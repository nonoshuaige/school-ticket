import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi, getUserInfo } from '../api/auth'
import { logout as logoutApi } from '../api/auth'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref(null)
  const isLoggedIn = ref(!!localStorage.getItem('isLoggedIn') || !!localStorage.getItem('authToken'))

  async function login(phone, password) {
    const token = await loginApi({ phone, password })
    if (token) {
      localStorage.setItem('authToken', token)
    }
    localStorage.setItem('isLoggedIn', 'true')
    isLoggedIn.value = true
    await fetchUserInfo()
  }

  async function fetchUserInfo() {
    try {
      userInfo.value = await getUserInfo()
      if (userInfo.value?.userId) {
        localStorage.setItem('currentUserId', String(userInfo.value.userId))
      }
    } catch {
      // ignore
    }
  }

  async function logout() {
    try { await logoutApi() } catch {}
    userInfo.value = null
    isLoggedIn.value = false
    localStorage.removeItem('isLoggedIn')
    localStorage.removeItem('authToken')
    localStorage.removeItem('currentUserId')
  }

  return { userInfo, isLoggedIn, login, fetchUserInfo, logout }
})
