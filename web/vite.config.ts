import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The dev server proxies the API so the browser sees one origin; SSE needs the proxy to
// keep the connection unbuffered, which is why `configure` disables compression on /api.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.WILLCALL_API_ORIGIN ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  preview: { port: 4173, strictPort: true },
  build: {
    target: 'es2022',
    manifest: true,
    sourcemap: true,
    // Route-level budgets are enforced in CI from the manifest; see scripts/check-bundle-budget.mjs.
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          query: ['@tanstack/react-query'],
        },
      },
    },
  },
})
