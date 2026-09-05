import Foundation

/// Mac sender pipeline contract (menu bar / Share extension / Quick Action /
/// Services all converge here). Mirrors chrome queueCapture + Android ingest.
public enum SenderPipeline {
    public struct Prepared {
        public let transferId: String
        public let documentId: String
        public let title: String
        public let canonical: String
        public let gzipped: Data
        public let wordCount: Int
    }

    public static func prepare(title: String, markdown: String) throws -> Prepared {
        let canonical = ReaderCore.canonicalize(markdown)
        let data = Data(canonical.utf8)
        let gzipped = try ReaderGzip.encode(data)
        return Prepared(
            transferId: UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
            documentId: ReaderCore.documentId(canonical),
            title: String(title.prefix(500)),
            canonical: canonical,
            gzipped: gzipped,
            wordCount: ReaderCore.wordCount(canonical)
        )
    }
}
