import { describe, expect, it } from "vitest";
import { KeyedSerialExecutor, SerialExecutor } from "../src/protocol/serial-executor.js";

describe("durable-state operation serialization", () => {
  it("orders same-transfer publish, ACK deletion, and a later retry", async () => {
    const executor = new KeyedSerialExecutor<string>();
    const mutations: string[] = [];
    let present = true;
    let releasePublish!: () => void;
    const publishGate = new Promise<void>((resolve) => { releasePublish = resolve; });

    const publish = executor.run("transfer", async () => {
      mutations.push("publish-start");
      await publishGate;
      if (present) mutations.push("publish-put");
    });
    const ack = executor.run("transfer", () => {
      present = false;
      mutations.push("ack-delete");
    });
    const retry = executor.run("transfer", () => {
      if (present) mutations.push("retry-put");
      else mutations.push("retry-skipped");
    });

    await Promise.resolve();
    expect(mutations).toEqual(["publish-start"]);
    releasePublish();
    await Promise.all([publish, ack, retry]);
    expect(mutations).toEqual(["publish-start", "publish-put", "ack-delete", "retry-skipped"]);
    expect(present).toBe(false);
  });

  it("does not let a failed operation poison later work", async () => {
    const executor = new SerialExecutor();
    await expect(executor.run(() => { throw new Error("expected"); })).rejects.toThrow("expected");
    await expect(executor.run(() => 42)).resolves.toBe(42);
  });

  it("allows independent transfers to make progress concurrently", async () => {
    const executor = new KeyedSerialExecutor<string>();
    let secondRan = false;
    let releaseFirst!: () => void;
    const gate = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const first = executor.run("first", () => gate);
    const second = executor.run("second", () => { secondRan = true; });
    await second;
    expect(secondRan).toBe(true);
    releaseFirst();
    await first;
  });
});
