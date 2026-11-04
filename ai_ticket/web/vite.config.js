import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 两个入口，一套依赖：
 *
 *   /            用户端    选咨询类型 → 和 AI 客服对话
 *   /agent/      人工端    批阅工单、配置模板
 *
 * 为什么用多页而不是两个独立工程：
 *   两端共用同一份 api 封装与 DynamicForm / QualityBanner。
 *   拆成两个工程要么复制代码，要么引入 workspace，
 *   对两周项目来说维护成本高于收益。
 *
 * 但「两个端」是真的：两个 html、两套 main、两套布局，
 * 用户端代码里不引用任何工单/模板页面。
 */
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      // 相对 root 的路径即可；不用 __dirname —— 本工程是 ESM（type: module）
      input: {
        user: 'index.html',
        agent: 'agent/index.html'
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 开发期用 proxy 解决跨域，后端不需要配 CORS
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
