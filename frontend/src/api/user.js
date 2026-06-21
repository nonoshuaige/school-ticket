import request from '../utils/request'

export function followUser(followeeId) {
  return request.post(`/user/follow/${followeeId}`)
}

export function unfollowUser(followeeId) {
  return request.delete(`/user/follow/${followeeId}`)
}

export function getFollowStats() {
  return request.get('/user/follow/stats')
}

export function checkFollowing(followeeId) {
  return request.get(`/user/follow/check/${followeeId}`)
}
