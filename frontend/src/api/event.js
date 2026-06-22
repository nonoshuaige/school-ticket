import request from '../utils/request'

export function getEventList(status, page = 1, pageSize = 8) {
  return request.get('/event/list', { params: { status, page, pageSize } })
}

export function getEventDetail(eventId) {
  return request.get(`/event/${eventId}`)
}

export function getEventTickets(eventId) {
  return request.get(`/event/${eventId}/tickets`)
}

export function getPurchaseStatus(eventId) {
  return request.get(`/event/${eventId}/purchase-status`)
}
