<template>
  <div class="event-card" @click="$emit('click')">
    <div class="card-banner">
      <div class="card-emoji">{{ emoji }}</div>
      <div class="card-status" v-if="isOnSale">热卖中</div>
      <div class="card-status sold-out" v-else-if="new Date(event.saleStartTime) > new Date()">即将开售</div>
      <div class="card-status ended" v-else>已结束</div>
    </div>
    <div class="card-body">
      <h3 class="card-title">{{ event.title }}</h3>
      <div class="card-info">
        <span>📅 {{ formatDateShort(event.eventStartTime) }}</span>
        <span>📍 {{ event.venue }}</span>
      </div>
      <div class="card-footer">
        <span class="card-price">¥{{ minPrice }} 起</span>
        <span class="card-organizer">{{ event.organizer }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatDateShort } from '../utils/format'

const props = defineProps({
  event: { type: Object, required: true }
})

defineEmits(['click'])

const emoji = computed(() => {
  const map = {
    '毕业': '🎓',
    '歌手': '🎤',
    '新年': '🎊',
    '戏剧': '🎭'
  }
  for (const [key, val] of Object.entries(map)) {
    if (props.event.title.includes(key)) return val
  }
  return '🎪'
})

const isOnSale = computed(() => {
  const now = Date.now()
  return now >= new Date(props.event.saleStartTime).getTime() && now <= new Date(props.event.saleEndTime).getTime()
})

const minPrice = computed(() => {
  // Prices are hardcoded for demo — in production, fetch from ticket list
  const prices = { 1: 88, 2: 38, 3: 128, 4: 48 }
  return prices[props.event.eventId] || '??'
})
</script>

<style scoped>
.event-card { background: #fff; border-radius: 10px; overflow: hidden; margin-bottom: 12px; box-shadow: 0 1px 4px rgba(0,0,0,.08); cursor: pointer; }
.card-banner { height: 80px; background: linear-gradient(135deg, #667eea, #764ba2); display: flex; align-items: center; justify-content: space-between; padding: 0 16px; position: relative; }
.card-emoji { font-size: 36px; }
.card-status { background: #ff6b35; color: #fff; font-size: 12px; padding: 2px 10px; border-radius: 10px; }
.card-status.sold-out { background: #999; }
.card-status.ended { background: #666; }
.card-body { padding: 12px 16px; }
.card-title { font-size: 16px; font-weight: 600; margin-bottom: 6px; }
.card-info { display: flex; gap: 12px; font-size: 13px; color: #666; margin-bottom: 8px; }
.card-footer { display: flex; justify-content: space-between; align-items: center; }
.card-price { font-size: 18px; font-weight: 700; color: #f40; }
.card-organizer { font-size: 12px; color: #999; }
</style>
