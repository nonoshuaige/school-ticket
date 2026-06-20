import request from '../utils/request'

export function register(data) {
  return request.post('/auth/register', data)
}

export function login(data) {
  return request.post('/auth/login', data)
}

export function getUserInfo() {
  return request.get('/user/info')
}

export function logout() {
  return request.post('/auth/logout')
}