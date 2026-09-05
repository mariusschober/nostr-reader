import CryptoKit
import Foundation

/// Swift mirror of reader-core + PROTOCOL.md. Codec-v2 gates every release.
/// Full app binds the Rust crate via UniFFI; this package pins the algorithms
/// and ships the Mac sender + reader UI against the same vectors.
public enum ReaderCore {
    public static let protocolVersion = "reader/2"
    public static let syncWindowDays = 10
    public static let maxCompressedBytes = 5 * 1024 * 1024
    public static let maxExpandedBytes = 20 * 1024 * 1024
    public static let maxChunks = 512

    public static func canonicalize(_ input: String) -> String {
        var s = input.precomposedStringWithCanonicalMapping
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let lines = s.components(separatedBy: "\n").map {
            $0.replacingOccurrences(of: "[ \\t]+$", with: "", options: .regularExpression)
        }
        var collapsed: [String] = []
        var blanks = 0
        for l in lines {
            if l.trimmingCharacters(in: .whitespaces).isEmpty {
                blanks += 1
                if blanks <= 2 { collapsed.append("") }
            } else { blanks = 0; collapsed.append(l) }
        }
        s = collapsed.joined(separator: "\n")
        while s.hasPrefix("\n") { s.removeFirst() }
        while s.hasSuffix("\n") { s.removeLast() }
        return s + "\n"
    }

    public static func sha256Hex(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    public static func documentId(_ canonical: String) -> String {
        sha256Hex(Data(canonical.utf8))
    }

    public static func syncSince(nowSecs: Int) -> Int {
        // CRITICAL: rolling window, never last-sync (NIP-59 randomized past timestamps).
        nowSecs - syncWindowDays * 86400
    }

    static let skipTokens = try! NSRegularExpression(pattern: "^[-*+>]+$|^\\d+[.)]$")

    public static func wordCount(_ canonical: String) -> Int {
        var inFence = false
        var n = 0
        for line in canonical.components(separatedBy: "\n") {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("```") { inFence.toggle(); continue }
            if inFence { continue }
            for w in t.components(separatedBy: .whitespaces).filter({ !$0.isEmpty }) {
                if w.hasPrefix("http://") || w.hasPrefix("https://") { continue }
                let r = NSRange(w.startIndex..., in: w)
                if skipTokens.firstMatch(in: w, range: r) != nil { continue }
                n += 1
            }
        }
        return n
    }

    public static func readingMinutes(_ words: Int) -> Int { max(1, (words + 224) / 225) }

    public static func focalIndex(_ len: Int) -> Int {
        switch len {
        case ...1: return 0
        case 2...5: return 1
        case 6...9: return 2
        case 10...13: return 3
        default: return min(len - 1, Int((Double(len) * 0.35).rounded()))
        }
    }

    public static func rsvpFactor(token: String, paragraphBreak: Bool, headingBreak: Bool) -> Double {
        if headingBreak || paragraphBreak { return 2.7 }
        let len = token.count
        var f = 1.0
        if len > 12 { f = 1.2 } else if len > 8 { f = 1.1 }
        if let last = token.last {
            if ".!?".contains(last) { f = max(f, 2.2) }
            else if ",;:".contains(last) { f = max(f, 1.4) }
        }
        return f
    }
}
