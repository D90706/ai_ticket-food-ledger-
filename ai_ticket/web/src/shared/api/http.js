import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一请求包装。
 *
 * 后端约定：HTTP 200 + { code, message, data }，code === 0 表示成功。
 * 所以这里做四件事：解包 data、非 0 时抛错（带上 code）、
 * 把错误变成一次提示（silent 时跳过）、401 时把人工端踢回登录页。
 *
 * 业务代码因此只需要处理成功分支；
 * 需要区分错误类型的地方（比如 409 版本冲突）读 err.code 即可。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 180000 // 抽取要调模型，实测 1 秒左右，但留足余量
})

function fail(message, code, silent) {
  const error = new Error(message)
  error.code = code
  if (!silent) {
    if (code === 401) {
      redirectToLogin()
    }
    ElMessage.error(message)
  }
  return Promise.reject(error)
}

/**
 * 只把人工端踢回登录页。
 * 用户端（/）没有登录这回事，跳过去只会让人莫名其妙。
 */
function redirectToLogin() {
  if (!window.location.pathname.startsWith('/agent')) return
  if (window.location.hash.includes('/login')) return
  window.location.hash = '#/login'
}

http.interceptors.response.use(
  (response) => {
    const body = response.data
    const silent = response.config?.silent === true

    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      return fail(body.message || '请求失败', body.code, silent)
    }
    return body
  },
  (error) => {
    const silent = error.config?.silent === true
    const data = error.response?.data
    return fail(
      data?.message || error.message || '网络错误',
      data?.code ?? error.response?.status,
      silent
    )
  }
)

export default http
