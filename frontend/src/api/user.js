import request from '../utils/request'

export function followUser(userId) {
  return request.post(`/user/follow/${userId}`)
}

export function unfollowUser(userId) {
  return request.delete(`/user/follow/${userId}`)
}

export function getFollowStats() {
  return request.get('/user/follow/stats')
}

export function checkFollowing(userId) {
  return request.get(`/user/follow/check/${userId}`)
}

export function checkFollowingBatch(userIds) {
  return request.post('/user/follow/check/batch', userIds)
}

export function getFollowingList(params) {
  return request.get('/user/follow/following', { params })
}

export function getFansList(params) {
  return request.get('/user/follow/fans', { params })
}
