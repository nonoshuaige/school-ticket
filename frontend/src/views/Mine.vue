<template>
  <div class="mine-page">
    <div class="user-header" v-if="userStore.userInfo">
      <div class="user-avatar">{{ (userStore.userInfo.nickname || 'U')[0] }}</div>
      <div class="user-meta">
        <div class="user-name">{{ userStore.userInfo.nickname }}</div>
        <div class="user-phone">{{ userStore.userInfo.phone }}</div>
        <div class="user-follow-stats" v-if="followStats">
          <span>关注 {{ followStats.followingCount }}</span>
          <span class="divider">|</span>
          <span>粉丝 {{ followStats.followerCount }}</span>
        </div>
      </div>
      <van-button size="small" plain hairline round color="#ffd700" @click="handleLogout"
        style="border-color:#ffd700;color:#ffd700;font-size:12px;min-width:70px">退出登录</van-button>
    </div>

    <van-cell-group inset style="margin: 16px 12px; border-radius: 10px;">
      <van-cell title="我的订单" is-link to="/mine/orders" icon="records-o" />
    </van-cell-group>

    <BottomNav />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { showToast, showConfirmDialog } from 'vant'
import { useUserStore } from '../stores/user'
import { getFollowStats } from '../api/user'
import BottomNav from '../components/BottomNav.vue'

const router = useRouter()
const userStore = useUserStore()
const followStats = ref(null)

onMounted(async () => {
  if (userStore.isLoggedIn) {
    try { followStats.value = await getFollowStats() } catch {}
  }
})

function handleLogout() {
  showConfirmDialog({ title: '提示', message: '确定要退出登录吗？' }).then(() => {
    userStore.logout()
    showToast('已退出')
    router.replace('/login')
  }).catch(() => {})
}
</script>

<style scoped>
.mine-page { padding: 0 0 60px; }
.user-header { display: flex; align-items: center; padding: 20px 16px; background: linear-gradient(135deg, #667eea, #764ba2); color: #fff; }
.user-avatar { width: 48px; height: 48px; border-radius: 50%; background: rgba(255,255,255,.3); display: flex; align-items: center; justify-content: center; font-size: 20px; font-weight: 600; margin-right: 12px; }
.user-meta { flex: 1; }
.user-name { font-size: 18px; font-weight: 600; }
.user-phone { font-size: 13px; opacity: .8; margin-top: 2px; }
.user-follow-stats { font-size: 12px; opacity: .75; margin-top: 4px; }
.user-follow-stats .divider { margin: 0 6px; opacity: .5; }
</style>