import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Prudence UI (govern vertex). Proxies /api to the Spring Boot backend on :8099 in dev.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5200,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:8099' },
    fs: { allow: ['..'] }, // import the shared design tokens from ../design
  },
});
