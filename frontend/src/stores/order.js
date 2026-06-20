import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useOrderStore = defineStore('order', () => {
  // 下单流程中暂存的数据
  const currentEvent = ref(null)
  const selectedTicket = ref(null)
  const quantity = ref(1)
  const currentOrder = ref(null)

  function setOrderData(event, ticket, qty) {
    currentEvent.value = event
    selectedTicket.value = ticket
    quantity.value = qty
  }

  function setCurrentOrder(order) {
    currentOrder.value = order
  }

  function clear() {
    currentEvent.value = null
    selectedTicket.value = null
    quantity.value = 1
    currentOrder.value = null
  }

  return { currentEvent, selectedTicket, quantity, currentOrder, setOrderData, setCurrentOrder, clear }
})
