import { defineConfig } from "vite";
import { resolve } from "node:path";

export default defineConfig({
  base: "./",
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
