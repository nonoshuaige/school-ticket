<template>
  <div class="confirm-page">
    <van-nav-bar title="确认订单" left-arrow @click-left="handleBack" />

    <div class="order-summary" v-if="event">
      <div class="summary-header">{{ event?.title }}</div>
      <div class="summary-info">
        <van-cell-group>
          <van-cell title="场次" :value="formatDate(event?.eventStartTime)" />
          <van-cell title="场地" :value="event?.venue" />
        </van-cell-group>
      </div>

      <div class="ticket-info" v-if="ticket">
        <van-cell-group>
          <van-cell title="票档" :value="ticket?.name" />
          <van-cell title="单价" :value="'¥' + ticket?.price" />
          <van-cell title="数量" :value="qty + ' 张'" />
          <van-cell title="小计" :value="'¥' + (ticket?.price * qty).toFixed(2)" />
        </van-cell-group>
      </div>
    </div>

    <div class="bottom-bar">
      <div class="total">合计：<span class="price">¥{{ total }}</span></div>
      <van-button type="danger" round @click="handleSubmit" :loading="paying">确认下单</van-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useOrderStore } from '../stores/order'
import { createOrder } from '../api/order'
import { formatDate } from '../utils/format'

const router = useRouter()
const orderStore = useOrderStore()

const { currentEvent: event, selectedTicket: ticket, quantity: qty } = storeToRefs(orderStore)
const paying = ref(false)

const total = computed(() => {
  if (!ticket.value) return '0.00'
  return (ticket.value.price * qty.value).toFixed(2)
})

if (!event.value || !ticket.value) {
  router.replace('/')
}

function handleBack() {
  orderStore.clear()
  router.back()
}

let lastSubmitTime = 0

async function handleSubmit() {
  const now = Date.now()
  if (now - lastSubmitTime < 1000) return
  lastSubmitTime = now

  paying.value = true
  try {
    const order = await createOrder({
      ticketId: ticket.value.ticketId,
      quantity: qty.value
    })
    orderStore.setCurrentOrder(order)
    router.push('/order/pay')
  } catch {
    // ignore
  } finally {
    paying.value = false
  }
}
</script>

<style scoped>
.confirm-page { padding-bottom: 70px; }
.summary-header { font-size: 18px; font-weight: 600; padding: 16px; background: #fff; }
.ticket-info { margin-top: 10px; }
.bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; padding: 10px 16px; background: #fff; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 -2px 8px rgba(0,0,0,.1); max-width: 480px; margin: 0 auto; }
.total { font-size: 16px; }
.total .price { font-size: 22px; font-weight: 700; color: #f40; }
</style>