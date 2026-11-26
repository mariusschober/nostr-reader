// Run the packaged extension in a disposable, unpaired Chrome for Testing profile.
import { execFile, spawn } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { promisify } from "node:util";

if (!process.env.CHROME_BIN) throw new Error("Set CHROME_BIN to the Chrome for Testing executable.");
const extension = resolve(import.meta.dirname, "../dist");
const profile = await mkdtemp(join(tmpdir(), "reader-qa-"));
const browser = spawn(process.env.CHROME_BIN, [
  "--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--no-first-run",
  "--disable-background-networking", "--disable-sync", "--remote-debugging-port=0",
  "--user-data-dir=" + profile, "--disable-extensions-except=" + extension, "--load-extension=" + extension, "about:blank",
], { stdio: "ignore" });
let stopped = false;
const finished = new Promise(resolveExit => {
  browser.once("exit", () => { stopped = true; resolveExit(); });
  browser.once("error", () => { stopped = true; resolveExit(); });
});

async function waitFor(label, read) {
  for (let attempt = 0; attempt < 150; attempt++) {
    if (stopped) throw new Error("Chrome exited while waiting for " + label);
    const value = await read();
    if (value) return value;
    await sleep(200);
  }
  throw new Error("Timed out waiting for " + label);
}

try {
  const portFile = await waitFor("DevTools", () => readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""));
  const port = portFile.split("\n")[0];
  const endpoint = "http://127.0.0.1:" + port;
  async function targets() { return (await fetch(endpoint + "/json/list")).json(); }
  const worker = await waitFor("Reader installation", async () => (await targets()).find(
    target => target.type === "service_worker" && target.url.endsWith("/background.js"),
  ));
  const settings = new URL("src/ui/options.html", worker.url).href;
  const opened = await fetch(endpoint + "/json/new?" + encodeURIComponent(settings), { method: "PUT" });
  if (!opened.ok) throw new Error("Could not open Reader settings");
  await waitFor("Reader settings", async () => (await targets()).some(target => target.url === settings));
  const { stdout } = await promisify(execFile)(process.execPath, [join(import.meta.dirname, "cdp-installed-check.mjs"), port, "outbox-smoke"], { timeout: 45_000 });
  process.stdout.write(stdout);
} finally {
  browser.kill("SIGTERM");
  const killTimer = setTimeout(() => browser.kill("SIGKILL"), 5000);
  await finished;
  clearTimeout(killTimer);
  await rm(profile, { recursive: true, force: true, maxRetries: 3 });
}
