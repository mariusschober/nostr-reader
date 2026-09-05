import Foundation
import zlib

public enum ReaderCodecError: Error, Equatable {
    case invalidExpandedSize
    case invalidCompressedSize
    case invalidGzipFraming
    case compressionFailed
    case decompressionFailed
}

/// Reader v2 deterministic gzip profile. Foundation's `.zlib` codec exposes
/// raw DEFLATE on macOS, so the RFC 1952 header and trailer are explicit.
public enum ReaderGzip {
    public static let header = Data([0x1f, 0x8b, 0x08, 0, 0, 0, 0, 0, 0, 3])
    private static let crcTable: [UInt32] = (0..<256).map { value in
        var entry = UInt32(value)
        for _ in 0..<8 {
            entry = (entry >> 1) ^ ((entry & 1) == 1 ? 0xedb88320 : 0)
        }
        return entry
    }

    public static func encode(_ input: Data) throws -> Data {
        guard (1...ReaderCore.maxExpandedBytes).contains(input.count) else {
            throw ReaderCodecError.invalidExpandedSize
        }
        let deflated: Data
        do {
            deflated = try (input as NSData).compressed(using: .zlib) as Data
        } catch {
            throw ReaderCodecError.compressionFailed
        }
        var output = header
        output.append(deflated)
        appendLittleEndian(crc32(input), to: &output)
        appendLittleEndian(UInt32(truncatingIfNeeded: input.count), to: &output)
        guard output.count <= ReaderCore.maxCompressedBytes else {
            throw ReaderCodecError.invalidCompressedSize
        }
        return output
    }

    public static func decode(_ input: Data) throws -> Data {
        guard (18...ReaderCore.maxCompressedBytes).contains(input.count) else {
            throw ReaderCodecError.invalidCompressedSize
        }
        guard input.prefix(header.count) == header else {
            throw ReaderCodecError.invalidGzipFraming
        }
        let compressedTail = input.subdata(in: header.count..<input.count)
        let (decoded, deflateBytes) = try inflateOneRawDeflateStream(compressedTail)
        let trailerStart = header.count + deflateBytes
        guard trailerStart + 8 == input.count else {
            throw ReaderCodecError.invalidGzipFraming
        }
        guard (1...ReaderCore.maxExpandedBytes).contains(decoded.count) else {
            throw ReaderCodecError.invalidExpandedSize
        }
        let expectedCrc = littleEndianUInt32(input, at: trailerStart)
        let expectedSize = littleEndianUInt32(input, at: trailerStart + 4)
        guard crc32(decoded) == expectedCrc,
              UInt32(truncatingIfNeeded: decoded.count) == expectedSize else {
            throw ReaderCodecError.decompressionFailed
        }
        return decoded
    }

    /// zlib reports unconsumed bytes at the first raw DEFLATE stream boundary.
    /// That boundary is required because Foundation's convenience decoder
    /// accepts a valid first stream followed by another gzip member.
    private static func inflateOneRawDeflateStream(_ input: Data) throws -> (Data, Int) {
        var stream = z_stream()
        guard inflateInit2_(
            &stream,
            -15,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        ) == Z_OK else {
            throw ReaderCodecError.decompressionFailed
        }
        defer { inflateEnd(&stream) }

        return try input.withUnsafeBytes { rawBuffer in
            guard let source = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
                throw ReaderCodecError.decompressionFailed
            }
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: source)
            stream.avail_in = uInt(input.count)
            var output = Data()
            let destinationSize = 64 * 1024
            let destination = UnsafeMutablePointer<UInt8>.allocate(capacity: destinationSize)
            defer { destination.deallocate() }
            while true {
                stream.next_out = destination
                stream.avail_out = uInt(destinationSize)
                let status = inflate(&stream, Z_NO_FLUSH)
                let produced = destinationSize - Int(stream.avail_out)
                if produced > 0 {
                    guard output.count + produced <= ReaderCore.maxExpandedBytes else {
                        throw ReaderCodecError.invalidExpandedSize
                    }
                    output.append(destination, count: produced)
                }
                if status == Z_STREAM_END {
                    return (output, input.count - Int(stream.avail_in))
                }
                if status != Z_OK || (produced == 0 && stream.avail_in == 0) {
                    throw ReaderCodecError.decompressionFailed
                }
            }
        }
    }

    private static func appendLittleEndian(_ value: UInt32, to data: inout Data) {
        data.append(UInt8(truncatingIfNeeded: value))
        data.append(UInt8(truncatingIfNeeded: value >> 8))
        data.append(UInt8(truncatingIfNeeded: value >> 16))
        data.append(UInt8(truncatingIfNeeded: value >> 24))
    }

    private static func littleEndianUInt32(_ data: Data, at offset: Int) -> UInt32 {
        UInt32(data[offset]) |
            (UInt32(data[offset + 1]) << 8) |
            (UInt32(data[offset + 2]) << 16) |
            (UInt32(data[offset + 3]) << 24)
    }

    private static func crc32(_ data: Data) -> UInt32 {
        var crc = UInt32.max
        for byte in data {
            crc = (crc >> 8) ^ crcTable[Int((crc ^ UInt32(byte)) & 0xff)]
        }
        return ~crc
    }
}
