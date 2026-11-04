<template>
  <LoginCard v-if="!staff" title="食材采购台账" subtitle="采购录入端"
             @success="onLogin" />

  <div v-else class="shell">
    <header class="top">
      <div class="brand">
        <span class="logo">台账</span>
        <span class="name">采购录入</span>
        <span class="tag">一句话记账 · 缺什么就提示什么</span>
      </div>
      <div class="right">
        <a class="to-admin" href="/admin/" target="_blank" rel="noopener">台账管理端 ↗</a>
        <span class="who">{{ staff.displayName }}（{{ roleText }}）</span>
        <el-button link size="small" @click="doLogout">退出</el-button>
      </div>
    </header>

    <!-- 管理员误入录入端时给一条明确出路，而不是让他以为登录错了 -->
    <div v-if="isManager" class="role-tip">
      你是<b>管理员</b>：日常在
      <a href="/admin/">台账管理端</a>
      审核入账；这里只用于临时补录。
    </div>

    <nav class="tabs">
      <button :class="{ on: tab === 'entry' }" @click="tab = 'entry'">记一笔</button>
      <button :class="{ on: tab === 'manual' }" @click="tab = 'manual'">手填</button>
      <button :class="{ on: tab === 'mine' }" @click="tab = 'mine'">我的记录</button>
    </nav>

    <main class="body">
      <EntryView v-if="tab === 'entry'" :staff="staff"
                 @recorded="onRecorded" @go-mine="onGoMine" />
      <ManualEntryView v-else-if="tab === 'manual'" :staff="staff"
                       @recorded="onRecorded" @go-mine="onGoMine" />
      <MyRecordsView v-else :staff="staff" :refresh-key="refreshKey" />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import LoginCard from '../shared/components/LoginCard.vue'
import EntryView from './views/EntryView.vue'
import ManualEntryView from './views/ManualEntryView.vue'
import MyRecordsView from './views/MyRecordsView.vue'
import { logout, me } from '../shared/api/auth'
import { clearAllDrafts } from '../shared/draft'

const staff = ref(null)
const tab = ref('entry')
const refreshKey = ref(0)

const roleText = computed(() => (staff.value?.role === 'MANAGER' ? '管理员' : '采购员'))
const isManager = computed(() => staff.value?.role === 'MANAGER')

onMounted(async () => {
  // 支持 ?tab=manual 直达：三个页签都是常用入口，能收藏更好
  const wanted = new URLSearchParams(window.location.search).get('tab')
  if (wanted && ['entry', 'manual', 'mine'].includes(wanted)) {
    tab.value = wanted
  }
  try {
    staff.value = await me()
  } catch (e) {
    staff.value = null
  }
  // 已经在管理端登录过、又打开录入端：不强制跳转（管理员也可能要临时补录），
  // 只在页面上给提示；只有"刚登录"那一刻才替他跳到该去的地方
})

function onLogin(value) {
  staff.value = value
  if (value.role === 'MANAGER') {
    // 管理员的默认页是台账管理端，不是录入端——
    // 这是人工测试提的第一个问题：两种角色登录后落在同一个页面
    ElMessage.success('管理员账号，正在进入台账管理端…')
    window.location.href = '/admin/'
    return
  }
  ElMessage.success('已登录')
}

function onRecorded() {
  refreshKey.value += 1
}

/**
 * 结果卡上的「去我的记录」。
 *
 * 两件事都要做，少一件就会出现「点了没反应」或「切过去了但列表里没有刚提交那条」：
 * 1 刷新键 +1，让 MyRecordsView 重新拉一次数据
 * 2 切到「我的记录」页签
 */
function onGoMine() {
  refreshKey.value += 1
  tab.value = 'mine'
}

async function doLogout() {
  try {
    await logout()
  } catch (e) {
    /* 会话可能已过期，照样清本地状态 */
  }
  staff.value = null
}
</script>

<style scoped>
.shell { height: 100vh; display: flex; flex-direction: column; }
.top {
  height: 58px; flex: 0 0 58px; background: #fff; border-bottom: 1px solid var(--line);
  display: flex; align-items: center; padding: 0 20px;
}
.brand { display: flex; align-items: center; gap: 10px; }
.logo {
  width: 28px; height: 28px; border-radius: 7px; background: var(--brand);
  color: #fff; font-size: 12px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.name { font-size: 16px; font-weight: 700; color: var(--text-main); }
.tag { font-size: 12px; color: var(--text-muted); border-left: 1px solid var(--line); padding-left: 10px; }
.right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.to-admin { font-size: 12px; color: var(--text-muted); text-decoration: none; }
.to-admin:hover { color: var(--brand); }
.who { font-size: 13px; color: var(--text-main); }
.role-tip {
  flex: 0 0 auto;
  padding: 8px 20px;
  background: #fff8e6;
  border-bottom: 1px solid #f3e2c7;
  font-size: 12px;
  color: #a86b1a;
}
.role-tip a { color: #a86b1a; font-weight: 600; }
.tabs {
  flex: 0 0 46px; height: 46px; background: #fff; border-bottom: 1px solid var(--line);
  display: flex; gap: 4px; padding: 0 16px; align-items: stretch;
}
.tabs button {
  border: 0; background: transparent; padding: 0 16px; cursor: pointer;
  font-size: 14px; color: var(--text-muted); border-bottom: 2px solid transparent;
}
.tabs button.on { color: var(--brand); border-bottom-color: var(--brand); font-weight: 600; }
.body { flex: 1; min-height: 0; display: flex; justify-content: center; padding: 16px; }
</style>
