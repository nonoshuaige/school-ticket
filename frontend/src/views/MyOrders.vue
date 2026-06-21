<template>
  <div class="orders-page">
    <van-nav-bar title="我的订单" left-arrow @click-left="$router.back()" />

    <van-tabs v-model:active="activeTab" sticky @change="onTabChange">
      <van-tab title="全部"></van-tab>
      <van-tab title="待支付"></van-tab>
      <van-tab title="已支付"></van-tab>
    </van-tabs>

    <div class="order-list">
      <div v-for="item in orders" :key="item.orderNo" class="ticket-item" @click="goToDetail(item)">
        <div class="ticket-top">
          <span class="ticket-event">{{ item.eventTitle }}</span>
          <span class="ticket-status" :style="{ color: orderStatusColor(item.status) }">
            {{ statusLabel(item) }}
          </span>
        </div>
        <div class="ticket-info">
          <span>{{ item.ticketName }} × {{ item.quantity }}</span>
          <span>{{ formatDate(item.eventStartTime) }}</span>
        </div>
        <div class="ticket-venue">{{ item.eventVenue }}</div>
        <div class="ticket-actions" v-if="item.status === 0" @click.stop>
          <van-button size="mini" type="primary" @click="payNow(item)">去支付</van-button>
          <van-button size="mini" plain @click="cancelNow(item)">取消</van-button>
        </div>
      </div>

      <div v-if="orders.length === 0 && !loading" class="empty-state">
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
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { showToast, showConfirmDialog } from 'vant'
import { getOrderList, payOrder, cancelOrder } from '../api/order'
import { formatDate, orderStatusText, orderStatusColor } from '../utils/format'

const router = useRouter()
const orders = ref([])
const total = ref(0)
const loading = ref(false)
const activeTab = ref(0)
const page = ref(1)
const pageSize = 10

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function buildParams() {
  const params = { page: page.value, pageSize }
  if (activeTab.value === 1) params.status = 0
  else if (activeTab.value === 2) params.status = 1
  return params
}

onMounted(() => { loadOrders() })

async function loadOrders() {
  loading.value = true
  try {
    const res = await getOrderList(buildParams())
    orders.value = res.records
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

function statusLabel(item) {
  if (item.status === 0) return '待支付'
  if (item.status === 1) return '已支付'
  return orderStatusText(item.status)
}

function goToDetail(item) {
  router.push(`/mine/detail/${item.orderNo}`)
}

async function payNow(item) {
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
.orders-page { padding-bottom: 20px; }
.order-list { padding: 0; }
.ticket-item { background: #fff; border-radius: 10px; padding: 14px; margin: 10px 12px; box-shadow: 0 1px 4px rgba(0,0,0,.06); cursor: pointer; }
.ticket-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.ticket-event { font-size: 15px; font-weight: 600; }
.ticket-status { font-size: 13px; font-weight: 600; }
.ticket-info { display: flex; justify-content: space-between; font-size: 13px; color: #666; margin-bottom: 4px; }
.ticket-venue { font-size: 12px; color: #999; }
.ticket-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; padding-top: 8px; border-top: 1px solid #f0f0f0; }
.ticket-actions .van-button { font-size: 12px; height: 28px; line-height: 28px; }
.empty-state { text-align: center; padding: 80px 0; }
.empty-state p { color: #999; margin: 8px 0; }
</style>
