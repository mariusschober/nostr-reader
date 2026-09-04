import { defineConfig } from "vite";
import { cp, copyFile } from "node:fs/promises";
import { resolve } from "node:path";

const copyExtensionAssets = () => ({
  name: "copy-extension-assets",
  async closeBundle() {
    const output = resolve(__dirname, "dist");
    await copyFile(resolve(__dirname, "manifest.json"), resolve(output, "manifest.json"));
    await cp(resolve(__dirname, "icons"), resolve(output, "icons"), { recursive: true });
  }
});

export default defineConfig({
  base: "./",
  esbuild: {
    // Chromium rejects Unicode noncharacters such as U+FFFF in extension
    // scripts, even when their byte sequences are valid UTF-8.
    charset: "ascii"
  },
  plugins: [copyExtensionAssets()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      input: {
        background: resolve(__dirname, "src/background/service-worker.ts"),
        content: resolve(__dirname, "src/content/capture.ts"),
        popup: resolve(__dirname, "src/ui/popup.html"),
        options: resolve(__dirname, "src/ui/options.html"),
        pairing: resolve(__dirname, "src/ui/pairing.html"),
        bridge: resolve(__dirname, "src/signer/bridge-main.ts")
      },
      output: { entryFileNames: "[name].js", chunkFileNames: "[name]-[hash].js" }
    },
    target: "es2022",
    minify: false
  }
});
