import axios from 'axios'
import { showToast, showDialog } from 'vant'

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  withCredentials: true
})

// 请求拦截器：附加 Authorization header 作为 cookie 的兜底
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('authToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      showToast(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg))
    }
    return res.data
  },
  (error) => {
    if (error.response) {
      const status = error.response.status
      if (status === 401 || status === 403) {
        localStorage.removeItem('isLoggedIn')
        localStorage.removeItem('authToken')
        location.replace('/#/login')
        return Promise.reject(error)
      } else if (status === 500) {
        showToast('服务器错误')
      } else {
        showToast(error.response.data?.msg || '请求失败')
      }
    } else {
      showToast('网络异常')
    }
    return Promise.reject(error)
  }
)

export default request
