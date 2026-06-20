<template>
  <div class="login-page">
    <div class="login-header">
      <h1>校园票务</h1>
      <p>登录后即可购买热门活动门票</p>
    </div>
    <van-form @submit="handleLogin">
      <van-cell-group inset>
        <van-field
          v-model="phone"
          name="phone"
          label="手机号"
          placeholder="请输入手机号"
          :rules="[{ required: true, message: '请输入手机号' }]"
        />
        <van-field
          v-model="password"
          type="password"
          name="password"
          label="密码"
          placeholder="请输入密码"
          :rules="[{ required: true, message: '请输入密码' }]"
        />
      </van-cell-group>
      <div style="margin: 16px">
        <van-button round block type="primary" native-type="submit" :loading="loading">
          登录
        </van-button>
      </div>
    </van-form>
    <div class="login-footer">
      <span @click="$router.push('/register')">没有账号？去注册</span>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { useUserStore } from '../stores/user'
import { login as loginApi } from '../api/auth'

const router = useRouter()
const userStore = useUserStore()

const phone = ref('')
const password = ref('')
const loading = ref(false)

async function handleLogin() {
  loading.value = true
  try {
    await userStore.login(phone.value, password.value)
    showToast('登录成功')
    router.replace('/')
  } catch (e) {
    // 错误已在 request 拦截器中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { padding: 60px 16px 0; }
.login-header { text-align: center; margin-bottom: 40px; }
.login-header h1 { font-size: 28px; color: #1989fa; margin-bottom: 8px; }
.login-header p { color: #999; font-size: 14px; }
.login-footer { text-align: center; margin-top: 16px; }
.login-footer span { color: #1989fa; font-size: 14px; cursor: pointer; }
</style>
