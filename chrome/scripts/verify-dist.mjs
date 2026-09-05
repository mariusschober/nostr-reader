import { readFile, readdir } from "node:fs/promises";
import { resolve } from "node:path";

const dist = resolve(import.meta.dirname, "../dist");
const manifest = JSON.parse(await readFile(resolve(dist, "manifest.json"), "utf8"));
if (manifest.background?.service_worker !== "background.js") {
  throw new Error("built manifest does not reference background.js");
}
if (!manifest.content_scripts?.some((entry) => entry.js?.includes("content.js"))) {
  throw new Error("built manifest does not reference content.js");
}

const content = await readFile(resolve(dist, "content.js"), "utf8");
if (/^\s*(?:import|export)\s/m.test(content)) {
  throw new Error("content.js contains ES-module syntax; Chrome content scripts must be classic scripts");
}

for (const name of await readdir(dist)) {
  if (!name.endsWith(".js")) continue;
  const source = await readFile(resolve(dist, name), "utf8");
  if (/\uFFFF|\uFFFE/.test(source)) {
    throw new Error(`${name} contains a Unicode noncharacter rejected by Chromium`);
  }
}

console.log("Verified Chrome extension package: standalone content script, manifest assets, Chromium-safe text.");
