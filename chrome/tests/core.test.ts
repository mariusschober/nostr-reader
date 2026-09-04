import { describe, it, expect } from "vitest";
import { gunzipSync } from "node:zlib";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createHash } from "node:crypto";
import {
  canonicalize, documentId, wordCount, readingMinutes, focalIndex,
  rsvpFactor, syncSince, checkLimits,
} from "../src/protocol/core.js";

const GOLDEN = "Hello Reader\n\nThis is a golden-vector article. It has two paragraphs.\n\n- one\n- two\n";

describe("golden vector", () => {
  it("canonical form is stable and hashes to documentId", async () => {
    expect(canonicalize(GOLDEN)).toBe(GOLDEN);
    expect(await documentId(GOLDEN)).toBe("78dad25a190e768000a895240766cbbcaab76c3d49db0129b5ba703ce5bff2d8");
  });
  it("chunk base64 gunzips back to canonical bytes", () => {
    const v = JSON.parse(readFileSync(join(process.cwd(), "..", "shared", "test-vectors", "golden-v1.json"), "utf8"));
    const gz = Buffer.from(v.chunk.dataBase64, "base64");
    expect(createHash("sha256").update(gz).digest("hex")).toBe(v.compressedSha256);
    expect(gunzipSync(gz).toString("utf8")).toBe(v.canonicalMarkdown);
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
    expect(() => checkLimits({ compressedBytes: 6 * 1024 * 1024, titleLen: 1, urlLen: 1, chunkCount: 1 })).toThrow();
    expect(() => checkLimits({ compressedBytes: 10, titleLen: 1, urlLen: 1, chunkCount: 513 })).toThrow();
  });
});
