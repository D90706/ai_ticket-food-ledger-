<template>
  <!-- 登录页不套管理框架：还没有登录者时，侧栏每个链接点下去都只会被弹回来 -->
  <router-view v-if="isLogin" />

  <div v-else class="shell">
    <aside class="side">
      <div class="brand">
        <span class="logo">台账</span>
        <div class="brand-text">
          <div class="brand-name">食材台账</div>
          <div class="brand-sub">管理端</div>
        </div>
      </div>

      <nav class="nav">
        <router-link to="/ledger" class="nav-item" :class="{ on: inLedger }">台账</router-link>
        <router-link to="/stats" class="nav-item" :class="{ on: inStats }">统计</router-link>
        <router-link to="/suppliers" class="nav-item" :class="{ on: inSuppliers }">供应商</router-link>
      </nav>

      <a class="cross" href="/" target="_blank" rel="noopener">采购录入端 ↗</a>
    </aside>

    <div class="main">
      <header class="top">
        <div class="title">{{ pageTitle }}</div>
        <div class="user">
          <span class="who">{{ staff?.displayName || '未命名' }}</span>
          <span class="role">{{ roleText }}</span>
          <el-button link size="small" @click="doLogout">退出</el-button>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { clearStaff, state } from './authStore'
import { logout } from '../shared/api/auth'

/*
 * 管理端外壳：左侧深色导航 + 顶部当前用户 + 右侧内容区。
 *
 * 导航只有「台账 / 供应商」两项，没有做成可配置菜单：
 * 这个系统的管理动作就这些，多一层菜单配置只会让权限和路由对不上。
 *
 * 登录页单独走一个分支，不套外壳——外壳里的每个入口都需要登录态。
 */
const route = useRoute()
const router = useRouter()

const isLogin = computed(() => route.path === '/login')
const staff = computed(() => state.staff)
const roleText = computed(() => (staff.value?.role === 'MANAGER' ? '管理员' : '采购员'))
const pageTitle = computed(() => route.meta.title || '')
const inLedger = computed(() => route.path.startsWith('/ledger'))
const inSuppliers = computed(() => route.path.startsWith('/suppliers'))
const inStats = computed(() => route.path.startsWith('/stats'))

async function doLogout() {
  try {
    await logout()
  } catch (e) {
    // 会话可能已经在服务端过期了，照样把本地状态清掉
  }
  clearStaff()
  ElMessage.success('已退出')
  router.replace('/login')
}
</script>

<style scoped>
.shell { height: 100vh; display: flex; }

.side {
  flex: 0 0 196px; width: 196px; height: 100%;
  background: #1f2b23; display: flex; flex-direction: column;
  padding: 16px 12px 14px;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 0 6px 18px; }
.logo {
  width: 32px; height: 32px; border-radius: 8px; background: var(--brand);
  color: #fff; font-size: 12px; font-weight: 700; flex: 0 0 32px;
  display: flex; align-items: center; justify-content: center;
}
.brand-name { color: #fff; font-size: 15px; font-weight: 700; line-height: 1.2; }
.brand-sub { color: #7f9686; font-size: 11px; margin-top: 2px; }

.nav { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
.nav-item {
  display: block; padding: 10px 12px; border-radius: 6px;
  color: #c4d3c9; font-size: 14px; text-decoration: none;
  border-left: 3px solid transparent;
}
.nav-item:hover { background: rgba(255, 255, 255, 0.06); color: #fff; }
.nav-item.on {
  background: rgba(46, 125, 50, 0.32); border-left-color: #7ac47f;
  color: #fff; font-weight: 600;
}

.cross {
  margin-top: auto; font-size: 12px; color: #7f9686;
  text-decoration: none; padding: 8px 12px;
}
.cross:hover { color: #fff; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

.top {
  flex: 0 0 56px; height: 56px; background: #fff; border-bottom: 1px solid var(--line);
  display: flex; align-items: center; padding: 0 20px;
}
.title { font-size: 15px; font-weight: 700; color: var(--text-main); }
.user { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.who { font-size: 13px; color: var(--text-main); }
.role {
  font-size: 11px; color: var(--brand); border: 1px solid #cfe4d0;
  background: #f2fbf4; border-radius: 10px; padding: 1px 7px;
}

.content { flex: 1; min-height: 0; overflow: auto; padding: 16px 20px 24px; }
</style>
