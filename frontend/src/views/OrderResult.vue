<template>
  <div class="result-page">
    <div class="result-icon">
      <van-icon name="success" color="#4caf50" size="64" />
    </div>
    <h2 class="result-title">支付成功</h2>
    <p class="result-desc">恭喜您，购票成功！请妥善保管电子票凭证。</p>

    <div class="action-buttons">
      <van-button round block type="primary" @click="$router.push('/mine')">
        查看我的票证
      </van-button>
      <van-button round plain block style="margin-top: 12px;" @click="$router.push('/')">
        返回首页
      </van-button>
    </div>
  </div>
</template>

<style scoped>
.result-page { text-align: center; padding: 80px 32px 0; }
.result-icon { margin-bottom: 16px; }
.result-title { font-size: 24px; margin-bottom: 8px; }
.result-desc { font-size: 14px; color: #666; margin-bottom: 40px; }
.action-buttons { max-width: 280px; margin: 0 auto; }
</style>
