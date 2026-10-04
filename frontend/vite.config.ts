import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080"
    }
  },
  build: {
    rollupOptions: {
      output: {
        // Long-lived vendor chunks: a release that changes only the app does not re-download MUI.
        manualChunks(id) {
          if (!id.includes("node_modules")) return undefined;
          if (id.includes("/@stripe/")) return "stripe";
          if (/\/(@mui|@emotion|@popperjs|react-transition-group|stylis|hoist-non-react-statics)\//.test(id)) return "mui";
          return "vendor";
        }
      }
    }
  }
});
