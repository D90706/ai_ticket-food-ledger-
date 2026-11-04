<template>
  <div class="login">
    <div class="card">
      <div class="head">
        <span class="logo">台账</span>
        <div>
          <div class="title">{{ title }}</div>
          <div class="sub">{{ subtitle }}</div>
        </div>
      </div>

      <el-input v-model="form.username" placeholder="用户名" size="large" class="gap"
                @keyup.enter="submit" />
      <el-input v-model="form.password" type="password" placeholder="密码" size="large"
                show-password class="gap" @keyup.enter="submit" />

      <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
        登录
      </el-button>

      <div class="hint">
        演示账号：<b>caigou</b>（采购员）/ <b>guanli</b>（管理员），密码 <b>123456</b>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'

const props = defineProps({
  title: { type: String, default: '食材采购台账' },
  subtitle: { type: String, default: '登录后使用' }
})
const emit = defineEmits(['success'])

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const staff = await login(form.username.trim(), form.password)
    emit('success', staff)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #1f2b23;
}
.card {
  width: 360px;
  background: #fff;
  border-radius: 10px;
  padding: 28px 26px 20px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.28);
}
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 22px; }
.logo {
  width: 38px; height: 38px; border-radius: 9px; background: var(--brand);
  color: #fff; font-size: 13px; font-weight: 700;
  display: flex; align-items: center; justify-content: center; flex: 0 0 38px;
}
.title { font-size: 16px; font-weight: 700; color: var(--text-main); }
.sub { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
.gap { margin-bottom: 12px; }
.submit { width: 100%; }
.hint { margin-top: 16px; font-size: 12px; color: #a8b3bf; text-align: center; }
.hint b { color: #7b8794; }
</style>