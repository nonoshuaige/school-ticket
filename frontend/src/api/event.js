import request from '../utils/request'

export function getEventList(status) {
  return request.get('/event/list', { params: { status } })
}

export function getEventDetail(eventId) {
  return request.get(`/event/${eventId}`)
}

export function getEventTickets(eventId) {
  return request.get(`/event/${eventId}/tickets`)
}
