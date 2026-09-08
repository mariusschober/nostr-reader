export interface ScanWindow { since: number; until: number; limit: number }
export interface ScanState { pending: ScanWindow[]; incompleteBuckets: number; startedAt: number }
interface TimedEvent { id: string; created_at: number }

/** Inclusive overlapping windows preserve timestamp ties and randomized history.
 * Opaque saturated single-second buckets are reported, never skipped as complete.
 */
export async function scanHistory<T extends TimedEvent>(options: {
  since: number; until: number; state?: ScanState; budget?: number;
  query: (window: ScanWindow) => Promise<T[]>;
  checkpoint: (state: ScanState) => Promise<void>;
  consume?: (events: T[]) => Promise<void>;
  retainEvents?: boolean;
}): Promise<{ events: T[]; state: ScanState; complete: boolean }> {
  const state: ScanState = options.state?.pending.length
    ? structuredClone(options.state)
    : { pending: [{ since: options.since, until: options.until, limit: 256 }], incompleteBuckets: 0, startedAt: options.until };
  const found = new Map<string, T>();
  let retainedBytes = 0;
  for (let step = 0; step < (options.budget ?? 4) && state.pending.length; step++) {
    const window = state.pending[0]!;
    const events = await options.query(window); // failed reads retain the window
    if (options.retainEvents !== false) for (const event of events) {
      if (event.created_at < window.since || event.created_at > window.until || found.has(event.id)) continue;
      retainedBytes += new TextEncoder().encode(JSON.stringify(event)).length;
      if (found.size >= 4096 || retainedBytes > 8 * 1024 * 1024) throw new Error("history collection budget exceeded");
      found.set(event.id, event);
    }
    await options.consume?.(events); // durable effects precede coverage advancement
    state.pending.shift();
    // 64 is the legacy cap encountered by Reader. Conservatively subdivide
    // even when a relay returns fewer than the requested limit.
    if (events.length >= 64) {
      if (window.until > window.since) {
        const middle = Math.floor((window.since + window.until) / 2);
        state.pending.unshift({ since: middle + 1, until: window.until, limit: 256 }, { since: window.since, until: middle, limit: 256 });
      } else if (window.limit < 4096 && events.length >= window.limit) {
        state.pending.unshift({ ...window, limit: Math.min(4096, window.limit * 4) });
      } else {
        state.incompleteBuckets++;
      }
    }
    await options.checkpoint(structuredClone(state));
  }
  return { events: [...found.values()], state, complete: state.pending.length === 0 && state.incompleteBuckets === 0 };
}
