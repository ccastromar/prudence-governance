import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Prudence UI (govern vertex). Proxies /api to the Spring Boot backend in dev: :8099 unless
// PRUDENCE_BACKEND_URL points elsewhere (e.g. the conformance stack on :18099).
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5200,
    strictPort: true,
    proxy: { '/api': process.env.PRUDENCE_BACKEND_URL || 'http://127.0.0.1:8099' },
  },
});
