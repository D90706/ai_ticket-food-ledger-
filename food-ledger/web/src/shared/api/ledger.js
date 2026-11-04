import http from './http'

/** 台账全量列表：导出与统计用，注意不受页大小限制 */
export const listLedger = (params) => http.get('/ledger', { params })

/** 台账分页列表：界面用。返回 { rows, total, page, size } */
export const listLedgerPage = (params) => http.get('/ledger/page', { params })

export const getLedger = (id) => http.get(`/ledger/${id}`)

export const createLedger = (body) => http.post('/ledger', body)

export const updateLedger = (id, body) => http.put(`/ledger/${id}`, body)

/** 入账 / 退回补录 / 作废：都要带 version，防止覆盖别人刚做的修改 */
export const postLedger = (id, version) => http.post(`/ledger/${id}/post`, { version })

export const returnLedger = (id, version, note) =>
  http.post(`/ledger/${id}/return`, { version, note })

export const voidLedger = (id, version, reason) =>
  http.post(`/ledger/${id}/void`, { version, reason })

/** 按批号追溯：同一批次还有哪些记录 */
export const traceBatch = (batchNo) => http.get('/ledger/trace', { params: { batchNo } })

export const listCategories = () => http.get('/categories')

export const listSuppliers = (params) => http.get('/suppliers', { params })

export const createSupplier = (body) => http.post('/suppliers', body)

export const updateSupplier = (id, body) => http.put(`/suppliers/${id}`, body)

/**
 * 手填录入：直接提交一张「字段 key → 值」的表。
 *
 * 不自己拼后端 DTO 的驼峰字段，是因为后端会用同一个映射器把这张表转过去——
 * 和 AI 录入走完全同一条路，规则不会分叉。
 */
export const createLedgerFromValues = (values) => http.post('/ledger/values', { values })

/**
 * 按「字段表」修改记录（与 createLedgerFromValues 对称）。
 * 补录界面按模板渲染，提交的就是模板 key → 值，无需在前端拼驼峰属性。
 */
export const updateLedgerFromValues = (id, values, version) =>
  http.put(`/ledger/${id}/values`, { values, version })