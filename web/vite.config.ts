import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    // Built into the core service's static resources, so the whole thing is
    // served from one origin on one port. No CORS to configure, no second
    // process to run, and the token never crosses an origin boundary.
    outDir: "../core/src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    // Only for `npm run dev`, where Vite serves the app and forwards API calls
    // to the running service.
    proxy: {
      "/v1": "http://localhost:8080",
    },
  },
});
