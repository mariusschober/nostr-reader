import { defineConfig } from "vite";
import { resolve } from "node:path";

/**
 * Chrome content scripts are classic scripts: manifest v3 does not provide a
 * `type: module` switch for them. Build this entry separately so Rollup cannot
 * split shared imports into an ES module that Chrome rejects at injection time.
 */
export default defineConfig({
  esbuild: {
    // Match the main extension build: Chromium rejects these noncharacters in
    // extension scripts even when the file is valid UTF-8.
    charset: "ascii",
  },
  build: {
    modulePreload: false,
    outDir: process.env.READER_DIST ?? "dist",
    emptyOutDir: false,
    target: "es2022",
    minify: false,
    rollupOptions: {
      input: resolve(import.meta.dirname, "src/content/capture.ts"),
      output: {
        format: "iife",
        entryFileNames: "content.js",
      },
    },
  },
});
