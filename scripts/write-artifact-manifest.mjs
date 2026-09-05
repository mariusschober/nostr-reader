import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const git = (...args) => execFileSync("git", ["-C", repo, ...args], { encoding: "utf8" }).trim();
const dirty = git("status", "--porcelain", "--untracked-files=no");
if (dirty) throw new Error("Refusing manifest generation: tracked worktree is dirty");

const definitions = [
  {
    path: join(repo, "artifacts", "reader-debug.apk"),
    buildCommand: "cd android && ./gradlew clean test lint assembleDebug assembleDebugAndroidTest",
    installTest: process.env.READER_ANDROID_APK_RESULT ?? "NOT MEASURED: build completed; physical install result not supplied",
  },
  {
    path: join(repo, "artifacts", "reader-debug-androidTest.apk"),
    buildCommand: "cd android && ./gradlew clean test lint assembleDebug assembleDebugAndroidTest",
    installTest: process.env.READER_ANDROID_INSTRUMENTATION_RESULT ?? "NOT MEASURED: build completed; physical instrumentation result not supplied",
  },
  {
    path: join(repo, "artifacts", "reader-chrome-extension.zip"),
    buildCommand: "cd chrome && npm ci && npm run typecheck && npm test && npm run build; zip -X dist contents",
    installTest: process.env.READER_CHROME_RESULT ?? "NOT MEASURED: build and ZIP integrity completed; installed-browser result not supplied",
  },
  {
    path: join(repo, "artifacts", "chrome-sbom.cdx.json"),
    buildCommand: "cd chrome && npm sbom --package-lock-only --sbom-format cyclonedx --sbom-type application",
    installTest: "PASS: generated and JSON-parsed by npm",
  },
  {
    path: join(repo, "artifacts", "android-debug-runtime-dependencies.txt"),
    buildCommand: "cd android && ./gradlew -q app:dependencies --configuration debugRuntimeClasspath",
    installTest: "PASS: generated dependency inventory",
  },
];

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

const sourceCommit = git("rev-parse", "HEAD");
const artifacts = definitions.map((item) => ({
  filename: relative(repo, item.path),
  sourceCommit,
  buildCommand: item.buildCommand,
  sha256: sha256(item.path),
  installTest: item.installTest,
  path: item.path,
}));

const manifest = {
  schema: "reader-audit-artifacts/1",
  generatedAtUtc: new Date().toISOString(),
  sourceCommit,
  trackedWorktreeClean: true,
  productionSigningMaterial: false,
  artifacts,
};

writeFileSync(join(repo, "artifacts", "ARTIFACTS.json"), `${JSON.stringify(manifest, null, 2)}\n`);
writeFileSync(
  join(repo, "artifacts", "SHA256SUMS"),
  `${artifacts.map((item) => `${item.sha256}  ${item.filename}`).join("\n")}\n`,
);

console.log(JSON.stringify({sourceCommit, artifacts: artifacts.map(({filename, sha256}) => ({filename, sha256}))}, null, 2));
