import XCTest
@testable import ReaderCoreSwift

final class CoreTests: XCTestCase {
    let golden = "Hello Reader\n\nThis is a golden-vector article. It has two paragraphs.\n\n- one\n- two\n"
    func testGolden() {
        XCTAssertEqual(ReaderCore.canonicalize(golden), golden)
        XCTAssertEqual(ReaderCore.documentId(golden), "78dad25a190e768000a895240766cbbcaab76c3d49db0129b5ba703ce5bff2d8")
        XCTAssertEqual(ReaderCore.wordCount(golden), 13)
    }
    func testSyncWindow() {
        XCTAssertEqual(ReaderCore.syncSince(nowSecs: 1725400000), 1725400000 - 864000)
    }
    func testRsvp() {
        XCTAssertEqual(ReaderCore.focalIndex(4), 1)
        XCTAssertEqual(ReaderCore.rsvpFactor(token: "end.", paragraphBreak: false, headingBreak: false), 2.2, accuracy: 1e-9)
    }
}
