export type PairingLifecycleState =
  | "waiting_response"
  | "response_validated"
  | "waiting_completion"
  | "complete"
  | "expired"
  | "cancelled"
  | "superseded";

export const PAIR_ACK_RETRY_SECS = 30;
const PAIR_ACK_NO_OK_ERROR = "No pairing relay accepted Chrome's authenticated acknowledgement yet.";

export interface SecretBearingPairingSession {
  state: PairingLifecycleState;
  pairingSeckey?: string;
  lastError?: string;
}

/** Stable key list for a local disconnect. Device identity and queued captures remain. */
export const PAIRED_CHANNEL_STORAGE_KEYS = [
  "channelPubkey",
  "channelDevicePubkey",
  "relays",
  "channelRelaySetDigest",
  "protocolVersion",
] as const;

export function isActivePairingState(state: PairingLifecycleState): boolean {
  return state === "waiting_response" || state === "response_validated" || state === "waiting_completion";
}

/** Throttle durable pairing-ACK retries despite the UI polling every 2.5s. */
export function pairingAckRetryDue(lastAttemptAt: unknown, nowSecs: number): boolean {
  if (!Number.isSafeInteger(nowSecs) || nowSecs < 0) throw new Error("invalid pairing retry clock");
  if (!Number.isSafeInteger(lastAttemptAt) || Number(lastAttemptAt) < 0 || Number(lastAttemptAt) > nowSecs + 60) {
    return true;
  }
  return nowSecs - Number(lastAttemptAt) >= PAIR_ACK_RETRY_SECS;
}

/** Endpoint completion remains authoritative even when no relay returned OK. */
export function pairingAckAttemptTransition(
  acceptedRelayCount: number,
  nowSecs: number,
): { state: "waiting_completion"; lastAckAttemptAt: number; lastError: string | undefined } {
  if (!Number.isSafeInteger(acceptedRelayCount) || acceptedRelayCount < 0) {
    throw new Error("invalid pairing ACK relay count");
  }
  if (!Number.isSafeInteger(nowSecs) || nowSecs < 0) throw new Error("invalid pairing retry clock");
  return acceptedRelayCount > 0
    ? { state: "waiting_completion", lastAckAttemptAt: nowSecs, lastError: undefined }
    : { state: "waiting_completion", lastAckAttemptAt: nowSecs, lastError: PAIR_ACK_NO_OK_ERROR };
}

/** Remove—not merely overwrite—the one-time bootstrap secret before persistence. */
export function stripPairingSecret<T extends { pairingSeckey?: string }>(session: T): Omit<T, "pairingSeckey"> {
  const { pairingSeckey: _discarded, ...safe } = session;
  return safe;
}

export function cancelActivePairingSessions<T extends SecretBearingPairingSession>(
  sessions: readonly T[],
  lastError: string,
): T[] {
  return sessions.map((session) => {
    if (!isActivePairingState(session.state)) return session;
    return {
      ...stripPairingSecret(session),
      state: "cancelled",
      lastError,
    } as T;
  });
}
