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
  // 开发服务器：npm run dev（5173）
  // baseURL 改成相对路径 /api 之后，dev 模式也必须把 /api 代理到后端，
  // 否则请求会打到 5173 自己身上变成 404。
  // 不要加 rewrite —— 后端 context-path 就是 /api，原样转发即可。
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8567',
        changeOrigin: false,
      },
    },
  },
  // 预览生产产物：npm run build && npm run preview（4173）
  // 浏览器看到的是 4173 这一个源，与线上 Nginx 行为一致。
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
