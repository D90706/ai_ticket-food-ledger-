import { createRouter, createWebHashHistory } from 'vue-router'
import { clearStaff, ensureStaff } from '../authStore'
import LoginView from '../views/LoginView.vue'
import LedgerListView from '../views/LedgerListView.vue'
import LedgerDetailView from '../views/LedgerDetailView.vue'
import SupplierListView from '../views/SupplierListView.vue'

/*
 * 管理端路由表。
 *
 * 用 hash 模式：管理端经常被丢到内网 nginx 的某个子目录下直接静态托管，
 * history 模式少了后端 rewrite 就会 404；hash 模式复制到任何目录都能打开。
 *
 * 登录校验只写在 beforeEach 一处：四个页面各写一次 if (!staff) 迟早漏一个，
 * 漏的那个页面会在拿不到数据时白屏，而不是回到登录页。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { public: true, title: '登录' }
  },
  { path: '/', redirect: '/ledger' },
  { path: '/ledger', name: 'ledger', component: LedgerListView, meta: { title: '台账' } },
  { path: '/ledger/:id', name: 'ledger-detail', component: LedgerDetailView, meta: { title: '台账详情' } },
  { path: '/stats', name: 'stats', component: () => import('../views/StatsView.vue'), meta: { title: '统计' } },
  { path: '/suppliers', name: 'suppliers', component: SupplierListView, meta: { title: '供应商' } },
  // 未知地址回列表：管理端没有 404 页，也没必要为它多一个页面
  { path: '/:pathMatch(.*)*', redirect: '/ledger' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    /*
     * 到登录页只有两种情况：还没登录，或者会话刚在服务端过期
     * ——http.js 遇到 401 会把 hash 拨到 #/login。
     * 两种情况下内存里那个 staff 都不再可信，清掉它，
     * 否则守卫会拿旧缓存把人直接送回列表页，登录页永远出不来。
     */
    if (to.path === '/login') clearStaff()
    return true
  }

  const staff = await ensureStaff()
  if (!staff) {
    // 带上原地址，登录后能回到本来想看的那一行
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 采购员能登录，但没有管理权限：把他送回录入端，
  // 而不是让他对着满屏按钮一个个点出 403
  if (staff.role !== 'MANAGER') {
    clearStaff()
    window.location.href = '/'
    return false
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 食材采购台账` : '食材采购台账'
})

export default router
