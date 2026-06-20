import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi, getUserInfo } from '../api/auth'
import { logout as logoutApi } from '../api/auth'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref(null)
  const isLoggedIn = ref(!!localStorage.getItem('isLoggedIn'))

  async function login(phone, password) {
    await loginApi({ phone, password })
    localStorage.setItem('isLoggedIn', 'true')
    isLoggedIn.value = true
    await fetchUserInfo()
  }

  async function fetchUserInfo() {
    try {
      userInfo.value = await getUserInfo()
    } catch {
      // ignore
    }
  }

  async function logout() {
    try { await logoutApi() } catch {} 
    userInfo.value = null
    isLoggedIn.value = false
    localStorage.removeItem('isLoggedIn')
  }

  return { userInfo, isLoggedIn, login, fetchUserInfo, logout }
})
