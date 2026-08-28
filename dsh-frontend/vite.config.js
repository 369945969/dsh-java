import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 对接 Java 后端（Spring Boot，默认 8765 端口）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8765',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
});
