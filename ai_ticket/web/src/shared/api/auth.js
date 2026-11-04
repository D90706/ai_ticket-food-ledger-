import http from './http'

/**
 * 登录相关。
 *
 * 用 cookie session，不用 token：
 * 前端不需要保存任何凭据，浏览器自动带 Cookie。
 * 好处是「退出登录」真的能退掉——token 存下来的话，
 * 客服在公共电脑上登过就撤不回来了。
 */
export const login = (username, password) =>
  http.post('/auth/login', { username, password })

/** 静默询问：未登录时不弹提示、不跳转，由调用方决定怎么处理 */
export const me = () => http.get('/auth/me', { silent: true })

export const logout = () => http.post('/auth/logout')
