import Foundation

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
        let trailerStart = input.count - 8
        let deflated = input.subdata(in: header.count..<trailerStart)
        let decoded: Data
        do {
            decoded = try (deflated as NSData).decompressed(using: .zlib) as Data
        } catch {
            throw ReaderCodecError.decompressionFailed
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
