import { reactive } from 'vue'
import { me } from '../shared/api/auth'

/*
 * 当前登录者，只活在内存里，不写 localStorage。
 *
 * 凭据是 HttpOnly cookie，前端手里本来就没有 token；再把 displayName
 * 抄进 localStorage，只是多造一份会过期、会和服务端对不上的副本。
 * 刷新页面就没了，用 ensureStaff() 静默再问一次。
 *
 * "只问一次"靠 loaded/pending 两个标记：并发进来三次 ensureStaff()，
 * 只会发一个请求，其余两次等同一个 promise。
 */
export const state = reactive({ staff: null })

let loaded = false
let pending = null

export function setStaff(staff) {
  state.staff = staff
  loaded = true
  pending = null
}

/**
 * 退出后 loaded 置 true、staff 置 null：
 * 我们确定现在是未登录，不该再为每个受保护页面补发一次 /auth/me。
 */
export function clearStaff() {
  state.staff = null
  loaded = true
  pending = null
}

export function ensureStaff() {
  if (loaded) return Promise.resolve(state.staff)
  if (!pending) {
    pending = me()
      .then((staff) => {
        state.staff = staff
        return staff
      })
      .catch(() => {
        state.staff = null
        return null
      })
      .finally(() => {
        loaded = true
        pending = null
      })
  }
  return pending
}
