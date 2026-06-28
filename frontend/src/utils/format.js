/**
 * 格式化价格为 ¥xx.xx
 */
export function formatPrice(price) {
  return '¥' + (Number(price).toFixed(2))
}

/**
 * 格式化日期时间
 */
export function formatDate(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 格式化日期（不含时间）
 */
export function formatDateShort(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`
}

/**
 * 订单状态文本
 */
export function orderStatusText(status) {
  const map = { 0: '待支付', 1: '已支付', 2: '已取消', 3: '已退款', 4: '已核销' }
  return map[status] || '未知'
}

/**
 * 订单状态颜色
 */
export function orderStatusColor(status) {
  const map = { 0: '#f57c00', 1: '#4caf50', 2: '#999', 3: '#f44336', 4: '#2196f3' }
  return map[status] || '#999'
}

/**
 * 格式化日期时间（短格式：MM/DD HH:mm）
 */
export function formatDateTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 剩余时间（倒计时用）
 */
export function countdown(expireTime) {
  const diff = new Date(expireTime).getTime() - Date.now()
  if (diff <= 0) return '已过期'
  const min = Math.floor(diff / 60000)
  const sec = Math.floor((diff % 60000) / 1000)
  return `${min}分${sec}秒`
}
