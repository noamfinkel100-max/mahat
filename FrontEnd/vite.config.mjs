import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tsconfigPaths from 'vite-tsconfig-paths';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react(), tsconfigPaths()],
    server: {
      host: '0.0.0.0',
      proxy: {
        '/api': { target: env.API_PROXY_TARGET || 'http://127.0.0.1:12000', changeOrigin: false },
      },
    },
    test: {
      include: ['src/**/*.test.{ts,tsx}'],
      globals: true,
      environment: 'jsdom',
      setupFiles: './vitest.setup.mjs',
      exclude: ['**/node_modules/**', '**/e2e/**', '**/dist/**'],
    },
  };
});
