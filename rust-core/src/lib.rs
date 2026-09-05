//! reader-core: normative Reader v2 algorithms (PROTOCOL.md).
//! Thin shells (Kotlin/TS/Swift) must mirror this file and pass codec-v2.

use sha2::{Digest, Sha256};
use unicode_normalization::UnicodeNormalization;

pub const READER_PROTOCOL: &str = "reader/2";
pub const SYNC_WINDOW_DAYS: i64 = 10;
pub const TRANSPORT_TTL_DAYS: i64 = 7;
pub const MAX_COMPRESSED_BYTES: usize = 5 * 1024 * 1024;
pub const MAX_EXPANDED_BYTES: usize = 20 * 1024 * 1024;
pub const MAX_CHUNKS: usize = 512;
pub const MAX_TITLE_LEN: usize = 500;
pub const MAX_URL_LEN: usize = 2000;

/// Canonicalize markdown per PROTOCOL.md. Returns canonical text.
pub fn canonicalize(input: &str) -> String {
    let nfc: String = input.nfc().collect();
    let lf = nfc.replace("\r\n", "\n").replace('\r', "\n");
    let mut out_lines: Vec<String> = Vec::new();
    for line in lf.lines() {
        out_lines.push(line.trim_end().to_string());
    }
    let mut collapsed: Vec<String> = Vec::new();
    let mut blanks = 0;
    for l in out_lines {
        if l.trim().is_empty() {
            blanks += 1;
            if blanks <= 2 {
                collapsed.push(String::new());
            }
        } else {
            blanks = 0;
            collapsed.push(l);
        }
    }
    let mut s = collapsed.join("\n");
    // Drop a body H1 that duplicates the title is done by callers with metadata;
    // core guarantees the mechanical normal form only.
    s = s.trim_matches('\n').to_string() + "\n";
    s
}

/// Escape literal plain-text so it is never interpreted as markdown.
pub fn escape_plain_text(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 8);
    for ch in input.chars() {
        match ch {
            '\\' | '`' | '*' | '_' | '{' | '}' | '[' | ']' | '(' | ')' | '#' | '+' | '-' | '!' | '|' | '>' => {
                out.push('\\');
                out.push(ch);
            }
            _ => out.push(ch),
        }
    }
    out
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    hex_encode(Sha256::digest(bytes).as_slice())
}

fn hex_encode(bytes: &[u8]) -> String {
    const H: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(H[(b >> 4) as usize] as char);
        s.push(H[(b & 0xf) as usize] as char);
    }
    s
}

pub fn document_id(canonical_markdown: &str) -> String {
    sha256_hex(canonical_markdown.as_bytes())
}

/// Gzip the canonical document (mtime=0 for determinism) and split into
/// `chunk_size`-byte data slices. Caller wraps each slice in DocumentChunkV2.
pub fn pack_chunks(canonical: &str, chunk_size: usize) -> Result<(Vec<u8>, Vec<Vec<u8>>), String> {
    use flate2::{Compression, GzBuilder};
    if chunk_size == 0 {
        return Err("chunk_size must be > 0".into());
    }
    if canonical.is_empty() || canonical.len() > MAX_EXPANDED_BYTES {
        return Err("expanded transfer must be 1 byte to 20 MiB".into());
    }
    let mut enc = GzBuilder::new()
        .mtime(0)
        .operating_system(3)
        .write(Vec::new(), Compression::default());
    use std::io::Write;
    enc.write_all(canonical.as_bytes()).map_err(|e| e.to_string())?;
    let gz = enc.finish().map_err(|e| e.to_string())?;
    if gz.len() > MAX_COMPRESSED_BYTES {
        return Err("compressed transfer exceeds 5 MiB".into());
    }
    let mut chunks = Vec::new();
    let mut i = 0;
    while i < gz.len() {
        let j = (i + chunk_size).min(gz.len());
        chunks.push(gz[i..j].to_vec());
        i = j;
    }
    if chunks.len() > MAX_CHUNKS {
        return Err("chunk count exceeds 512".into());
    }
    Ok((gz, chunks))
}

