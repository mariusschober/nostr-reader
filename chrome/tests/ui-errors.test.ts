import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const read = (path: string) => readFileSync(join(process.cwd(), "src", "ui", path), "utf8");

describe("extension surface error states", () => {
  it("popup builds status with textContent and offers a retry error path", () => {
    const popup = read("popup.html");
    expect(popup).not.toContain("innerHTML");
    expect(popup).toContain("Could not read Reader status");
    expect(popup).toContain("Retry failed");
  });

  it("pairing page catches worker rejection and reveals restart", () => {
    const pairing = read("pairing.html");
    expect(pairing).toContain("Could not reach the Reader worker");
    expect(pairing).toContain("restart.hidden = false");
    expect(pairing).not.toContain("innerHTML");
  });
});
