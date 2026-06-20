import request from '../utils/request'

export function createOrder(data) {
  return request.post('/order/create', data)
}

export function payOrder(orderNo) {
  return request.post('/order/pay', { orderNo })
}

export function cancelOrder(orderNo) {
  return request.post('/order/cancel', { orderNo })
}

export function refundOrder(orderNo) {
  return request.post('/order/refund', { orderNo })
}

export function getOrderList() {
  return request.get('/order/list')
}

export function getOrderDetail(orderNo) {
  return request.get(`/order/${orderNo}`)
}
