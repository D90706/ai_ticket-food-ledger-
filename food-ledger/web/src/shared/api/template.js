import http from './http'

/**
 * 取默认台账模板（字段定义）。
 *
 * 前端不写死字段：模板改了，表单跟着变。
 * 这是「字段可自定义」在界面上的落点——
 * 如果表单字段写死，改模板就得改前端，那"可自定义"就是假的。
 */
export const getDefaultTemplate = () => http.get('/templates/default')