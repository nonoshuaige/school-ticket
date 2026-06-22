import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
  { path: '/register', name: 'Register', component: () => import('../views/Register.vue') },

  { path: '/notes', name: 'Notes', component: () => import('../views/Notes.vue') },
  { path: '/note/:id', name: 'NoteDetail', component: () => import('../views/NoteDetail.vue') },
  { path: '/', name: 'EventList', component: () => import('../views/EventList.vue') },
  { path: '/event/:id', name: 'EventDetail', component: () => import('../views/EventDetail.vue') },

  { path: '/order/confirm', name: 'OrderConfirm', component: () => import('../views/OrderConfirm.vue'), meta: { requiresAuth: true } },
  { path: '/order/pay', name: 'OrderPay', component: () => import('../views/OrderPay.vue'), meta: { requiresAuth: true } },
  { path: '/order/result', name: 'OrderResult', component: () => import('../views/OrderResult.vue') },

  { path: '/mine', name: 'Mine', component: () => import('../views/Mine.vue') },
  { path: '/mine/orders', name: 'MyOrders', component: () => import('../views/MyOrders.vue'), meta: { requiresAuth: true } },
  { path: '/mine/notes', name: 'MyNotes', component: () => import('../views/MyNotes.vue'), meta: { requiresAuth: true } },
  { path: '/mine/detail/:orderNo', name: 'TicketDetail', component: () => import('../views/TicketDetail.vue'), meta: { requiresAuth: true } },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.requiresAuth && !localStorage.getItem('isLoggedIn')) {
    next('/login')
  } else {
    next()
  }
})

export default router