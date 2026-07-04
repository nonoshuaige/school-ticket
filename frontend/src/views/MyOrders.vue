<template>
  <div class="orders-page">
    <van-nav-bar title="我的订单" left-arrow @click-left="$router.back()" />

    <van-tabs v-model:active="activeTab" sticky @change="onTabChange">
      <van-tab title="全部"></van-tab>
      <van-tab title="待支付"></van-tab>
      <van-tab title="已支付"></van-tab>
    </van-tabs>

    <div class="order-list">
      <div v-for="group in groups" :key="group.eventId" class="event-group">
        <div class="event-header">
          <div class="event-title">{{ group.eventTitle }}</div>
          <div class="event-sub">
            <span>{{ formatDate(group.eventStartTime) }}</span>
            <span> · {{ group.eventVenue }}</span>
            <span> · {{ group.orders.length }}笔订单</span>
          </div>
        </div>
        <div
          v-for="item in group.orders"
          :key="item.orderNo"
          class="order-item"
          @click="goToDetail(item)"
        >
          <div class="order-left">
            <div class="order-ticket">{{ item.ticketName }} × {{ item.quantity }}</div>
            <div class="order-price">¥{{ item.totalPrice }}</div>
          </div>
          <div class="order-right">
            <span class="order-status" :style="{ color: orderStatusColor(item.status) }">
              {{ orderStatusText(item.status) }}
            </span>
            <span class="order-time">{{ formatDateTime(item.createTime) }}</span>
          </div>
          <div class="order-actions" v-if="item.status === 0" @click.stop>
            <span class="countdown-label" :class="{ expired: countdownInfo(item.expireTime).expired }">
              {{ countdownInfo(item.expireTime).text }}
            </span>
            <van-button size="mini" type="primary" :disabled="countdownInfo(item.expireTime).expired" @click="payNow(item)">去支付</van-button>
            <van-button size="mini" plain @click="cancelNow(item)">取消</van-button>
          </div>
        </div>
      </div>

      <div v-if="groups.length === 0 && !loading" class="empty-state">
        <van-icon name="info-o" size="48" color="#ccc"/>
        <p>暂无订单</p>
      </div>
    </div>

    <van-pagination
      v-if="totalPages > 1"
      v-model="page"
      :page-count="totalPages"
      :items-per-page="pageSize"
      mode="simple"
      @change="onPageChange"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { showToast, showConfirmDialog } from 'vant'
import { getOrderList, payOrder, cancelOrder } from '../api/order'
import { formatDate, orderStatusText, orderStatusColor } from '../utils/format'

const router = useRouter()
const groups = ref([])
const total = ref(0)
const loading = ref(false)
const activeTab = ref(0)
const page = ref(1)
const pageSize = 10

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function formatDateTime(d) {
  if (!d) return ''
  const t = new Date(d)
  const pad = n => String(n).padStart(2, '0')
  return `${t.getFullYear()}-${pad(t.getMonth()+1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}`
}

function buildParams() {
  const params = { page: page.value, pageSize }
  if (activeTab.value === 1) params.status = 0
  else if (activeTab.value === 2) params.status = 1
  return params
}

const now = ref(Date.now())
let timer = null

onMounted(() => {
  loadOrders()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})

onUnmounted(() => {
  clearInterval(timer)
})

async function loadOrders() {
  loading.value = true
  try {
    const res = await getOrderList(buildParams())
    groups.value = res.records
    total.value = res.total
  } catch {} finally {
    loading.value = false
  }
}

function onTabChange() {
  page.value = 1
  loadOrders()
}

function onPageChange() {
  loadOrders()
}

function countdownInfo(expireTime) {
  if (!expireTime) return { text: '', expired: false }
  const diff = new Date(expireTime).getTime() - now.value
  if (diff <= 0) return { text: '已过期', expired: true }
  const min = Math.floor(diff / 60000)
  const sec = Math.floor((diff % 60000) / 1000)
  return { text: `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`, expired: false }
}

function goToDetail(item) {
  router.push(`/mine/detail/${item.orderNo}`)
}

async function payNow(item) {
  if (countdownInfo(item.expireTime).expired) {
    showToast('订单已过期')
    return
  }
  try {
    await payOrder(item.orderNo)
    showToast('支付成功')
    await loadOrders()
  } catch {}
}

async function cancelNow(item) {
  showConfirmDialog({ title: '提示', message: '确定要取消此订单吗？' }).then(async () => {
    try {
      await cancelOrder(item.orderNo)
      showToast('已取消')
      await loadOrders()
    } catch {}
  }).catch(() => {})
}
</script>

<style scoped>
.orders-page { padding-bottom: 20px; background: #f7f8fa; min-height: 100vh; }
.order-list { padding: 0; }
.event-group { margin: 10px 12px 16px; }
.event-header { padding: 12px 0 8px; }
.event-title { font-size: 16px; font-weight: 700; color: #323233; }
.event-sub { font-size: 12px; color: #999; margin-top: 4px; }
.order-item { background: #fff; border-radius: 10px; padding: 12px 14px; margin-bottom: 6px; box-shadow: 0 1px 3px rgba(0,0,0,.04); cursor: pointer; }
.order-left { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.order-ticket { font-size: 14px; font-weight: 500; }
.order-price { font-size: 14px; font-weight: 600; color: #f40; }
.order-right { display: flex; justify-content: space-between; align-items: center; }
.order-status { font-size: 12px; font-weight: 600; }
.order-time { font-size: 11px; color: #999; }
.order-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; padding-top: 8px; border-top: 1px solid #f5f5f5; }
.order-actions .van-button { font-size: 12px; height: 28px; line-height: 28px; }
.countdown-label { font-size: 12px; color: #f57c00; white-space: nowrap; }
.countdown-label.expired { color: #999; }
.empty-state { text-align: center; padding: 80px 0; }
.empty-state p { color: #999; margin: 8px 0; }
</style>
