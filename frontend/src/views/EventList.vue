<template>
  <div class="event-list-page">
    <div class="page-header">
      <h2>🎭 校园票务</h2>
      <span v-if="userStore.isLoggedIn" class="user-badge" @click="$router.push('/mine')">
        {{ userStore.userInfo?.nickname || '我' }}
      </span>
      <span v-else class="user-badge" @click="$router.push('/login')">登录</span>
    </div>

    <div class="event-list">
      <EventCard
        v-for="event in events"
        :key="event.eventId"
        :event="event"
        @click="goToDetail(event.eventId)"
      />
    </div>

    <div v-if="events.length === 0 && !loading" class="empty-state">
      <van-icon name="info-o" size="48" color="#ccc"/>
      <p>暂无活动</p>
    </div>

    <BottomNav />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getEventList } from '../api/event'
import EventCard from '../components/EventCard.vue'
import BottomNav from '../components/BottomNav.vue'

const router = useRouter()
const userStore = useUserStore()
const events = ref([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    events.value = await getEventList()
  } catch (e) {
    // ignore
  } finally {
    loading.value = false
  }
})

function goToDetail(id) {
  router.push(`/event/${id}`)
}
</script>

<style scoped>
.event-list-page { padding: 0 0 60px; }
.page-header { display: flex; justify-content: space-between; align-items: center; padding: 16px; background: #fff; position: sticky; top: 0; z-index: 10; }
.page-header h2 { font-size: 20px; font-weight: 600; }
.user-badge { font-size: 14px; color: #1989fa; cursor: pointer; padding: 4px 12px; border-radius: 12px; background: #f0f8ff; }
.event-list { padding: 0 12px; }
.empty-state { text-align: center; padding: 80px 0; }
.empty-state p { color: #999; margin-top: 8px; }
</style>
