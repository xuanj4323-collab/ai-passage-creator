import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  // 仅用于本机验证生产产物：npm run build && npm run preview
  // 把 /api 反代到后端，浏览器看到的是 4173 这一个源，与线上 Nginx 行为一致。
  // 不要加 rewrite —— 后端 context-path 就是 /api，原样转发即可。
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8567',
        changeOrigin: false,
      },
    },
  },
})
