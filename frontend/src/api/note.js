import request from '../utils/request'

export function getNoteList(params) {
  return request.get('/note/list', { params })
}

export function likeNote(noteId) {
  return request.post(`/note/${noteId}/like`)
}

export function unlikeNote(noteId) {
  return request.delete(`/note/${noteId}/like`)
}
