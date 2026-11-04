import http from './http'

/** 用户端入口用：能咨询哪几类问题（只有 id + 名称，不含字段定义） */
export const listChatTemplates = () => http.get('/chat/templates')

export const createSession = (templateId) =>
  http.post('/chat/sessions', { templateId })

export const sendMessage = (sessionId, content) =>
  http.post(`/chat/sessions/${sessionId}/messages`, { content })

/**
 * 顾客确认后提交给人工。只有「信息不全」的工单会用到。
 * summary 可传顾客改过的那句话；不传就用系统整理的那版。
 */
export const confirmTicket = (sessionId, summary) =>
  http.post(`/chat/sessions/${sessionId}/confirm`, { summary })

/**
 * 工单状态回执。顾客出单后轮询这个接口。
 * changed=true 表示这次产生了新回执（前端追加一条气泡）。
 */
export const checkStatus = (sessionId) =>
  http.get(`/chat/sessions/${sessionId}/status`)

/** 人工端用：取回某次会话的原始对话，核对工单依据 */
export const getHistory = (sessionId) =>
  http.get(`/chat/sessions/${sessionId}/messages`)
