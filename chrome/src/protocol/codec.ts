import { gzip, Inflate } from "pako";
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

const CRC32_TABLE = Uint32Array.from({ length: 256 }, (_unused, value) => {
  let entry = value;
  for (let bit = 0; bit < 8; bit += 1) {
    entry = (entry >>> 1) ^ ((entry & 1) === 1 ? 0xedb88320 : 0);
  }
  return entry >>> 0;
});

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = (crc >>> 8) ^ CRC32_TABLE[(crc ^ byte) & 0xff]!;
  return (crc ^ 0xffffffff) >>> 0;
}

function littleEndianU32(bytes: Uint8Array, offset: number): number {
  return new DataView(bytes.buffer, bytes.byteOffset + offset, 4).getUint32(0, true);
}

/** Inflate exactly the first raw DEFLATE stream and expose its byte boundary. */
function inflateSingleMember(input: Uint8Array): { decoded: Uint8Array; trailerOffset: number } {
  const inflater = new Inflate({ raw: true, chunkSize: 64 * 1024 });
  const chunks: Uint8Array[] = [];
  let expandedBytes = 0;
  let exceededLimit = false;
  inflater.onData = (chunk) => {
    const bytes = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk);
    expandedBytes += bytes.length;
    if (expandedBytes > MAX_EXPANDED_BYTES) {
      exceededLimit = true;
      throw new Error("expanded transfer must be 1 byte to 20 MiB");
    }
    chunks.push(bytes.slice());
  };
  try {
    if (!inflater.push(input.subarray(READER_GZIP_HEADER.length), true) || inflater.err !== 0) {
      throw new Error("invalid or truncated Reader gzip stream");
    }
  } catch (error) {
    if (exceededLimit) throw new Error("expanded transfer must be 1 byte to 20 MiB");
    throw error;
  }
  // Pako's public convenience decoder deliberately accepts concatenated gzip
  // members. Its pinned raw inflater retains zlib's exact consumed-input count,
  // which lets Reader reject a second member or arbitrary trailing bytes.
  const stream = (inflater as unknown as { strm?: { next_in?: unknown } }).strm;
  const consumed = stream?.next_in;
  if (!Number.isSafeInteger(consumed) || Number(consumed) < 1) {
    throw new Error("invalid or truncated Reader gzip stream");
  }
  const trailerOffset = READER_GZIP_HEADER.length + Number(consumed);
  const decoded = new Uint8Array(expandedBytes);
  let offset = 0;
  for (const chunk of chunks) {
    decoded.set(chunk, offset);
    offset += chunk.length;
  }
  return { decoded, trailerOffset };
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
  let trailerOffset: number;
  try {
    ({ decoded, trailerOffset } = inflateSingleMember(input));
  } catch (error) {
    if (error instanceof Error && error.message.includes("expanded transfer")) throw error;
    throw new Error("invalid or truncated Reader gzip stream");
  }
  if (trailerOffset + 8 !== input.length) {
    throw new Error("invalid Reader gzip: expected exactly one member and no trailing bytes");
  }
  if (decoded.length < 1 || decoded.length > MAX_EXPANDED_BYTES) {
    throw new Error("expanded transfer must be 1 byte to 20 MiB");
  }
  if (
    littleEndianU32(input, trailerOffset) !== crc32(decoded) ||
    littleEndianU32(input, trailerOffset + 4) !== decoded.length
  ) {
    throw new Error("invalid or truncated Reader gzip stream");
  }
  return decoded;
}
