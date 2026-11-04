<template>
  <LoginCard title="食材采购台账" subtitle="台账管理端 · 入账与供应商"
             @success="onSuccess" />
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import LoginCard from '../../shared/components/LoginCard.vue'
import { setStaff } from '../authStore'

/*
 * 登录页。
 *
 * 登录动作本身交给 LoginCard（采购端也用同一个卡片，账号规则只在一处），
 * 这里只负责登录成功后的两件事：写进内存的 authStore、回到用户本来想看的那一页。
 *
 * redirect 只认站内绝对路径，并且自己在本地再挡一次 /login 与 //host：
 * 守卫那边也挡过一次，但这里是真正执行跳转的地方，宁可重复也不留开放跳转。
 */
const route = useRoute()
const router = useRouter()

function onSuccess(staff) {
  setStaff(staff)
  ElMessage.success(`欢迎，${staff?.displayName || staff?.username || ''}`)

  const redirect = route.query.redirect
  const internal =
    typeof redirect === 'string' &&
    redirect.startsWith('/') &&
    !redirect.startsWith('//') &&
    !redirect.startsWith('/login')
  router.replace(internal ? redirect : '/ledger')
}
</script>
