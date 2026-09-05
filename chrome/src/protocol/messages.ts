import { READER_PROTOCOL, sha256HexBytes } from "./core.js";

const HEX_16 = /^[0-9a-f]{32}$/;
const HEX_32 = /^[0-9a-f]{64}$/;

export interface ManifestIdentityInput {
  transferId: string;
  documentId: string;
  compressedSha256: string;
  compressedBytes: number;
  chunkCount: number;
  senderDevicePubkey: string;
  recipientChannelPubkey: string;
  expiresAt: number;
}

export interface EndpointAckV2 {
  protocol: typeof READER_PROTOCOL;
  type: "ack";
  transferId: string;
  documentId: string;
  manifestId: string;
  contentHash: string;
  senderChannelPubkey: string;
  recipientDevicePubkey: string;
  status: "stored" | "duplicate" | "rejected";
  receivedAt: number;
  expiresAt: number;
  reasonCode?: string;
}

export async function manifestIdentity(input: ManifestIdentityInput): Promise<string> {
  return sha256HexBytes(new TextEncoder().encode(JSON.stringify([
    READER_PROTOCOL,
    input.transferId,
    input.documentId,
    input.compressedSha256,
    input.compressedBytes,
    input.chunkCount,
    input.senderDevicePubkey,
    input.recipientChannelPubkey,
    input.expiresAt,
  ])));
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("ACK must be an object");
  return value as Record<string, unknown>;
}

export function validateEndpointAck(
  value: unknown,
  expected: {
    transferId: string;
    documentId: string;
    manifestId: string;
    channelPubkey: string;
    devicePubkey: string;
  },
  verifiedInnerSenderPubkey: string,
  nowSecs: number,
): EndpointAckV2 {
  const payload = record(value);
  const optionalReason = Object.hasOwn(payload, "reasonCode");
  const wanted = [
    "protocol", "type", "transferId", "documentId", "manifestId", "contentHash",
    "senderChannelPubkey", "recipientDevicePubkey", "status", "receivedAt", "expiresAt",
    ...(optionalReason ? ["reasonCode"] : []),
  ].sort();
  const actual = Object.keys(payload).sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) throw new Error("ACK fields do not match protocol");
  if (payload["protocol"] !== READER_PROTOCOL || payload["type"] !== "ack") throw new Error("wrong ACK protocol or type");
  for (const [key, pattern] of [
    ["transferId", HEX_16], ["documentId", HEX_32], ["manifestId", HEX_32],
    ["contentHash", HEX_32], ["senderChannelPubkey", HEX_32], ["recipientDevicePubkey", HEX_32],
  ] as const) {
    if (typeof payload[key] !== "string" || !pattern.test(payload[key] as string)) throw new Error(`invalid ACK ${key}`);
  }
  if (!(["stored", "duplicate", "rejected"] as unknown[]).includes(payload["status"])) throw new Error("invalid ACK status");
  if (!Number.isSafeInteger(payload["receivedAt"]) || !Number.isSafeInteger(payload["expiresAt"])) throw new Error("invalid ACK timestamp");
  if (
    (payload["receivedAt"] as number) > nowSecs + 60 ||
    (payload["receivedAt"] as number) < nowSecs - 7 * 86400 - 60 ||
    (payload["expiresAt"] as number) <= nowSecs ||
    (payload["expiresAt"] as number) > (payload["receivedAt"] as number) + 7 * 86400 + 60
  ) throw new Error("ACK expired or timestamp invalid");
  if (optionalReason && (typeof payload["reasonCode"] !== "string" || !/^[a-z0-9][a-z0-9-]{0,47}$/.test(payload["reasonCode"] as string))) {
    throw new Error("invalid ACK reason code");
  }
  if ((payload["status"] === "rejected") !== optionalReason) throw new Error("ACK rejection reason mismatch");
  if (
    payload["transferId"] !== expected.transferId ||
    payload["documentId"] !== expected.documentId ||
    payload["manifestId"] !== expected.manifestId ||
    payload["contentHash"] !== expected.documentId ||
    payload["senderChannelPubkey"] !== expected.channelPubkey ||
    payload["recipientDevicePubkey"] !== expected.devicePubkey
  ) throw new Error("ACK binding mismatch");
  if (verifiedInnerSenderPubkey !== expected.channelPubkey) throw new Error("untrusted ACK sender");
  return payload as unknown as EndpointAckV2;
}
