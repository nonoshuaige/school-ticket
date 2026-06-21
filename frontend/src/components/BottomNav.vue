<template>
  <van-tabbar v-model="active" route>
    <van-tabbar-item to="/" icon="shop-o">购票</van-tabbar-item>
    <van-tabbar-item to="/notes" icon="notes-o">笔记</van-tabbar-item>
    <van-tabbar-item to="/mine" icon="contact-o">我的</van-tabbar-item>
  </van-tabbar>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const active = ref(route.path === '/notes' ? 1 : route.path === '/mine' ? 2 : 0)
</script>
