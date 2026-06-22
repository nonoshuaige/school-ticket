import request from '../utils/request'

export function getNoteList(params) {
  return request.get('/note/list', { params })
}

export function getRecommendFeed(params) {
  return request.get('/note/recommend-feed', { params })
}

export function getFollowingFeed(params) {
  return request.get('/note/following-feed', { params })
}

export function getMyNotes(params) {
  return request.get('/note/my-notes', { params })
}

export function getNoteDetail(noteId) {
  return request.get(`/note/${noteId}`)
}

export function createNote(content) {
  return request.post('/note/create', { content })
}

export function likeNote(noteId) {
  return request.post(`/note/${noteId}/like`)
}

export function unlikeNote(noteId) {
  return request.delete(`/note/${noteId}/like`)
}

export function getComments(noteId, params) {
  return request.get(`/note/${noteId}/comment`, { params })
}

export function createComment(noteId, data) {
  return request.post(`/note/${noteId}/comment`, data)
}

export function deleteComment(noteId, commentId) {
  return request.delete(`/note/${noteId}/comment/${commentId}`)
}
