<template>
  <div class="pay-page" v-if="order">
    <van-nav-bar title="订单支付" left-arrow @click-left="$router.back()" />

    <div class="pay-header">
      <div class="amount">¥{{ order?.totalPrice?.toFixed(2) }}</div>
      <div class="countdown" :class="{ expired: expired }">
        <van-icon name="clock-o" />
        支付剩余：{{ expired ? '已过期' : countdownText }}
      </div>
    </div>

    <van-cell-group>
      <van-cell title="订单编号" :value="order?.orderNo" />
      <van-cell title="创建时间" :value="formatDate(order?.createTime)" />
    </van-cell-group>

    <div style="margin: 30px 16px">
      <van-button round block type="primary" size="large"
        :disabled="expired || paying" :loading="paying" @click="handlePay">
        {{ expired ? '订单已过期' : '模拟支付' }}
      </van-button>
    </div>

    <div style="margin: 0 16px; text-align: center;">
      <van-button plain size="small" @click="handleCancel" :disabled="paying">取消订单</van-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { showToast, showConfirmDialog } from 'vant'
import { storeToRefs } from 'pinia'
import { useOrderStore } from '../stores/order'
import { payOrder, cancelOrder } from '../api/order'
import { formatDate } from '../utils/format'

const router = useRouter()
const orderStore = useOrderStore()

const { currentOrder: order } = storeToRefs(orderStore)
const paying = ref(false)
const expired = ref(false)
const countdownText = ref('')
let timer = null

if (!order.value) {
  router.replace('/')
}

function updateCountdown() {
  if (!order.value?.expireTime) return
  const diff = new Date(order.value.expireTime).getTime() - Date.now()
  if (diff <= 0) {
    expired.value = true
    countdownText.value = '已过期'
    clearInterval(timer)
    return
  }
  const min = Math.floor(diff / 60000)
  const sec = Math.floor((diff % 60000) / 1000)
  countdownText.value = `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

onMounted(() => {
  updateCountdown()
  timer = setInterval(updateCountdown, 1000)
})

onUnmounted(() => {
  clearInterval(timer)
})

async function handlePay() {
  paying.value = true
  try {
    await payOrder(order.value.orderNo)
    showToast('支付成功')
    router.push('/order/result')
  } catch {
    // error handled by interceptor
  } finally {
    paying.value = false
  }
}

async function handleCancel() {
  showConfirmDialog({ title: '提示', message: '确定要取消此订单吗？' })
    .then(async () => {
      try {
        await cancelOrder(order.value.orderNo)
        showToast('已取消')
        orderStore.clear()
        router.replace('/')
      } catch {}
    })
    .catch(() => {})
}
</script>

<style scoped>
.pay-header { text-align: center; padding: 30px 16px; background: #fff; margin-bottom: 10px; }
.amount { font-size: 40px; font-weight: 700; color: #333; margin-bottom: 12px; }
.countdown { font-size: 14px; color: #f57c00; display: flex; align-items: center; justify-content: center; gap: 4px; }
.countdown.expired { color: #999; }
</style>