/// Decode the one-member deterministic Reader v2 gzip profile with strict
/// compressed and expanded limits. CRC/ISIZE are verified by flate2.
pub fn unpack_gzip(gzip: &[u8]) -> Result<Vec<u8>, String> {
    use flate2::read::GzDecoder;
    use std::io::Read;
    const HEADER: [u8; 10] = [0x1f, 0x8b, 0x08, 0, 0, 0, 0, 0, 0, 3];
    if gzip.len() < 18 || gzip.len() > MAX_COMPRESSED_BYTES {
        return Err("invalid Reader gzip size".into());
    }
    if gzip[..10] != HEADER {
        return Err("invalid Reader gzip framing".into());
    }
    let mut decoder = GzDecoder::new(gzip);
    let mut decoded = Vec::new();
    decoder
        .by_ref()
        .take((MAX_EXPANDED_BYTES + 1) as u64)
        .read_to_end(&mut decoded)
        .map_err(|_| "invalid or truncated Reader gzip stream".to_string())?;
    if decoded.is_empty() || decoded.len() > MAX_EXPANDED_BYTES {
        return Err("expanded transfer must be 1 byte to 20 MiB".into());
    }
    Ok(decoded)
}

/// Rolling-window sync: return query `since` for now (unix secs).
/// NEVER use last-sync directly (NIP-59 randomized past timestamps).
pub fn sync_since(now_secs: i64) -> i64 {
    now_secs - SYNC_WINDOW_DAYS * 86400
}

/// Count readable words (excludes fenced code + raw URLs, best-effort).
pub fn word_count(canonical: &str) -> usize {
    let mut in_fence = false;
    let mut n = 0;
    for line in canonical.lines() {
        let t = line.trim();
        if t.starts_with("```") {
            in_fence = !in_fence;
            continue;
        }
        if in_fence {
            continue;
        }
        for w in t.split_whitespace() {
            if w.starts_with("http://") || w.starts_with("https://") {
                continue;
            }
            // Skip standalone list/quote markers so counts match TS/Kotlin/Swift.
            let marker = (w.chars().all(|c| matches!(c, '-' | '*' | '+' | '>')))
                || (w.len() >= 2 && w[..w.len() - 1].chars().all(|c| c.is_ascii_digit())
                    && matches!(w.chars().last(), Some('.') | Some(')')));
            if marker {
                continue;
            }
            n += 1;
        }
    }
    n
}

pub fn reading_minutes(word_count: usize) -> u32 {
    ((word_count + 224) / 225).max(1) as u32
}

static PUNCT_DELAY: &[(char, f64)] = &[(',', 1.4), (';', 1.4), (':', 1.4), ('.', 2.2), ('!', 2.2), ('?', 2.2)];

/// RSVP dwell multiplier for one token. Mirrors the TS/Kotlin policy object.
pub fn rsvp_factor(token: &str, paragraph_break: bool, heading_break: bool) -> f64 {
    if heading_break || paragraph_break {
        return 2.7;
    }
    let len = token.chars().count();
    let mut f: f64 = 1.0;
    if len > 12 {
        f = 1.2;
    } else if len > 8 {
        f = 1.1;
    }
    if let Some(last) = token.chars().last() {
        for (p, m) in PUNCT_DELAY {
            if last == *p {
                f = f.max(*m);
            }
        }
    }
    f
}

/// Focal-letter index for RSVP (fixed-X anchor). Same table everywhere.
pub fn focal_index(token_len: usize) -> usize {
    match token_len {
        0 => 0,
        1 => 0,
        2..=5 => 1,
        6..=9 => 2,
        10..=13 => 3,
        _ => ((token_len as f64 * 0.35).round() as usize).min(token_len - 1),
    }
}

