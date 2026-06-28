<template>
  <div class="event-card" @click="$emit('click')">
    <div class="card-banner">
      <div class="card-emoji">{{ emoji }}</div>
      <div class="card-status" v-if="isOnSale">热卖中</div>
      <div class="card-status upcoming" v-else-if="isUpcoming">即将开售</div>
      <div class="card-status ended" v-else>已结束</div>
    </div>
    <div class="card-body">
      <h3 class="card-title">{{ event.title }}</h3>
      <div class="card-info">
        <div class="info-row sale-row">
          {{ isOnSale ? '🎫 截止' : isUpcoming ? '🎫 开售' : '🎫' }}
          {{ isOnSale ? formatDateTime(event.saleEndTime) : formatDateTime(event.saleStartTime) }}
        </div>
        <div class="info-row">📅 {{ formatDateTime(event.eventStartTime) }}</div>
        <div class="info-row">📍 {{ event.venue }}</div>
      </div>
      <div class="card-price">¥{{ minPrice }} 起</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatDateTime } from '../utils/format'

const props = defineProps({
  event: { type: Object, required: true }
})

defineEmits(['click'])

const now = Date.now()
const saleStart = computed(() => new Date(props.event.saleStartTime).getTime())
const saleEnd = computed(() => new Date(props.event.saleEndTime).getTime())

const isOnSale = computed(() => now >= saleStart.value && now <= saleEnd.value)
const isUpcoming = computed(() => now < saleStart.value)

const emoji = computed(() => {
  const map = {
    '毕业': '🎓', '歌手': '🎤', '新年': '🎊', '戏剧': '🎭',
    '篮球': '🏀', '动漫': '🎮', '美食': '🍔', '摄影': '📷',
    '音乐': '🎵', '辩论': '🎙', '街舞': '💃', '马拉松': '🏃',
    '演讲': '🎤', '电影': '🎬', '中秋': '🌕'
  }
  for (const [key, val] of Object.entries(map)) {
    if (props.event.title.includes(key)) return val
  }
  return '🎪'
})

const minPrice = computed(() => {
  if (props.event.minPrice != null && props.event.minPrice > 0) {
    return Number(props.event.minPrice)
  }
  return '??'
})
</script>

<style scoped>
.event-card {
  background: #fff;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 1px 6px rgba(0,0,0,.06);
  cursor: pointer;
}
.card-banner {
  height: 80px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}
.card-emoji { font-size: 42px; }
.card-status {
  position: absolute;
  top: 8px;
  right: 8px;
  background: #ff6b35;
  color: #fff;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}
.card-status.upcoming { background: #1989fa; }
.card-status.ended { background: #999; }
.card-body { padding: 10px 12px; }
.card-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-info { margin-bottom: 6px; }
.info-row { font-size: 12px; color: #888; line-height: 1.6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sale-row { color: #e67e22; font-weight: 500; }
.card-price { font-size: 16px; font-weight: 700; color: #f40; }
</style>
