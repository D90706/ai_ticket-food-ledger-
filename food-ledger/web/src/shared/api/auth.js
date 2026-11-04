import http from './http'

/** 登录用 cookie session，前端不保存任何凭据 */
export const login = (username, password) => http.post('/auth/login', { username, password })

/** 静默询问：未登录时不弹提示、不跳转 */
export const me = () => http.get('/auth/me', { silent: true })

export const logout = () => http.post('/auth/logout')