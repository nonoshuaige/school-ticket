<template>
  <div class="event-list-page">
    <div class="page-header">
      <h2>🎭 校园票务</h2>
      <span v-if="userStore.isLoggedIn" class="user-badge" @click="$router.push('/mine')">
        {{ userStore.userInfo?.nickname || '我' }}
      </span>
      <span v-else class="user-badge" @click="$router.push('/login')">登录</span>
    </div>

    <div v-if="hotEvents.length > 0" class="section">
      <div class="section-header">🔥 热卖中</div>
      <div class="event-grid">
        <EventCard
          v-for="event in hotEvents"
          :key="event.eventId"
          :event="event"
          @click="goToDetail(event.eventId)"
        />
      </div>
    </div>

    <div v-if="upcomingEvents.length > 0" class="section">
      <div class="section-header">⏳ 预热中</div>
      <div class="event-grid">
        <EventCard
          v-for="event in upcomingEvents"
          :key="event.eventId"
          :event="event"
          @click="goToDetail(event.eventId)"
        />
      </div>
    </div>

    <div v-if="hotEvents.length === 0 && upcomingEvents.length === 0 && !loading" class="empty-state">
      <van-icon name="info-o" size="48" color="#ccc"/>
      <p>暂无活动</p>
    </div>

    <div v-if="!loading && totalPages > 1" class="pagination-wrap">
      <van-pagination
        v-model="page"
        :page-count="totalPages"
        :items-per-page="pageSize"
        mode="simple"
        @change="loadEvents"
      />
    </div>

    <div v-if="loading" class="loading-state">
      <van-loading size="24" />
    </div>

    <BottomNav />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getEventList } from '../api/event'
import EventCard from '../components/EventCard.vue'
import BottomNav from '../components/BottomNav.vue'

const router = useRouter()
const userStore = useUserStore()
const events = ref([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const pageSize = 8

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

const now = Date.now()
const hotEvents = computed(() => events.value.filter(e => {
  const start = new Date(e.saleStartTime).getTime()
  const end = new Date(e.saleEndTime).getTime()
  return now >= start && now <= end
}))
const upcomingEvents = computed(() => events.value.filter(e => {
  const start = new Date(e.saleStartTime).getTime()
  return now < start
}))

async function loadEvents() {
  loading.value = true
  try {
    const res = await getEventList(null, page.value, pageSize)
    events.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    // ignore
  } finally {
    loading.value = false
  }
}

onMounted(() => { loadEvents() })

function goToDetail(id) {
  router.push(`/event/${id}`)
}
</script>

<style scoped>
.event-list-page { padding: 0 0 60px; }
.page-header { display: flex; justify-content: space-between; align-items: center; padding: 16px; background: #fff; position: sticky; top: 0; z-index: 10; }
.page-header h2 { font-size: 20px; font-weight: 600; }
.user-badge { font-size: 14px; color: #1989fa; cursor: pointer; padding: 4px 12px; border-radius: 12px; background: #f0f8ff; }
.section { padding: 0 12px; margin-top: 16px; }
.section-header { font-size: 17px; font-weight: 700; margin-bottom: 10px; color: #333; }
.event-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.empty-state { text-align: center; padding: 80px 0; }
.empty-state p { color: #999; margin-top: 8px; }
.loading-state { text-align: center; padding: 20px; }
.pagination-wrap { display: flex; justify-content: center; margin-top: 20px; }
</style>
