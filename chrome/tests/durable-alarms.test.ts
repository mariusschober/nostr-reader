import { describe, expect, it } from "vitest";
import { DurableAlarms, type AlarmPort } from "../src/background/durable-alarms.js";

function fixture() {
  let now = 1_000_000;
  const alarms = new Map<string, { scheduledTime: number; periodInMinutes?: number }>();
  const creates: number[] = [];
  const port: AlarmPort = {
    get: async name => alarms.get(name),
    clear: async name => alarms.delete(name),
    create: async (name, info) => { creates.push(info.when); alarms.set(name, { scheduledTime: info.when }); },
  };
  return { alarms, creates, owner: () => new DurableAlarms(port, () => now), advance: (ms: number) => { now += ms; } };
}

describe("durable alarm orchestration", () => {
  it("preserves a persisted retry through 20 worker evaluations and unrelated wakes", async () => {
    const f = fixture();
    const due = 1_900_000;
    for (let i = 0; i < 20; i++) {
      await f.owner().reconcile("retry", async () => due);
      f.advance(60_000);
    }
    expect(f.creates).toEqual([due]);
    expect(f.alarms.get("retry")?.scheduledTime).toBe(due);
  });
  it("restores a missing alarm after restart and moves an alarm earlier for new work", async () => {
    const f = fixture();
    await f.owner().reconcile("retry", async () => 1_900_000);
    await f.owner().reconcile("retry", async () => 1_100_000);
    expect(f.creates).toEqual([1_900_000, 1_100_000]);
    f.alarms.clear();
    await f.owner().reconcile("retry", async () => 1_100_000);
    expect(f.creates).toEqual([1_900_000, 1_100_000, 1_100_000]);
  });
  it("removes pairing polling when durable sessions are terminal", async () => {
    const f = fixture();
    await f.owner().reconcile("pair", async () => 1_100_000);
    await f.owner().reconcile("pair", async () => undefined);
    expect(f.alarms.size).toBe(0);
  });
  it("serializes competing reconciliations and reads current durable state", async () => {
    const f = fixture();
    const owner = f.owner();
    await Promise.all([owner.reconcile("retry", async () => 1_100_000), owner.reconcile("retry", async () => 1_900_000)]);
    expect(f.creates).toEqual([1_100_000]);
  });
  it("converts a legacy repeating alarm without postponing its earlier wake", async () => {
    const f = fixture();
    f.alarms.set("retry", { scheduledTime: 1_050_000, periodInMinutes: 15 });
    await f.owner().reconcile("retry", async () => 1_900_000);
    expect(f.alarms.get("retry")).toEqual({ scheduledTime: 1_050_000 });
  });
  it("does not erase a valid alarm on a durable-store read failure", async () => {
    const f = fixture();
    f.alarms.set("retry", { scheduledTime: 1_050_000 });
    await expect(f.owner().reconcile("retry", async () => { throw Error("storage unavailable"); })).rejects.toThrow();
    expect(f.alarms.size).toBe(1);
  });
});
