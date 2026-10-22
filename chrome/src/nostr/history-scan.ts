export interface ScanWindow { since: number; until: number; limit: number }
export interface ScanState {
  pending: ScanWindow[];
  incompleteBuckets: number;
  /** Single-second buckets a relay would not enumerate; retried at the largest limit. */
  incompleteWindows: ScanWindow[];
  startedAt: number;
}
interface TimedEvent { id: string; created_at: number }

/** Conservative threshold for relays that silently cap below the requested limit. */
const DEFAULT_SATURATION_CAP = 50;
const MAX_LIMIT = 4096;

function sameBucket(left: ScanWindow, right: ScanWindow): boolean {
  return left.since === right.since && left.until === right.until;
}

/** Inclusive overlapping windows preserve timestamp ties and randomized history.
 * Opaque saturated single-second buckets are reported and retried, never skipped as complete.
 */
export async function scanHistory<T extends TimedEvent>(options: {
  since: number; until: number; state?: ScanState; budget?: number; cap?: number;
  query: (window: ScanWindow) => Promise<T[]>;
  checkpoint: (state: ScanState) => Promise<void>;
  consume?: (events: T[]) => Promise<void>;
  retainEvents?: boolean;
}): Promise<{ events: T[]; state: ScanState; complete: boolean }> {
  const previous = options.state;
  const previousPending = previous?.pending.length ? structuredClone(previous.pending) : [];
  const state: ScanState = previousPending.length
    ? {
      pending: previousPending,
      // Normalize legacy persisted state, which only carried a count.
      incompleteWindows: structuredClone(previous?.incompleteWindows ?? []),
      incompleteBuckets: previous?.incompleteWindows?.length ?? 0,
      startedAt: previous?.startedAt ?? options.until,
    }
    : {
      pending: [{ since: options.since, until: options.until, limit: 256 }],
      incompleteWindows: structuredClone(previous?.incompleteWindows ?? []),
      incompleteBuckets: previous?.incompleteWindows?.length ?? 0,
      startedAt: previous?.startedAt ?? options.until,
    };
  // Retry buckets that a relay could not enumerate earlier in this rolling window.
  for (const window of state.incompleteWindows) {
    const retryLimit = Math.max(window.limit, MAX_LIMIT);
    const existing = state.pending.findIndex(pending => sameBucket(pending, window));
    if (existing >= 0) {
      state.pending[existing] = { ...state.pending[existing]!, limit: Math.max(state.pending[existing]!.limit, retryLimit) };
    } else {
      state.pending.push({ ...window, limit: retryLimit });
    }
  }
  const saturationCap = Math.max(1, options.cap ?? DEFAULT_SATURATION_CAP);
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
    // A relay may cap below the requested limit (for example 50 << 256). Treat
    // any response at or above the conservative cap as saturated so the window
    // is subdivided/escalated instead of silently accepted as complete.
    const saturated = events.length >= Math.min(window.limit, saturationCap);
    if (saturated) {
      if (window.until > window.since) {
        const middle = Math.floor((window.since + window.until) / 2);
        state.pending.unshift({ since: middle + 1, until: window.until, limit: 256 }, { since: window.since, until: middle, limit: 256 });
      } else if (window.limit < MAX_LIMIT) {
        state.pending.unshift({ ...window, limit: Math.min(MAX_LIMIT, Math.max(512, window.limit * 4)) });
      } else if (!state.incompleteWindows.some(candidate => sameBucket(candidate, window))) {
        state.incompleteWindows.push({ ...window, limit: MAX_LIMIT });
      }
    } else {
      // A later successful enumeration clears the degraded marker for this bucket.
      state.incompleteWindows = state.incompleteWindows.filter(candidate => !sameBucket(candidate, window));
    }
    state.incompleteBuckets = state.incompleteWindows.length;
    await options.checkpoint(structuredClone(state));
  }
  return {
    events: [...found.values()],
    state,
    complete: state.pending.length === 0 && state.incompleteWindows.length === 0,
  };
}
