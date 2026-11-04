import { reactive } from 'vue'
import { me } from '../shared/api/auth'

/**
 * 人工端的登录态。
 *
 * 只在内存里缓存，不写 localStorage：
 * 真正的凭据是浏览器的 session cookie，前端复制一份身份只会让人误以为
 * 「改一下 localStorage 就能冒充别人」或者反过来「清掉就安全了」。
 *
 * checked 是为了只问后端一次——每次路由切换都打一趟 /auth/me 是浪费。
 */
export const state = reactive({
  agent: null,
  checked: false
})

export async function ensureAgent() {
  if (!state.checked) {
    try {
      state.agent = await me()
    } catch (e) {
      state.agent = null
    }
    state.checked = true
  }
  return state.agent
}

export function setAgent(agent) {
  state.agent = agent
  state.checked = true
}

export function clearAgent() {
  state.agent = null
  state.checked = false
}
