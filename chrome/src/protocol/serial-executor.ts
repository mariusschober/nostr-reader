/**
 * Promise-based serial executors for MV3 code. They do not keep a worker alive;
 * they only prevent overlapping handlers inside one worker evaluation from
 * racing durable state writes.
 */
export class SerialExecutor {
  private tail: Promise<void> = Promise.resolve();

  run<T>(operation: () => Promise<T> | T): Promise<T> {
    const result = this.tail.then(operation);
    this.tail = result.then(() => undefined, () => undefined);
    return result;
  }
}

export class KeyedSerialExecutor<Key> {
  private readonly tails = new Map<Key, Promise<void>>();

  run<T>(key: Key, operation: () => Promise<T> | T): Promise<T> {
    const previous = this.tails.get(key) ?? Promise.resolve();
    const result = previous.then(operation);
    const tail = result.then(() => undefined, () => undefined);
    this.tails.set(key, tail);
    void tail.then(() => {
      if (this.tails.get(key) === tail) this.tails.delete(key);
    });
    return result;
  }
}
