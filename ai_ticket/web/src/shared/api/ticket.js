import http from './http'

export const listTickets = (params) => http.get('/tickets', { params })
export const getTicket = (id) => http.get(`/tickets/${id}`)
export const patchTicket = (id, body) => http.patch(`/tickets/${id}`, body)
