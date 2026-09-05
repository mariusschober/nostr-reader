import XCTest
@testable import ReaderCoreSwift

final class CoreTests: XCTestCase {
    let golden = "Hello Reader\n\nThis is a golden-vector article. It has two paragraphs.\n\n- one\n- two\n"
    func testGolden() {
        XCTAssertEqual(ReaderCore.canonicalize(golden), golden)
        XCTAssertEqual(ReaderCore.documentId(golden), "78dad25a190e768000a895240766cbbcaab76c3d49db0129b5ba703ce5bff2d8")
        XCTAssertEqual(ReaderCore.wordCount(golden), 13)
    }
    func testCodecV2VectorsAreByteExactAndDecodable() throws {
        let testFile = URL(fileURLWithPath: #filePath)
        let repository = testFile
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let data = try Data(contentsOf: repository.appendingPathComponent("shared/test-vectors/codec-v2.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(fixture["protocol"] as? String, "reader/2")
        let vectors = try XCTUnwrap(fixture["vectors"] as? [[String: Any]])
        for vector in vectors {
            let source = try XCTUnwrap(vector["source"] as? String)
            let canonical = try XCTUnwrap(vector["canonicalMarkdown"] as? String)
            let expected = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(vector["chromeGzipBase64"] as? String)))
            XCTAssertEqual(ReaderCore.canonicalize(source), canonical)
            let encoded = try ReaderGzip.encode(Data(canonical.utf8))
            XCTAssertEqual(encoded, expected, vector["name"] as? String ?? "vector")
            XCTAssertEqual(ReaderCore.sha256Hex(encoded), vector["chromeGzipSha256"] as? String)
            XCTAssertEqual(try ReaderGzip.decode(encoded), Data(canonical.utf8))
            XCTAssertEqual(try ReaderGzip.encode(Data(canonical.utf8)), encoded)
            for producer in ["chrome", "normative"] {
                let bytes = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(vector["\(producer)GzipBase64"] as? String)))
                XCTAssertEqual(ReaderCore.sha256Hex(bytes), vector["\(producer)GzipSha256"] as? String)
                XCTAssertEqual(try ReaderGzip.decode(bytes), Data(canonical.utf8))
            }
        }
    }
    func testCodecRejectsWrongTruncatedCorruptAndOversizedInput() throws {
        let good = try ReaderGzip.encode(Data("safe\n".utf8))
        XCTAssertThrowsError(try ReaderGzip.decode(Data([0x78, 0x9c]) + good.dropFirst(10).dropLast(8)))
        XCTAssertThrowsError(try ReaderGzip.decode(good.dropLast()))
        var corrupt = good
        corrupt[corrupt.count - 8] ^= 1
        XCTAssertThrowsError(try ReaderGzip.decode(corrupt))
        XCTAssertThrowsError(try ReaderGzip.decode(Data(count: ReaderCore.maxCompressedBytes + 1)))
        XCTAssertThrowsError(try ReaderGzip.encode(Data(count: ReaderCore.maxExpandedBytes + 1)))
    }
    func testCodecExpandedBoundary() throws {
        let boundary = Data(count: ReaderCore.maxExpandedBytes)
        XCTAssertEqual(try ReaderGzip.decode(ReaderGzip.encode(boundary)).count, boundary.count)
    }
    func testSenderNeverFallsBackToUncompressedBytes() throws {
        let prepared = try SenderPipeline.prepare(title: "Hello", markdown: golden)
        XCTAssertEqual(prepared.gzipped.prefix(10), ReaderGzip.header)
        XCTAssertEqual(try ReaderGzip.decode(prepared.gzipped), Data(golden.utf8))
    }
    func testSyncWindow() {
        XCTAssertEqual(ReaderCore.syncSince(nowSecs: 1725400000), 1725400000 - 864000)
    }
    func testRsvp() {
        XCTAssertEqual(ReaderCore.focalIndex(4), 1)
        XCTAssertEqual(ReaderCore.rsvpFactor(token: "end.", paragraphBreak: false, headingBreak: false), 2.2, accuracy: 1e-9)
    }
}
