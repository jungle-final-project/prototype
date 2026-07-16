import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const defaultApiProxyTarget = 'http://localhost:8080';

function resolveApiProxyTarget(value: string | undefined) {
  const candidate = value?.trim().replace(/^(['"])(.*)\1$/, '$2');

  if (!candidate) {
    return defaultApiProxyTarget;
  }

  try {
    const url = new URL(candidate);
    return url.protocol === 'http:' || url.protocol === 'https:'
      ? url.origin
      : defaultApiProxyTarget;
  } catch {
    return defaultApiProxyTarget;
  }
}

const apiProxyTarget = resolveApiProxyTarget(process.env.VITE_DEV_PROXY_TARGET);

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
        secure: false,
        rewrite: (path) => path,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin');
          });
        }
      },
      '/ws': {
        target: apiProxyTarget,
        ws: true,
        changeOrigin: true,
        secure: false
      }
    }
  }
});
