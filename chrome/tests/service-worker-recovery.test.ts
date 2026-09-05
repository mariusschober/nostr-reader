// Supplementary source architecture checks only. Runtime recovery is tested in
// durable-alarms, resumable-publisher, history-scan and real packaged browser QA.
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
const source = readFileSync(new URL("../src/background/service-worker.ts", import.meta.url), "utf8");
describe("supplementary worker boundary checks", () => {
  it("reasserts key isolation on evaluation", () => {
    expect(source).toContain('setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" })');
    expect(source).toContain("void lockDownKeyStorage().catch");
  });
  it("uses short guarded progress commits and durable receipt before cleanup", () => {
    expect(source).toContain("checkpoint: (index, relay, outcome) => transferOperations.run");
    expect(source.indexOf("await recordDelivered(item.transferId, now)")).toBeLessThan(source.indexOf("await outboxDelete(item.transferId); // Only"));
    expect(source).toContain("if (!await current()) return false;");
  });
  it("validates identity and limits before publication", () => {
    expect(source).toContain("await bindOutboxItem(durable, channelPubkey, getPublicKey(seckey))");
    expect(source).toContain("deliveryCeilingReason(durable, now)");
    expect(source).toContain("if (!trustedPage");
  });
  it("executes due work at startup and restores alarms at evaluation", () => {
    expect(source).toContain("void resumeDueTransfers().catch");
    expect(source).toContain("void restoreWorkAlarms().catch");
    expect(source).not.toContain("periodInMinutes: 15");
  });
});
