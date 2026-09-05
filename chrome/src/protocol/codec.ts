import { gzip, ungzip } from "pako";
import { MAX_COMPRESSED_BYTES, MAX_EXPANDED_BYTES } from "./core.js";

/**
 * Reader v2 deterministic gzip profile:
 * RFC 1952, one member, DEFLATE level 6, no optional fields, mtime=0,
 * XFL=0, OS=3. Each pinned encoder is byte-deterministic; valid DEFLATE
 * encoders may choose different blocks, so both producer fixtures are kept.
 */
export const READER_GZIP_HEADER = new Uint8Array([
  0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03,
]);

function requireReaderHeader(bytes: Uint8Array): void {
  if (bytes.length < 18) throw new Error("truncated Reader gzip stream");
  for (let i = 0; i < READER_GZIP_HEADER.length; i += 1) {
    if (bytes[i] !== READER_GZIP_HEADER[i]) throw new Error("invalid Reader gzip framing");
  }
}

export function deterministicGzip(input: Uint8Array): Uint8Array {
  if (input.length < 1 || input.length > MAX_EXPANDED_BYTES) {
    throw new Error("expanded transfer must be 1 byte to 20 MiB");
  }
  // @types/pako omits the supported zlib header option. Keep the cast local;
  // exact bytes are pinned by codec-v2.json and four-runtime tests.
  const options = { level: 6, header: { time: 0, os: 3 } } as unknown as Parameters<typeof gzip>[1];
  const encoded = gzip(input, options);
  requireReaderHeader(encoded);
  if (encoded.length > MAX_COMPRESSED_BYTES) throw new Error("compressed transfer exceeds 5 MiB");
  return encoded;
}

/** Strict decoder used by cross-runtime and hostile-input tests. */
export function decodeReaderGzip(input: Uint8Array): Uint8Array {
  if (input.length > MAX_COMPRESSED_BYTES) throw new Error("compressed transfer exceeds 5 MiB");
  requireReaderHeader(input);
  let decoded: Uint8Array;
  try {
    decoded = ungzip(input);
  } catch {
    throw new Error("invalid or truncated Reader gzip stream");
  }
  if (!(decoded instanceof Uint8Array)) throw new Error("invalid or truncated Reader gzip stream");
  if (decoded.length < 1 || decoded.length > MAX_EXPANDED_BYTES) {
    throw new Error("expanded transfer must be 1 byte to 20 MiB");
  }
  return decoded;
}
