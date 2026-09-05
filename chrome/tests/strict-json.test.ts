import { describe, expect, it } from "vitest";
import { parseStrictJson } from "../src/protocol/strict-json.js";

describe("strict JSON", () => {
  it("accepts valid nested JSON", () => {
    expect(parseStrictJson('{"a":[1,true,null,{"b":"x"}]}')).toEqual({ a: [1, true, null, { b: "x" }] });
  });

  it("rejects duplicate keys at every depth, including escaped aliases", () => {
    expect(() => parseStrictJson('{"a":1,"a":2}')).toThrow(/duplicate/);
    expect(() => parseStrictJson('{"outer":{"x":1,"x":2}}')).toThrow(/duplicate/);
    expect(() => parseStrictJson('{"a":1,"\\u0061":2}')).toThrow(/duplicate/);
  });

  it("rejects malformed and trailing JSON", () => {
    expect(() => parseStrictJson('{"a":01}')).toThrow();
    expect(() => parseStrictJson('{"a":1} garbage')).toThrow();
    expect(() => parseStrictJson('"unterminated')).toThrow();
  });
});
