import http from './http'

export const listTemplates = () => http.get('/templates')
export const getTemplate = (id) => http.get(`/templates/${id}`)
export const createTemplate = (body) => http.post('/templates', body)
export const updateTemplate = (id, body) => http.put(`/templates/${id}`, body)

/** 试抽：跑一次完整抽取 + 核验，不落库 */
export const previewTemplate = (id, dialogue) =>
  http.post(`/templates/${id}/preview`, { dialogue })
