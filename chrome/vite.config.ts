import { defineConfig } from "vite";
import { cp, copyFile } from "node:fs/promises";
import { resolve } from "node:path";

const copyExtensionAssets = () => ({
  name: "copy-extension-assets",
  async closeBundle() {
    const output = resolve(import.meta.dirname, "dist");
    await copyFile(resolve(import.meta.dirname, "manifest.json"), resolve(output, "manifest.json"));
    await cp(resolve(import.meta.dirname, "icons"), resolve(output, "icons"), { recursive: true });
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
    modulePreload: false,
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      input: {
        background: resolve(import.meta.dirname, "src/background/service-worker.ts"),
        popup: resolve(import.meta.dirname, "src/ui/popup.html"),
        options: resolve(import.meta.dirname, "src/ui/options.html"),
        pairing: resolve(import.meta.dirname, "src/ui/pairing.html")
      },
      output: { entryFileNames: "[name].js", chunkFileNames: "[name]-[hash].js" }
    },
    target: "es2022",
    minify: false
  }
});
