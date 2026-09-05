import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createHash } from "node:crypto";
import {
  canonicalize, documentId, wordCount, readingMinutes, focalIndex,
  rsvpFactor, syncSince, checkLimits, MAX_COMPRESSED_BYTES, MAX_EXPANDED_BYTES,
} from "../src/protocol/core.js";
import { decodeReaderGzip, deterministicGzip, READER_GZIP_HEADER } from "../src/protocol/codec.js";

const GOLDEN = "Hello Reader\n\nThis is a golden-vector article. It has two paragraphs.\n\n- one\n- two\n";

describe("golden vector", () => {
  it("canonical form is stable and hashes to documentId", async () => {
    expect(canonicalize(GOLDEN)).toBe(GOLDEN);
    expect(await documentId(GOLDEN)).toBe("78dad25a190e768000a895240766cbbcaab76c3d49db0129b5ba703ce5bff2d8");
  });
  it("Chrome uses the strict deterministic gzip encoder", () => {
    const source = readFileSync(join(process.cwd(), "src", "background", "service-worker.ts"), "utf8");
    expect(source).toMatch(/const\s+gz\s*=\s*deterministicGzip\(/);
    expect(source).not.toMatch(/\bdeflate\(/);
  });
  it("emits and decodes every shared codec-v2 byte vector exactly", () => {
    const fixture = JSON.parse(readFileSync(join(process.cwd(), "..", "shared", "test-vectors", "codec-v2.json"), "utf8"));
    expect(fixture.protocol).toBe("reader/2");
    for (const v of fixture.vectors) {
      const canonical = canonicalize(v.source);
      expect(canonical).toBe(v.canonicalMarkdown);
      const encoded = deterministicGzip(new TextEncoder().encode(canonical));
      expect(Buffer.from(encoded).toString("base64"), v.name).toBe(v.chromeGzipBase64);
      expect(createHash("sha256").update(encoded).digest("hex"), v.name).toBe(v.chromeGzipSha256);
      for (const producer of ["chrome", "normative"] as const) {
        const fixtureBytes = Buffer.from(v[`${producer}GzipBase64`], "base64");
        expect(createHash("sha256").update(fixtureBytes).digest("hex"), `${v.name}/${producer}`).toBe(v[`${producer}GzipSha256`]);
        expect(new TextDecoder("utf-8", { fatal: true }).decode(decodeReaderGzip(fixtureBytes)), `${v.name}/${producer}`).toBe(canonical);
      }
      expect([...encoded.slice(0, 10)], v.name).toEqual([...READER_GZIP_HEADER]);
      expect(deterministicGzip(new TextEncoder().encode(canonical)), v.name).toEqual(encoded);
    }
  });
  it("rejects zlib framing, truncation, CRC corruption, and size abuse", () => {
    const good = deterministicGzip(new TextEncoder().encode("safe\n"));
    const zlibFramed = good.slice();
    zlibFramed[0] = 0x78;
    zlibFramed[1] = 0x9c;
    expect(() => decodeReaderGzip(zlibFramed)).toThrow(/framing/);
    expect(() => decodeReaderGzip(good.slice(0, -1))).toThrow(/invalid|truncated/);
    const corrupted = good.slice();
    corrupted[corrupted.length - 8] ^= 1;
    expect(() => decodeReaderGzip(corrupted)).toThrow(/invalid|truncated/);
    const second = deterministicGzip(new TextEncoder().encode("second\n"));
    const concatenated = new Uint8Array(good.length + second.length);
    concatenated.set(good);
    concatenated.set(second, good.length);
    expect(() => decodeReaderGzip(concatenated)).toThrow(/framing|member|trailing/);
    const trailing = new Uint8Array(good.length + 1);
    trailing.set(good);
    expect(() => decodeReaderGzip(trailing)).toThrow(/framing|member|trailing/);
    expect(() => decodeReaderGzip(new Uint8Array(MAX_COMPRESSED_BYTES + 1))).toThrow(/compressed/);
    expect(() => deterministicGzip(new Uint8Array(MAX_EXPANDED_BYTES + 1))).toThrow(/expanded/);
  });
  it("accepts the exact expanded boundary and rejects one byte more", () => {
    const boundary = new Uint8Array(MAX_EXPANDED_BYTES);
    const encoded = deterministicGzip(boundary);
    expect(decodeReaderGzip(encoded)).toHaveLength(MAX_EXPANDED_BYTES);
    expect(() => deterministicGzip(new Uint8Array(MAX_EXPANDED_BYTES + 1))).toThrow(/expanded/);
  });
  it("word count and reading time", () => {
    expect(wordCount(GOLDEN)).toBe(13);
    expect(readingMinutes(13)).toBe(1);
    expect(readingMinutes(226)).toBe(2);
  });
});

describe("rsvp policy", () => {
  it("focal table", () => {
    expect(focalIndex(1)).toBe(0);
    expect(focalIndex(4)).toBe(1);
    expect(focalIndex(8)).toBe(2);
    expect(focalIndex(12)).toBe(3);
  });
  it("dwell factors", () => {
    expect(rsvpFactor("word", false, false)).toBe(1);
    expect(rsvpFactor("end.", false, false)).toBe(2.2);
    expect(rsvpFactor("x", true, false)).toBe(2.7);
  });
});

describe("sync rule", () => {
  it("uses a 10-day rolling window, never last-sync", () => {
    expect(syncSince(1725400000)).toBe(1725400000 - 864000);
  });
});

describe("limits", () => {
  it("rejects oversize transfers", () => {
    expect(() => checkLimits({ compressedBytes: 6 * 1024 * 1024, expandedBytes: 1, titleLen: 1, urlLen: 1, chunkCount: 1 })).toThrow();
    expect(() => checkLimits({ compressedBytes: 10, expandedBytes: 1, titleLen: 1, urlLen: 1, chunkCount: 513 })).toThrow();
    expect(() => checkLimits({ compressedBytes: 10, expandedBytes: MAX_EXPANDED_BYTES + 1, titleLen: 1, urlLen: 1, chunkCount: 1 })).toThrow();
  });
});
