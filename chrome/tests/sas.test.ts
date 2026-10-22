import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { pairingSas, SAS_WORDS } from "../src/protocol/sas.js";

const vector = JSON.parse(readFileSync(join(process.cwd(), "..", "shared", "test-vectors", "pairing-v2.json"), "utf8"));

describe("pairing short authentication string", () => {
  it("matches the cross-runtime vector", async () => {
    expect(await pairingSas(vector.request)).toBe(vector.sas);
  });

  it("is deterministic and sensitive to every transcript field", async () => {
    const base = vector.request;
    expect(await pairingSas(base)).toBe(await pairingSas(base));
    const replacements = {
      sessionId: "aa".repeat(16),
      chromeDevicePubkey: "bb".repeat(32),
      pairingPubkey: "cc".repeat(32),
      nonce: "dd".repeat(32),
    } as const;
    for (const field of Object.keys(replacements) as Array<keyof typeof replacements>) {
      expect(await pairingSas({ ...base, [field]: replacements[field] })).not.toBe(vector.sas);
    }
  });

  it("uses six words from the fixed public list", async () => {
    const words = (await pairingSas(vector.request)).split(" ");
    expect(words).toHaveLength(6);
    expect(words.every(word => (SAS_WORDS as readonly string[]).includes(word))).toBe(true);
  });
});
