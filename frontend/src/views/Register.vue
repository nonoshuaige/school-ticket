<template>
  <div class="register-page">
    <div class="register-header">
      <h1>注册账号</h1>
      <p>创建一个新账号来购买活动门票</p>
    </div>
    <van-form @submit="handleRegister">
      <van-cell-group inset>
        <van-field
          v-model="phone"
          name="phone"
          label="手机号"
          placeholder="请输入手机号"
          :rules="[{ required: true, pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号' }]"
        />
        <van-field
          v-model="nickname"
          name="nickname"
          label="昵称"
          placeholder="请输入昵称（选填）"
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
          注册
        </van-button>
      </div>
    </van-form>
    <div class="register-footer">
      <span @click="$router.push('/login')">已有账号？去登录</span>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { register } from '../api/auth'

const router = useRouter()
const phone = ref('')
const nickname = ref('')
const password = ref('')
const loading = ref(false)

async function handleRegister() {
  loading.value = true
  try {
    await register({ phone: phone.value, password: password.value, nickname: nickname.value })
    showToast('注册成功')
    router.push('/login')
  } catch (e) {
    // 错误已在 request 拦截器中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page { padding: 60px 16px 0; }
.register-header { text-align: center; margin-bottom: 40px; }
.register-header h1 { font-size: 28px; color: #1989fa; margin-bottom: 8px; }
.register-header p { color: #999; font-size: 14px; }
.register-footer { text-align: center; margin-top: 16px; }
.register-footer span { color: #1989fa; font-size: 14px; cursor: pointer; }
</style>
