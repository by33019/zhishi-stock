import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  cacheDir: '.cache/vite',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 本地开发代理：把 /api 转发到后端，使 `npm run dev` 也能取到真实数据。
    // 之所以不用 CORS：代理是服务端转发，浏览器视角下前后端同源，天然无跨域问题。
    // 容器部署下由 nginx 承担同样的转发职责（见 frontend/nginx.conf），两者行为一致。
    // 注意后端接口路径本身就含 /api 前缀，故无需 rewrite。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // ECharts is isolated in a route-lazy chunk; keep the budget explicit instead of warning at 500 kB.
    chunkSizeWarningLimit: 600,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
})
