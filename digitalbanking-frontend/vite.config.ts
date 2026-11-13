import { defineConfig } from "vitest/config";
import { loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const mock = env.VITE_ENABLE_MOCKS === "true";
  if (mode === "production" && mock)
    throw new Error("Production build cannot enable mocks");
  return {
    plugins: [react()],
    server: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: mock
        ? undefined
        : {
            "/api": {
              target: process.env.API_PROXY_TARGET || "http://localhost:8080",
              changeOrigin: true,
            },
          },
    },
    test: { environment: "node", clearMocks: true, testTimeout: 20000 },
  };
});
