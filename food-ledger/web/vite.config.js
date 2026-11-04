import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 两个入口，一套依赖：
 *
 *   /          采购录入端   一句话录入、我的记录、待补录
 *   /admin/    台账管理端   台账列表、入账/退回/作废、供应商、统计
 *
 * 分开的理由和业务有关：采购员只该看「我今天录了什么」，
 * 管理员才需要面对全量台账和审核动作。合成一个界面两拨人都别扭。
 */
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      input: {
        user: 'index.html',
        admin: 'admin/index.html'
      }
    }
  },
  server: {
    port: 5174,
    proxy: {
      // 开发期用 proxy 解决跨域，后端不需要配 CORS。
      // 注意是 8081：8080 上跑的是仓库根目录那个旧的 AI 客服工程。
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
      /*
       * 附件也必须代理。
       * 不代理的话，<img src="/uploads/..."> 会被 vite 当成前端路由，
       * 回一个 index.html 给图片标签 —— 表现就是「图片加载不出来」，
       * 可偏偏直接访问后端 8081 的同一个 URL 又是好的，很难查。
       */
      '/uploads': { target: 'http://localhost:8081', changeOrigin: true }
    }
  }
})