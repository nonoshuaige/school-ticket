<template>
  <div class="ticket-detail-page" v-if="order">
    <van-nav-bar title="票证详情" left-arrow @click-left="$router.back()" />

    <!-- 状态条 -->
    <div class="status-bar" :style="{ background: statusBg }">
      <div class="status-icon"><van-icon :name="statusIcon" size="32" color="#fff"/></div>
      <div class="status-text">{{ orderStatusText(order.status) }}</div>
    </div>

    <!-- 活动信息 -->
    <van-cell-group title="活动信息">
      <van-cell title="活动名称" :value="order.eventTitle" />
      <van-cell title="活动场地" :value="order.eventVenue" />
      <van-cell title="活动时间" :value="formatDate(order.eventStartTime)" />
    </van-cell-group>

    <!-- 票证信息 -->
    <van-cell-group title="票证信息" style="margin-top: 10px;">
      <van-cell title="票档" :value="order.ticketName" />
      <van-cell title="数量" :value="order.quantity + ' 张'" />
      <van-cell title="单价" :value="'¥' + order.ticketPrice" />
      <van-cell title="总价" :value="'¥' + order.totalPrice" />
      <van-cell title="订单编号" :value="order.orderNo" />
      <van-cell title="下单时间" :value="formatDate(order.createTime)" />
      <van-cell v-if="order.paidTime" title="支付时间" :value="formatDate(order.paidTime)" />
    </van-cell-group>

    <!-- 操作按钮 -->
    <div style="margin: 30px 16px;">
      <van-button v-if="order.status === -1" round block loading disabled>订单处理中，请稍候...</van-button>
      <van-button v-if="order.status === 0" round block type="primary" @click="handlePay" style="margin-bottom:10px">去支付</van-button>
      <van-button v-if="order.status === 0" round block @click="handleCancel">取消订单</van-button>
      <van-button v-if="order.status === 1" round block @click="handleRefund">申请退款</van-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useOrderStore } from '../stores/order'
import { showToast, showConfirmDialog } from 'vant'
import { getOrderDetail, cancelOrder, refundOrder } from '../api/order'
import { formatDate, orderStatusText, orderStatusColor } from '../utils/format'
const route = useRoute()
const router = useRouter()
const orderStore = useOrderStore()
const order = ref(null)

const statusBg = computed(() => {
  const map = { '-1': '#2196f3', 0: '#f57c00', 1: '#4caf50', 2: '#999', 3: '#f44336', 4: '#2196f3' }
  return map[order.value?.status] || '#999'
})

const statusIcon = computed(() => {
  const map = { '-1': 'clock-o', 0: 'clock-o', 1: 'paid', 2: 'info-o', 3: 'refund-o', 4: 'success' }
  return map[order.value?.status] || 'info-o'
})

onMounted(async () => {
  try {
    order.value = await getOrderDetail(route.params.orderNo)
  } catch {
    showToast('加载失败')
    router.back()
  }
})

async function handlePay() {
  orderStore.setCurrentOrder(order.value)
  router.push('/order/pay')
}

async function handleCancel() {
  showConfirmDialog({ title: '提示', message: '确定要取消此订单吗？' }).then(async () => {
    try {
      await cancelOrder(order.value.orderNo)
      showToast('已取消')
      order.value.status = 2
    } catch {}
  }).catch(() => {})
}

async function handleRefund() {
  showConfirmDialog({ title: '提示', message: '确定要申请退款吗？' }).then(async () => {
    try {
      await refundOrder(order.value.orderNo)
      showToast('退款成功')
      order.value.status = 3
    } catch {}
  }).catch(() => {})
}
</script>

<style scoped>
.status-bar { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px; color: #fff; }
.status-icon { margin-bottom: 8px; }
.status-text { font-size: 18px; font-weight: 600; }
</style>