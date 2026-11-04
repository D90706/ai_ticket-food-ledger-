import { createRouter, createWebHashHistory } from 'vue-router'
import { ensureAgent } from '../authStore'

/**
 * 人工端路由。用 hash 模式：部署在 /agent/ 子路径下也不用改服务端配置。
 *
 * 三个业务模块 + 一个登录页：
 *   工单 —— 人工的主战场
 *   模板 —— 配置字段与决策项（谁定义字段，谁负责这套业务）
 *   登录 —— 客服是内部员工，账号由系统预置，不开放注册
 */
const routes = [
  { path: '/', redirect: '/tickets' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true }
  },
  { path: '/tickets', name: 'tickets', component: () => import('../views/TicketListView.vue') },
  { path: '/tickets/:id', name: 'ticket', component: () => import('../views/TicketDetailView.vue') },
  { path: '/templates', name: 'templates', component: () => import('../views/TemplateListView.vue') },
  { path: '/templates/:id', name: 'template', component: () => import('../views/TemplateEditorView.vue') }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

/**
 * 守卫只做一件事：没登录就送去登录页，并记住原本要去的地址。
 *
 * 注意这只是体验层。真正的门在服务端（AuthInterceptor），
 * 前端守卫可以被绕过——绕过之后接口仍然会 401。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const agent = await ensureAgent()
  if (!agent) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
