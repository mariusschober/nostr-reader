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
}): Promise<boolean> {
  const now = options.now ?? Date.now;
  for (let index = 0; index < options.snapshot.payloadCount; index++) {
    if (!await options.current()) return false;
    const required = options.snapshot.relays.filter(relay => {
      const previous = options.snapshot.progress[String(index)]?.[relay];
      return previous?.state !== "OK_TRUE" || now() - previous.at >= 15 * 60_000;
    });
    const results = await Promise.all(required.map(async relay => {
      if (!await options.current()) return false;
      const outcome = await options.send(index, relay);
      return options.checkpoint(index, relay, outcome);
    }));
    if (results.some(result => !result)) return false;
  }
  return options.current();
}