/// Split semantic text into narration sentences (TTS model: skip code).
pub fn narrate_sentences(blocks: &[(&str, bool)]) -> Vec<String> {
    // (text, is_code) pairs; code blocks skipped.
    let mut out = Vec::new();
    for (text, is_code) in blocks {
        if *is_code {
            continue;
        }
        let mut cur = String::new();
        for ch in text.chars() {
            cur.push(ch);
            if matches!(ch, '.' | '!' | '?' | '\n') && cur.trim().len() > 1 {
                let s = cur.trim().to_string();
                if !s.is_empty() {
                    out.push(s);
                }
                cur = String::new();
            }
        }
        let s = cur.trim().to_string();
        if !s.is_empty() {
            out.push(s);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn codec_fixture() -> Value {
        serde_json::from_str(include_str!("../../shared/test-vectors/codec-v2.json")).unwrap()
    }

    #[test]
    fn golden_document_id() {
        let md = "Hello Reader\n\nThis is a golden-vector article. It has two paragraphs.\n\n- one\n- two\n";
        assert_eq!(canonicalize(md), md);
        assert_eq!(
            document_id(md),
            "78dad25a190e768000a895240766cbbcaab76c3d49db0129b5ba703ce5bff2d8"
        );
    }

    #[test]
    fn codec_v2_vectors_are_exact_and_decodable() {
        let fixture = codec_fixture();
        assert_eq!(fixture["protocol"], READER_PROTOCOL);
        for vector in fixture["vectors"].as_array().unwrap() {
            let source = vector["source"].as_str().unwrap();
            let canonical = vector["canonicalMarkdown"].as_str().unwrap();
            assert_eq!(canonicalize(source), canonical);
            let (gzip, chunks) = pack_chunks(canonical, 32 * 1024).unwrap();
            assert_eq!(chunks.len(), 1);
            assert_eq!(sha256_hex(&gzip), vector["normativeGzipSha256"].as_str().unwrap());
            assert_eq!(hex_encode(&gzip), vector["normativeGzipHex"].as_str().unwrap());
            let chrome = vector["chromeGzipHex"].as_str().unwrap();
            let chrome_bytes = (0..chrome.len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&chrome[i..i + 2], 16).unwrap())
                .collect::<Vec<_>>();
            assert_eq!(unpack_gzip(&chrome_bytes).unwrap(), canonical.as_bytes());
            assert_eq!(unpack_gzip(&gzip).unwrap(), canonical.as_bytes());
            assert_eq!(pack_chunks(canonical, 32 * 1024).unwrap().0, gzip);
        }
    }

    #[test]
    fn codec_v2_rejects_wrong_truncated_corrupt_and_oversized_input() {
        use flate2::{Compression, GzBuilder};
        use std::io::Write;
        let (good, _) = pack_chunks("safe\n", 1024).unwrap();
        let mut zlib = vec![0x78, 0x9c];
        zlib.extend_from_slice(&good[10..good.len() - 8]);
        assert!(unpack_gzip(&zlib).is_err());
        assert!(unpack_gzip(&good[..good.len() - 1]).is_err());
        let mut corrupt = good.clone();
        let trailer = corrupt.len() - 8;
        corrupt[trailer] ^= 1;
        assert!(unpack_gzip(&corrupt).is_err());
        assert!(unpack_gzip(&vec![0; MAX_COMPRESSED_BYTES + 1]).is_err());
        assert!(pack_chunks(&"x".repeat(MAX_EXPANDED_BYTES + 1), 1024).is_err());

        let too_large = vec![0u8; MAX_EXPANDED_BYTES + 1];
        let mut encoder = GzBuilder::new()
            .mtime(0)
            .operating_system(3)
            .write(Vec::new(), Compression::default());
        encoder.write_all(&too_large).unwrap();
        assert!(unpack_gzip(&encoder.finish().unwrap()).is_err());
    }

    #[test]
    fn word_count_and_minutes() {
        let md = "Hello Reader\n\nThis is a golden-vector article. It has two paragraphs.\n\n- one\n- two\n";
        assert_eq!(word_count(md), 13);
        assert_eq!(reading_minutes(13), 1);
        assert_eq!(reading_minutes(225), 1);
        assert_eq!(reading_minutes(226), 2);
    }

    #[test]
    fn rsvp_policy_spot() {
        assert_eq!(focal_index(1), 0);
        assert_eq!(focal_index(4), 1);
        assert_eq!(focal_index(8), 2);
        assert_eq!(focal_index(12), 3);
        assert!((rsvp_factor("word", false, false) - 1.0).abs() < 1e-9);
        assert!((rsvp_factor("end.", false, false) - 2.2).abs() < 1e-9);
        assert!((rsvp_factor("x", true, false) - 2.7).abs() < 1e-9);
    }

    #[test]
    fn sync_window_is_10_days() {
        assert_eq!(sync_since(1_725_400_000) , 1_725_400_000 - 864000);
    }
}
