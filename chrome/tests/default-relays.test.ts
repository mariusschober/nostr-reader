import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  configuredRelays,
  DEFAULT_RELAYS,
  normalizeCustomRelays,
  pairingReadRelays,
  RELAY_WRITE_QUORUM,
  sameRelayOrder,
  validatePairedRelaySet,
} from "../src/protocol/relays.js";

describe("cross-platform default relay contract", () => {
  const contract = JSON.parse(readFileSync(
    join(process.cwd(), "..", "shared", "default-relays.json"),
    "utf8",
  )) as { version: number; writeQuorum: number; relays: string[] };
  const androidSource = readFileSync(
    join(process.cwd(), "..", "android", "app", "src", "main", "java", "com", "reader", "app", "nostr", "PairingProtocol.kt"),
    "utf8",
  );
  const androidBlock = androidSource.match(/val READER_DEFAULT_RELAYS = listOf\(([\s\S]*?)\n\)/)?.[1] ?? "";
  const androidRelays = [...androidBlock.matchAll(/"(wss:\/\/[^"\s]+)"/g)].map((match) => match[1]);
  const androidQuorum = Number(androidSource.match(/const val READER_RELAY_WRITE_QUORUM = (\d+)/)?.[1]);

  it("uses six distinct secure public relay URLs", () => {
    expect(contract.version).toBe(1);
    expect(contract.relays).toHaveLength(6);
    expect(new Set(contract.relays).size).toBe(6);
    expect(contract.relays.every((relay) => relay.startsWith("wss://"))).toBe(true);
  });

  it("keeps Chrome and Android identical to the shared contract", () => {
    expect([...DEFAULT_RELAYS]).toEqual(contract.relays);
    expect(androidRelays).toEqual(contract.relays);
    expect(RELAY_WRITE_QUORUM).toBe(contract.writeQuorum);
    expect(androidQuorum).toBe(contract.writeQuorum);
  });

  it("normalizes at most two custom secure relays without duplicating defaults", () => {
    expect(normalizeCustomRelays([
      "wss://CUSTOM.example/",
      "wss://custom.example",
      DEFAULT_RELAYS[0],
      "wss://second.example/path/",
    ])).toEqual(["wss://custom.example", "wss://second.example/path"]);
    expect(() => normalizeCustomRelays(["ws://insecure.example"])).toThrow(/must use wss/);
    expect(() => normalizeCustomRelays([
      "wss://one.example", "wss://two.example", "wss://three.example",
    ])).toThrow(/up to 2/);
  });

  it("appends custom relays without mutating the defaults and detects re-pairing", () => {
    const custom = ["wss://custom.example"];
    const next = configuredRelays(custom);
    expect(next).toEqual([...DEFAULT_RELAYS, ...custom]);
    expect(sameRelayOrder(next, [...next])).toBe(true);
    expect(sameRelayOrder(next, [...DEFAULT_RELAYS])).toBe(false);
  });

  it("fails closed on missing, reordered, or non-canonical durable relay state", () => {
    const valid = [...DEFAULT_RELAYS, "wss://custom.example"];
    expect(validatePairedRelaySet(valid)).toEqual(valid);
    expect(() => validatePairedRelaySet([])).toThrow(/invalid size/);
    expect(() => validatePairedRelaySet([...DEFAULT_RELAYS].reverse())).toThrow(/authenticated channel/);
    expect(() => validatePairedRelaySet([...DEFAULT_RELAYS, "wss://CUSTOM.example/"])).toThrow(/authenticated channel/);
    expect(() => validatePairedRelaySet([...DEFAULT_RELAYS, "wss://127.0.0.1"])).toThrow();
  });

  it("can recover both pairing messages from owner-configured custom relays when defaults fail", () => {
    const bound = [...DEFAULT_RELAYS, "wss://custom.example"];
    expect(pairingReadRelays("bootstrap_response", bound)).toEqual(bound);
    expect(pairingReadRelays("authenticated_completion", bound)).toEqual(bound);
  });
});
