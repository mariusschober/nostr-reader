export type PairingLifecycleState =
  | "waiting_response"
  | "response_validated"
  | "waiting_completion"
  | "complete"
  | "expired"
  | "cancelled"
  | "superseded";

export interface SecretBearingPairingSession {
  state: PairingLifecycleState;
  pairingSeckey?: string;
  lastError?: string;
}

/** Stable key list for a local disconnect. Device identity and queued captures remain. */
export const PAIRED_CHANNEL_STORAGE_KEYS = [
  "channelPubkey",
  "relays",
  "channelRelaySetDigest",
  "protocolVersion",
] as const;

export function isActivePairingState(state: PairingLifecycleState): boolean {
  return state === "waiting_response" || state === "response_validated" || state === "waiting_completion";
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
