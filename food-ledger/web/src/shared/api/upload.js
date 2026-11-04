import http from './http'

/**
 * 上传附件（索证索票照片、检验报告）。
 *
 * 用 FormData 而不是 base64：base64 会让请求体积涨三分之一，
 * 而且后端还得再解一次码。多文件时逐个上传，
 * 这样一个失败不会连累其他的。
 */
export const uploadFile = (file) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/uploads', form, { timeout: 60000 })
}