export interface FragmentOutcome { state: string; at: number }
export interface PublicationSnapshot {
  relays: string[];
  payloadCount: number;
  progress: Record<string, Record<string, FragmentOutcome>>;
}

/** Executes the production publication loop; all durable mutations are supplied
 * as short guarded transactions. No state lock spans a network operation. */
export async function publishFragments(options: {
  snapshot: PublicationSnapshot;
  current: () => Promise<boolean>;
  send: (index: number, relay: string) => Promise<FragmentOutcome>;
  checkpoint: (index: number, relay: string, outcome: FragmentOutcome) => Promise<boolean>;
  now?: () => number;
  /** Explicit receipt recovery needs fresh authenticated demand, not payload replay. */
  refreshManifest?: boolean;
}): Promise<boolean> {
  const now = options.now ?? Date.now;
  // Each relay advances independently. A slow redundant manifest must not
  // prevent the healthy quorum from receiving the document's later chunks.
  // The validated relay set is capped at eight; each owns one in-flight frame.
  const results = await Promise.all(options.snapshot.relays.map(async relay => {
    for (let index = 0; index < options.snapshot.payloadCount; index++) {
      if (!await options.current()) return false;
      const previous = options.snapshot.progress[String(index)]?.[relay];
      if (previous?.state === "OK_TRUE" && now() - previous.at < 15 * 60_000 &&
        !(index === 0 && options.refreshManifest)) continue;
      const outcome = await options.send(index, relay);
      if (!await options.checkpoint(index, relay, outcome)) return false;
    }
    return true;
  }));
  return results.every(Boolean) && await options.current();
}
