import { SerialExecutor } from "../protocol/serial-executor.js";

export interface AlarmPort {
  get(name: string): Promise<{ scheduledTime: number; periodInMinutes?: number } | undefined>;
  create(name: string, info: { when: number }): Promise<void> | void;
  clear(name: string): Promise<boolean>;
}

/** One owner per worker. Durable work, not alarm creation time, owns deadlines. */
export class DurableAlarms {
  private readonly operations = new SerialExecutor();
  constructor(private readonly port: AlarmPort, private readonly now = Date.now) {}

  reconcile(name: string, readDue: () => Promise<number | undefined>): Promise<void> {
    return this.operations.run(async () => {
      const due = await readDue();
      const alarm = await this.port.get(name);
      if (due === undefined) {
        if (alarm) await this.port.clear(name);
        return;
      }
      if (!Number.isFinite(due)) throw new Error("Invalid durable alarm deadline");
      // Chrome may deliver late. Do not keep replacing a past-due wake either.
      const when = Math.max(this.now() + 1000, due);
      if (!alarm || alarm.scheduledTime > when || alarm.periodInMinutes !== undefined) {
        await this.port.create(name, { when: alarm ? Math.min(alarm.scheduledTime, when) : when });
      }
    });
  }
}
