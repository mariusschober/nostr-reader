// Reader core algorithms (TS mirror of rust-core + PROTOCOL.md).
export const READER_PROTOCOL = "reader/1";
export const RUMOR_KIND = 30078;
export const SYNC_WINDOW_DAYS = 10;
export const MAX_COMPRESSED_BYTES = 5 * 1024 * 1024;
export const MAX_CHUNKS = 512;
export const MAX_TITLE_LEN = 500;
export const MAX_URL_LEN = 2000;

export function canonicalize(input: string): string {
  const nfc = input.normalize("NFC");
  const lf = nfc.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  const lines = lf.split("\n").map((l) => l.replace(/[ \t]+$/g, ""));
  const collapsed: string[] = [];
  let blanks = 0;
  for (const l of lines) {
    if (l.trim() === "") {
      blanks += 1;
      if (blanks <= 2) collapsed.push("");
    } else {
      blanks = 0;
      collapsed.push(l);
    }
  }
  return collapsed.join("\n").replace(/^\n+|\n+$/g, "") + "\n";
}

export function escapePlainText(input: string): string {
  return input.replace(/([\\`*_{}[\]()#+\-!|>])/g, "\\$1");
}

export async function sha256HexBytes(bytes: Uint8Array): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", bytes as BufferSource);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function documentId(canonical: string): Promise<string> {
  return sha256HexBytes(new TextEncoder().encode(canonical));
}

export function syncSince(nowSecs: number): number {
  // CRITICAL: rolling window, never last-sync (NIP-59 randomized past timestamps).
  return nowSecs - SYNC_WINDOW_DAYS * 86400;
}

export function wordCount(canonical: string): number {
  let inFence = false;
  let n = 0;
  for (const line of canonical.split("\n")) {
    const t = line.trim();
    if (t.startsWith("```")) { inFence = !inFence; continue; }
    if (inFence) continue;
    for (const w of t.split(/\s+/)) {
      if (!w) continue;
      if (w.startsWith("http://") || w.startsWith("https://")) continue;
      if (/^[-*+>]+$/.test(w) || /^\d+[.)]$/.test(w)) continue;
      n += 1;
    }
  }
  return n;
}

export function readingMinutes(words: number): number {
  return Math.max(1, Math.ceil(words / 225));
}

// RSVP policy object (single source; Android/Mac mirror it).
export function focalIndex(tokenLen: number): number {
  if (tokenLen <= 1) return 0;
  if (tokenLen <= 5) return 1;
  if (tokenLen <= 9) return 2;
  if (tokenLen <= 13) return 3;
  return Math.min(tokenLen - 1, Math.round(tokenLen * 0.35));
}

export function rsvpFactor(token: string, paragraphBreak: boolean, headingBreak: boolean): number {
  if (headingBreak || paragraphBreak) return 2.7;
  const len = [...token].length;
  let f = 1.0;
  if (len > 12) f = 1.2; else if (len > 8) f = 1.1;
  const last = token.slice(-1);
  if (last === "." || last === "!" || last === "?") f = Math.max(f, 2.2);
  else if (last === "," || last === ";" || last === ":") f = Math.max(f, 1.4);
  return f;
}

export function checkLimits(args: { compressedBytes: number; titleLen: number; urlLen: number; chunkCount: number }): void {
  if (args.compressedBytes > MAX_COMPRESSED_BYTES) throw new Error("compressed transfer exceeds 5 MiB");
  if (args.chunkCount > MAX_CHUNKS) throw new Error("chunk count exceeds 512");
  if (args.titleLen > MAX_TITLE_LEN) throw new Error("title too long");
  if (args.urlLen > MAX_URL_LEN) throw new Error("url too long");
}
