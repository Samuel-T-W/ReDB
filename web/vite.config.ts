/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base is "./" so the static build works from any subpath (GitHub Pages / Vercel).
export default defineConfig({
  plugins: [react()],
  base: "./",
  // Pin the dev server to the port the UI tooling (WebKit MCP, shots.mjs) expects,
  // and fail loudly instead of silently picking another port if it's taken.
  server: { port: 5180, strictPort: true },
  test: {
    // Component smoke tests need a DOM; the data-layer tests are happy either way.
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
