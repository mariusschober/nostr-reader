import { READER_PROTOCOL } from "./core.js";

/** Bounded, authenticated teardown message. It carries no key material or content. */
export const DISCONNECT_TTL_SECS = 600;
const REASON = /^[a-z0-9][a-z0-9-]{0,47}$/;

export interface DisconnectPayloadV2 {
  protocol: typeof READER_PROTOCOL;
  type: "disconnect";
  createdAt: number;
  expiresAt: number;
  reasonCode: string;
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("disconnect must be an object");
  return value as Record<string, unknown>;
}

export function createDisconnectPayload(nowSecs: number, reasonCode = "user-disconnect"): DisconnectPayloadV2 {
  if (!Number.isSafeInteger(nowSecs) || nowSecs < 0) throw new Error("invalid disconnect time");
  if (!REASON.test(reasonCode)) throw new Error("invalid disconnect reason");
  return {
    protocol: READER_PROTOCOL,
    type: "disconnect",
    createdAt: nowSecs,
    expiresAt: nowSecs + DISCONNECT_TTL_SECS,
    reasonCode,
  };
}

export function validateDisconnectPayload(value: unknown, nowSecs: number): DisconnectPayloadV2 {
  const payload = record(value);
  if (payload["protocol"] !== READER_PROTOCOL || payload["type"] !== "disconnect") {
    throw new Error("wrong disconnect protocol or type");
  }
  const createdAt = payload["createdAt"];
  const expiresAt = payload["expiresAt"];
  const reasonCode = payload["reasonCode"];
  if (typeof createdAt !== "number" || typeof expiresAt !== "number" ||
      !Number.isSafeInteger(createdAt) || !Number.isSafeInteger(expiresAt)) {
    throw new Error("invalid disconnect time");
  }
  if (typeof reasonCode !== "string" || !REASON.test(reasonCode)) throw new Error("invalid disconnect reason");
  if (createdAt > nowSecs + 600 || createdAt < nowSecs - 172800) throw new Error("stale disconnect");
  if (expiresAt <= nowSecs || expiresAt - createdAt > DISCONNECT_TTL_SECS + 300) throw new Error("expired disconnect");
  return {
    protocol: READER_PROTOCOL,
    type: "disconnect",
    createdAt,
    expiresAt,
    reasonCode,
  };
}
