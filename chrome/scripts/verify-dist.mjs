import { readFile, readdir } from "node:fs/promises";
import { resolve } from "node:path";

const dist = resolve(import.meta.dirname, "..", process.env.READER_DIST ?? "dist");
const manifest = JSON.parse(await readFile(resolve(dist, "manifest.json"), "utf8"));
if (manifest.background?.service_worker !== "background.js") {
  throw new Error("built manifest does not reference background.js");
}
if (!manifest.optional_host_permissions?.length || manifest.content_scripts?.length) {
  throw new Error("provider access must be optional rather than a static content script grant");
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
