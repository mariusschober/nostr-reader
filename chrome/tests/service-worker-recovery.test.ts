import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const source = readFileSync(
  join(process.cwd(), "src", "background", "service-worker.ts"),
  "utf8",
);

describe("durable delivery recovery ordering", () => {
  it("reasserts trusted-context-only key storage on every worker evaluation", () => {
    expect(source).toContain('setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" })');
    const firstCall = source.indexOf("void lockDownKeyStorage().catch");
    const wiring = source.indexOf("// ---- Wiring ----");
    expect(firstCall).toBeGreaterThanOrEqual(0);
    expect(firstCall).toBeLessThan(wiring);
  });

  it("attempts every payload before treating a quorum miss as retryable", () => {
    const publish = source.slice(
      source.indexOf("async function publishTransfer"),
      source.indexOf("// ---- E2E ACK catch-up"),
    );
    const loopStart = publish.indexOf("for (const payload of payloads)");
    const loopEnd = publish.indexOf("if (complete < payloads.length)");
    expect(loopStart).toBeGreaterThanOrEqual(0);
    expect(loopEnd).toBeGreaterThan(loopStart);
    expect(publish.slice(loopStart, loopEnd)).not.toContain("throw new Error");
  });

  it("polls for an authenticated ACK after partial relay acceptance", () => {
    const partial = source.slice(
      source.indexOf('item.status = anyAccepted || complete > 0 ? "relay_accepted" : "queued"'),
      source.indexOf("// ---- E2E ACK catch-up"),
    );
    expect(partial).toMatch(/await outboxPut\(item\);[\s\S]*void pollForAcks\(\)[\s\S]*throw error;/);
  });

  it("schedules a worker-safe follow-up ACK check after every publish outcome", () => {
    const publish = source.slice(
      source.indexOf("async function publishTransfer"),
      source.indexOf("// ---- E2E ACK catch-up"),
    );
    expect(source).toContain('const ACK_ALARM = "reader-ack-check"');
    expect(source).toContain("chrome.alarms.create(ACK_ALARM, { when: Date.now() + 30_000 })");
    expect(publish.match(/scheduleAckCheck\(\)/g)).toHaveLength(2);
    expect(source).toMatch(/if \(a\.name === ACK_ALARM\) \{[\s\S]*await pollForAcks\(\)[\s\S]*return;/);
  });

  it("schedules a delayed ACK catch-up after a browser restart", () => {
    const startup = source.slice(
      source.indexOf("chrome.runtime.onStartup.addListener"),
      source.indexOf("chrome.contextMenus.onClicked.addListener"),
    );
    expect(startup).toContain("void pollForAcks()");
    expect(startup).toContain("scheduleAckCheck()");
    expect(startup).not.toContain("publishTransfer");
  });

  it("checks ACKs before scheduled retransmission", () => {
    const alarm = source.slice(source.indexOf("if (a.name !== RETRY_ALARM) return;"));
    expect(alarm.indexOf("await pollForAcks()")).toBeGreaterThanOrEqual(0);
    expect(alarm.indexOf("await pollForAcks()")).toBeLessThan(alarm.indexOf("const items = await outboxAll"));
    expect(alarm.indexOf("const items = await outboxAll")).toBeLessThan(alarm.indexOf("await Promise.all"));
  });

  it("checks ACKs before a user-requested retry", () => {
    const retry = source.slice(
      source.indexOf('msg?.kind === "reader-retry"'),
      source.indexOf('msg?.kind === "reader-retry"') + 1200,
    );
    expect(retry.indexOf("await pollForAcks()")).toBeGreaterThanOrEqual(0);
    expect(retry.indexOf("await pollForAcks()")).toBeLessThan(retry.indexOf("const items = await outboxAll"));
    expect(retry).toContain("await Promise.all");
  });

  it("offers a read-only ACK refresh that does not republish payloads", () => {
    const check = source.slice(
      source.indexOf('msg?.kind === "reader-check-acks"'),
      source.indexOf('msg?.kind === "reader-retry"'),
    );
    expect(check).toContain("await pollForAcks()");
    expect(check).not.toContain("publishTransfer");
  });

  it("disconnects through the secret-stripping state helper and exact channel keys", () => {
    const disconnect = source.slice(
      source.indexOf('msg?.kind === "reader-disconnect"'),
      source.indexOf('msg?.kind === "reader-retry"'),
    );
    expect(disconnect).toContain("cancelActivePairingSessions");
    expect(disconnect).toContain("PAIRED_CHANNEL_STORAGE_KEYS");
    expect(disconnect).not.toContain("deviceSeckey");
    expect(disconnect).not.toContain("customRelays");
  });

  it("never deletes captured payload merely because a retry ceiling was reached", () => {
    const publish = source.slice(
      source.indexOf("async function publishTransfer"),
      source.indexOf("// ---- E2E ACK catch-up"),
    );
    expect(publish).toContain("deliveryCeilingReason");
    expect(publish).not.toContain("item.chunks = []");
    expect(publish).not.toContain('item.title = ""');
    expect(source).toMatch(/recordDelivered\(item\.transferId, now\)[\s\S]*outboxDelete\(item\.transferId\)/);
  });
});
