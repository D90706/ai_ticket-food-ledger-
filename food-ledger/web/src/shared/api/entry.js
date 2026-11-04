import http from './http'

/** 开一次 AI 录入会话 */
export const createEntrySession = () => http.post('/entry/sessions')

/** 说一句：返回 ASK / CONFIRM / RECORD */
export const sendEntryMessage = (sessionId, content) =>
  http.post(`/entry/sessions/${sessionId}/messages`, { content })

/** 确认后生成台账记录（状态是「待入账」，不是已入账） */
export const confirmEntry = (sessionId, summary) =>
  http.post(`/entry/sessions/${sessionId}/confirm`, { summary })

export const entryHistory = (sessionId) => http.get(`/entry/sessions/${sessionId}/messages`)

/**
 * 对话中途上传凭证照片：把 /api/uploads 拿到的附件挂到这次录入上。
 * 后端会把它并入会话的 values，凭证这一项随后就算已提供。
 */
export const attachToEntry = (sessionId, attachment) =>
  http.post(`/entry/sessions/${sessionId}/attachments`, attachment)