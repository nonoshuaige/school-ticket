<template>
  <div class="detail-page" v-if="event">
    <!-- 返回按钮 -->
    <van-nav-bar :title="event.title" left-arrow @click-left="$router.back()" />

    <!-- 活动信息 -->
    <div class="event-header">
      <div class="event-banner">{{ emoji }}</div>
      <div class="event-meta">
        <h2>{{ event.title }}</h2>
        <p><van-icon name="manager-o"/> {{ event.organizer }}</p>
        <p><van-icon name="location-o"/> {{ event.venue }}</p>
        <p><van-icon name="clock-o"/> {{ formatDate(event.eventStartTime) }}</p>
      </div>
      <div class="event-desc" v-if="event.description">
        <p>{{ event.description }}</p>
      </div>
    </div>

    <!-- 票档列表 -->
    <div class="tickets-section">
      <h3>选择票档</h3>
      <div
        v-for="ticket in tickets"
        :key="ticket.ticketId"
        class="ticket-card"
        :class="{
          selected: selectedTicket?.ticketId === ticket.ticketId,
          disabled: !isOnSale || getTicketStatus(ticket).disabled
        }"
        @click="isOnSale && selectTicket(ticket)"
      >
        <div class="ticket-left">
          <div class="ticket-name">{{ ticket.name }}</div>
          <div class="ticket-remain" :class="{ low: ticket.remainingQuantity < 20 }">
            余 {{ ticket.remainingQuantity }} 张
          </div>
        </div>
        <div class="ticket-right">
          <div class="ticket-price">¥{{ ticket.price }}</div>
          <div v-if="!isOnSale" class="ticket-not-sale">未开售</div>
          <div v-else-if="getTicketStatus(ticket).reason" class="ticket-not-sale">{{ getTicketStatus(ticket).reason }}</div>
          <van-stepper
            v-if="isOnSale && !getTicketStatus(ticket).disabled && selectedTicket?.ticketId === ticket.ticketId"
            v-model="quantity"
            :min="1"
            :max="Math.min(ticket.remainingQuantity, getTicketStatus(ticket).maxQty)"
            @click.stop
          />
        </div>
      </div>
      <div v-if="tickets.length === 0" class="no-tickets">
        <p>暂无票档</p>
      </div>
    </div>

    <!-- 底部购买按钮 -->
    <div class="bottom-bar" v-if="selectedTicket || !isOnSale">
      <div v-if="!isOnSale" class="not-sale-text">活动尚未开售</div>
      <template v-else>
        <div class="total-price">合计：¥{{ (selectedTicket.price * quantity).toFixed(2) }}</div>
        <van-button type="danger" round @click="goBuy">立即购买</van-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { getEventDetail, getPurchaseStatus } from '../api/event'
import { useOrderStore } from '../stores/order'
import { formatDate } from '../utils/format'

const route = useRoute()
const router = useRouter()
const orderStore = useOrderStore()

const event = ref(null)
const tickets = ref([])
const selectedTicket = ref(null)
const quantity = ref(1)
const purchaseStatus = ref(null)

const isOnSale = computed(() => {
  if (!event.value) return false
  const now = Date.now()
  return now >= new Date(event.value.saleStartTime).getTime() && now <= new Date(event.value.saleEndTime).getTime()
})

const emoji = computed(() => {
  const t = event.value?.title || ''
  if (t.includes('毕业')) return '🎓'
  if (t.includes('歌手')) return '🎤'
  if (t.includes('新年')) return '🎊'
  if (t.includes('戏剧')) return '🎭'
  return '🎪'
})

onMounted(async () => {
  try {
    const data = await getEventDetail(route.params.id)
    event.value = data.event
    tickets.value = data.tickets
    if (localStorage.getItem('isLoggedIn')) {
      try {
        purchaseStatus.value = await getPurchaseStatus(route.params.id)
      } catch { /* 未登录忽略 */ }
    }
  } catch {
    showToast('加载失败')
    router.back()
  }
})

function getTicketStatus(ticket) {
  if (!purchaseStatus.value) return { disabled: false, reason: '', maxQty: 5 }
  const ps = purchaseStatus.value
  // 已购买其他票档 → 全部不可买
  if (ps.purchasedTicketId && ps.purchasedTicketId !== ticket.ticketId) {
    return { disabled: true, reason: '已购买其他票档', maxQty: 0 }
  }
  // 该票档已买满5张
  if (ps.purchasedTicketId === ticket.ticketId && ps.purchasedQuantity >= 5) {
    return { disabled: true, reason: '已达购买上限', maxQty: 0 }
  }
  // 该票档已买部分
  if (ps.purchasedTicketId === ticket.ticketId) {
    const remaining = 5 - ps.purchasedQuantity
    return { disabled: false, reason: `已购${ps.purchasedQuantity}/5张`, maxQty: remaining }
  }
  // 未购买
  return { disabled: false, reason: '', maxQty: 5 }
}

function selectTicket(ticket) {
  const status = getTicketStatus(ticket)
  if (status.disabled) return
  if (selectedTicket.value?.ticketId === ticket.ticketId) {
    selectedTicket.value = null
  } else {
    selectedTicket.value = ticket
    quantity.value = 1
  }
}

function goBuy() {
  if (!selectedTicket.value) return
  if (!localStorage.getItem('isLoggedIn')) {
    showToast('请先登录')
    router.push('/login')
    return
  }
  orderStore.setOrderData(event.value, selectedTicket.value, quantity.value)
  router.push('/order/confirm')
}
</script>

<style scoped>
.detail-page { padding-bottom: 70px; }
.event-header { background: #fff; padding: 16px; }
.event-banner { font-size: 48px; text-align: center; padding: 20px 0; }
.event-meta h2 { font-size: 20px; margin-bottom: 8px; }
.event-meta p { font-size: 14px; color: #666; margin-bottom: 4px; display: flex; align-items: center; gap: 4px; }
.event-desc { margin-top: 12px; padding: 12px; background: #f9f9f9; border-radius: 8px; font-size: 14px; color: #555; line-height: 1.6; }
.tickets-section { padding: 16px; }
.tickets-section h3 { font-size: 16px; margin-bottom: 12px; }
.ticket-card { display: flex; justify-content: space-between; align-items: center; background: #fff; padding: 14px 16px; border-radius: 10px; margin-bottom: 10px; border: 2px solid transparent; transition: all .2s; }
.ticket-card.selected { border-color: #1989fa; background: #f0f8ff; }
.ticket-card.disabled { opacity: .5; cursor: default; }
.ticket-not-sale { font-size: 12px; color: #999; margin-top: 4px; }
.ticket-name { font-size: 16px; font-weight: 600; }
.ticket-remain { font-size: 12px; color: #999; margin-top: 4px; }
.ticket-remain.low { color: #f40; }
.ticket-right { display: flex; flex-direction: column; align-items: flex-end; gap: 8px; }
.ticket-price { font-size: 22px; font-weight: 700; color: #f40; }
.no-tickets { text-align: center; padding: 40px; color: #999; }
.bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; padding: 10px 16px; background: #fff; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 -2px 8px rgba(0,0,0,.1); max-width: 480px; margin: 0 auto; }
.total-price { font-size: 18px; font-weight: 700; color: #f40; }
.not-sale-text { font-size: 14px; color: #999; text-align: center; width: 100%; }
</style>
