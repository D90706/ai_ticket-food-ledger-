<template>
  <!-- 登录页是独立的一屏，不套工作台框架 -->
  <router-view v-if="isLogin" />

  <div v-else class="shell">
    <aside class="side">
      <div class="brand">
        <span class="logo">工单</span>
        <div class="brand-text">
          <div class="brand-name">人工客服工作台</div>
          <div class="brand-sub">批阅 · 决策 · 配置</div>
        </div>
      </div>

      <nav class="menu">
        <router-link
          v-for="item in menu"
          :key="item.path"
          :to="item.path"
          class="menu-item"
          :class="{ active: current === item.key }"
        >
          <span class="icon" v-html="item.icon"></span>
          <span>{{ item.label }}</span>
          <span v-if="item.badge" class="badge">{{ item.badge }}</span>
        </router-link>
      </nav>

      <div class="side-foot">
        <div class="who">
          <span class="who-avatar">{{ initial }}</span>
          <div class="who-text">
            <div class="who-name">{{ agentName }}</div>
            <div class="who-role">{{ roleText }}</div>
          </div>
          <button class="who-out" title="退出登录" @click="doLogout">退出</button>
        </div>
        <!-- 演示用：方便在两端之间来回切 -->
        <a class="switch" href="/" target="_blank" rel="noopener">打开用户端 ↗</a>
      </div>
    </aside>

    <main class="main">
      <div class="page-head">
        <h1>{{ pageTitle }}</h1>
        <span class="page-sub">{{ pageSub }}</span>
      </div>
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logout as apiLogout } from '../shared/api/auth'
import { listTickets } from '../shared/api/ticket'
import { clearAgent, state as authState } from './authStore'

const route = useRoute()
const router = useRouter()
const pendingCount = ref(0)

const isLogin = computed(() => route.name === 'login')
const agentName = computed(() => authState.agent?.displayName || '未登录')
const initial = computed(() => (authState.agent?.displayName || '?').slice(0, 1))
const roleText = computed(() => (authState.agent?.role === 'LEAD' ? '组长' : '客服'))

// 顶部菜单只有两项，刻意做得比后台管理系统更窄
const menu = [
  {
    key: 'tickets',
    path: '/tickets',
    label: '工单',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5h16v6a2 2 0 0 0 0 2v6H4v-6a2 2 0 0 0 0-2z"/><path d="M9 5v14"/></svg>'
  },
  {
    key: 'templates',
    path: '/templates',
    label: '模板',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M9 9v11"/></svg>'
  }
]

const current = computed(() => {
  const seg = route.path.split('/')[1] || 'tickets'
  return seg === 'templates' ? 'templates' : 'tickets'
})

const pageTitle = computed(() => (current.value === 'templates' ? '模板' : '工单'))
const pageSub = computed(() =>
  current.value === 'templates'
    ? '定义字段与决策项——谁定义字段，谁负责这套业务'
    : '颜色绑定的是「能不能直接决策」：红=先补信息，黄=先核对依据，绿=可直接决策'
)

async function loadBadge() {
  // 登录页不请求受保护接口，否则会白白触发一次 401
  if (!authState.agent) return
  try {
    const tickets = await listTickets({ status: 'PENDING' })
    pendingCount.value = tickets.length
    menu[0].badge = pendingCount.value || ''
  } catch (e) {
    /* 后端没起时不影响界面渲染 */
  }
}

async function doLogout() {
  try {
    await apiLogout()
  } catch (e) {
    /* 会话可能已过期，照样清本地状态 */
  }
  clearAgent()
  router.replace('/login')
}

onMounted(loadBadge)
watch(() => route.name, (name) => {
  if (name !== 'login') loadBadge()
})
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
}

.side {
  width: 210px;
  flex: 0 0 210px;
  background: #1f2b3a;
  color: #b8c4d2;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  border-bottom: 1px solid #2b3a4b;
}

.logo {
  width: 30px;
  height: 30px;
  border-radius: 7px;
  background: #2f80ed;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 30px;
}

.brand-name {
  font-size: 14px;
  font-weight: 700;
  color: #fff;
}

.brand-sub {
  font-size: 11px;
  color: #7e8fa1;
  margin-top: 2px;
}

.menu {
  padding: 12px 10px;
  flex: 1;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border-radius: 6px;
  color: #b8c4d2;
  text-decoration: none;
  font-size: 14px;
  margin-bottom: 4px;
}

.menu-item:hover {
  background: #26374a;
  color: #fff;
}

.menu-item.active {
  background: #2b5f9e;
  color: #fff;
  font-weight: 600;
}

.menu-item .icon {
  width: 17px;
  height: 17px;
  display: inline-flex;
}
.menu-item .icon :deep(svg) {
  width: 17px;
  height: 17px;
}

.badge {
  margin-left: auto;
  background: #ef5350;
  color: #fff;
  font-size: 11px;
  line-height: 1;
  padding: 3px 6px;
  border-radius: 9px;
}

.side-foot {
  padding: 12px 14px 16px;
  border-top: 1px solid #2b3a4b;
}

.who {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 10px;
}

.who-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #2b5f9e;
  color: #fff;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
}

.who-text {
  min-width: 0;
  flex: 1;
}

.who-name {
  font-size: 13px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.who-role {
  font-size: 11px;
  color: #7e8fa1;
}

.who-out {
  border: 0;
  background: transparent;
  color: #7e8fa1;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
}

.who-out:hover {
  color: #ef5350;
}

.switch {
  font-size: 12px;
  color: #7e8fa1;
  text-decoration: none;
}
.switch:hover {
  color: #fff;
}

.main {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 20px 24px 32px;
  background: var(--bg-page);
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
}

.page-head h1 {
  margin: 0;
  font-size: 20px;
  color: var(--text-main);
}

.page-sub {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
