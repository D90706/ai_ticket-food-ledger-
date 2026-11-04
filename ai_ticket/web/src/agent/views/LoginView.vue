<template>
  <div class="login-page">
    <div class="card">
      <div class="head">
        <span class="logo">工单</span>
        <div>
          <div class="title">人工客服工作台</div>
          <div class="sub">批阅 · 决策 · 配置</div>
        </div>
      </div>

      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input
            v-model="form.username"
            placeholder="用户名"
            size="large"
            autocomplete="username"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="submit"
          native-type="submit"
          :loading="loading"
        >
          登录
        </el-button>
      </el-form>

      <div class="hint">
        演示账号：<b>zhangsan</b> / <b>lisi</b>，密码 <b>123456</b>
      </div>
      <a class="back" href="/">← 回到顾客端</a>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../../shared/api/auth'
import { setAgent } from '../authStore'

const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const agent = await login(form.username.trim(), form.password)
    setAgent(agent)
    router.replace(route.query.redirect || '/tickets')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #1f2b3a;
}

.card {
  width: 360px;
  background: #fff;
  border-radius: 10px;
  padding: 30px 28px 22px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.25);
}

.head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.logo {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  background: #2f80ed;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 38px;
}

.title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-main);
}

.sub {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.submit {
  width: 100%;
}

.hint {
  margin-top: 16px;
  font-size: 12px;
  color: #a8b3bf;
  text-align: center;
}

.hint b {
  color: #7b8794;
}

.back {
  display: block;
  text-align: center;
  margin-top: 10px;
  font-size: 12px;
  color: #a8b3bf;
  text-decoration: none;
}

.back:hover {
  color: #2f80ed;
}
</style>
