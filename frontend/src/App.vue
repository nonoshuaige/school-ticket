<template>
  <div id="app-container">
    <router-view />
    <!-- 底部导航：仅在购票/我的页面显示 -->
    <BottomNav v-if="showTabbar" />
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from './stores/user'
import BottomNav from './components/BottomNav.vue'

const route = useRoute()
const userStore = useUserStore()

const showTabbar = computed(() => {
  return ['/', '/notes', '/mine'].includes(route.path)
})

onMounted(async () => {
  if (userStore.isLoggedIn) {
    await userStore.fetchUserInfo()
  }
})
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: #f5f5f5; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
#app-container { max-width: 480px; margin: 0 auto; min-height: 100vh; position: relative; }
</style>
