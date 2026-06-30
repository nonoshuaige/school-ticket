<template>
  <div class="my-notes-page">
    <van-nav-bar title="我的笔记" left-text="返回" left-arrow fixed placeholder @click-left="router.back" />
    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <div class="note-grid">
        <div v-for="item in notes" :key="item.noteId" class="note-card" @click="goDetail(item.noteId)">
          <div class="note-cover" :style="{ background: coverGradient(item.noteId) }">
            <span class="cover-emoji">{{ coverEmoji(item.noteId) }}</span>
            <span v-if="item.eventIds && item.eventIds.length > 0" class="zhongcao-badge">🛍️ 种草</span>
          </div>
          <div class="note-body">
            <div class="note-content">{{ item.content }}</div>
            <div class="note-meta">
              <div class="note-author">
                <div class="author-avatar">{{ (item.nickname || 'U')[0] }}</div>
                <span class="author-name">{{ item.nickname }}</span>
              </div>
              <div class="like-count">
                <van-icon name="like-o" size="14" />
                <span>{{ item.likeCount || 0 }}</span>
              </div>
            </div>
            <div class="note-time">{{ formatTime(item.createTime) }}</div>
          </div>
        </div>

        <div v-if="notes.length === 0 && !loading" class="empty-state">
          <van-icon name="notes-o" size="48" color="#ccc"/>
          <p>你还没有发布过笔记</p>
        </div>
      </div>
    </van-pull-refresh>

    <div v-if="loading" class="loading-state">
      <van-loading size="24" />
    </div>

    <div v-if="!loading && hasMore" class="load-more-wrap">
      <van-button round plain type="primary" size="small" :loading="loadingMore" @click="loadMore">
        加载更多
      </van-button>
    </div>

    <BottomNav />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMyNotes } from '../api/note'
import BottomNav from '../components/BottomNav.vue'

const router = useRouter()
const notes = ref([])
const loading = ref(false)
const refreshing = ref(false)
const loadingMore = ref(false)
const pageSize = 10
const nextCursor = ref(null)
const hasMore = ref(false)

const gradients = [
  'linear-gradient(135deg, #667eea, #764ba2)',
  'linear-gradient(135deg, #f093fb, #f5576c)',
  'linear-gradient(135deg, #4facfe, #00f2fe)',
  'linear-gradient(135deg, #43e97b, #38f9d7)',
  'linear-gradient(135deg, #fa709a, #fee140)',
  'linear-gradient(135deg, #a18cd1, #fbc2eb)',
  'linear-gradient(135deg, #fccb90, #d57eeb)',
  'linear-gradient(135deg, #ffecd2, #fcb69f)',
]
const emojis = ['🌸','🎵','📖','🍔','🏃','📷','🎮','✨','🌈','🎨','🍕','🎬','📝','💡','🌟','🔥']

function coverGradient(id) { return gradients[id % gradients.length] }
function coverEmoji(id) { return emojis[id % emojis.length] }

function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadNotes() {
  loading.value = true
  try {
    const res = await getMyNotes({ cursor: nextCursor.value, pageSize })
    notes.value = res.records || []
    nextCursor.value = res.nextCursor || null
    hasMore.value = res.hasMore || false
  } catch {} finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const res = await getMyNotes({ cursor: nextCursor.value, pageSize })
    const records = res.records || []
    notes.value.push(...records)
    nextCursor.value = res.nextCursor || null
    hasMore.value = res.hasMore || false
  } catch {} finally {
    loadingMore.value = false
  }
}

async function onRefresh() {
  refreshing.value = true
  nextCursor.value = null
  await loadNotes()
  refreshing.value = false
}

function goDetail(noteId) {
  router.push(`/note/${noteId}`)
}

onMounted(() => { loadNotes() })
</script>

<style scoped>
.my-notes-page { padding-bottom: 60px; }
.note-grid {
  padding: 0 8px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 8px;
}
.note-card {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.note-cover {
  height: 100px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}
.cover-emoji { font-size: 40px; }
.zhongcao-badge {
  position: absolute;
  top: 4px;
  right: 4px;
  background: rgba(255, 107, 53, 0.9);
  color: #fff;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
  white-space: nowrap;
}
.note-body { padding: 8px 10px; }
.note-content {
  font-size: 12px;
  color: #333;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-bottom: 8px;
  min-height: 36px;
}
.note-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.note-author { display: flex; align-items: center; gap: 4px; }
.author-avatar {
  width: 20px; height: 20px; border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 10px; font-weight: 600; flex-shrink: 0;
}
.author-name { font-size: 11px; color: #999; max-width: 60px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.like-count { display: flex; align-items: center; gap: 2px; color: #bbb; font-size: 12px; }
.note-time { font-size: 10px; color: #bbb; border-top: 1px solid #f5f5f5; padding-top: 6px; }
.empty-state { text-align: center; padding: 80px 0; }
.empty-state p { color: #999; margin-top: 8px; }
.loading-state { text-align: center; padding: 20px; }
.load-more-wrap { display: flex; justify-content: center; margin: 16px 0; }
</style>
