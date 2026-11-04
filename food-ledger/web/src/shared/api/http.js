import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一请求包装。
 *
 * 后端约定：HTTP 200 + { code, message, data }，code === 0 成功。
 * 所以这里只做四件事：解包 data、非 0 抛错（带上 code）、
 * 弹一次提示（silent 时跳过）、401 时整页跳登录。
 *
 * 业务代码只处理成功分支；要区分 403 / 409 的地方读 err.code。
 */
const http = axios.create({ baseURL: '/api', timeout: 180000 })

/**
 * 401 时回到登录页。
 *
 * 三个细节，都是踩过坑才写下来的：
 *
 * 1 只跳真正"登录态失效"的请求。
 *   登录接口密码错也返回 401，那不是会话过期——
 *   一路把页面重载掉，用户连「密码错误」的提示都看不到。
 *
 * 2 整页跳转（location.href），不是改 hash。
 *   改 hash 只对管理端（有路由）有效；录入端没有路由，
 *   改了 hash 页面什么都不发生，用户会停在点不动的界面上。
 *   整页跳转顺带把内存里的登录态也清了——这是必要的：
 *   管理端的守卫缓存着 staff，不清就会拿旧缓存直接放回列表页。
 *
 * 3 两个端的登录页位置不同：
 *   录入端就在 /（未登录时渲染登录卡片），管理端在 /admin/#/login。
 */
function handleUnauthorized(requestUrl) {
  const url = requestUrl || ''
  // 登录/身份探测本身的 401 不跳转，由调用方自己处理
  if (url.includes('/auth/')) return

  if (window.location.pathname.startsWith('/admin')) {
    // 管理端有路由：拨到登录页；已经在登录页就不再动，避免循环
    if (window.location.hash.includes('/login')) return
    window.location.href = '/admin/#/login'
    return
  }

  // 录入端没有路由：重新加载 -> me() 失败 -> 渲染登录卡片
  // 已经在 / 上就 reload（页面必须重置，否则内存里的 staff 还在，界面停在死状态）
  if (window.location.pathname === '/') {
    window.location.reload()
    return
  }
  window.location.href = '/'
}

function fail(message, code, silent, requestUrl) {
  const error = new Error(message)
  error.code = code
  if (!silent) {
    if (code === 401) {
      handleUnauthorized(requestUrl)
    }
    ElMessage.error(message)
  }
  return Promise.reject(error)
}

http.interceptors.response.use(
  (response) => {
    const body = response.data
    const silent = response.config?.silent === true
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      return fail(body.message || '请求失败', body.code, silent, response.config?.url)
    }
    return body
  },
  (error) => {
    const silent = error.config?.silent === true
    const data = error.response?.data
    return fail(data?.message || error.message || '网络错误',
      data?.code ?? error.response?.status, silent, error.config?.url)
  }
)

export default http
