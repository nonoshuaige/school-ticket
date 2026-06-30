<template>
  <div class="notes-page">
    <van-nav-bar title="笔记" fixed placeholder />
    <van-sticky offset-top="46">
      <div class="sort-tabs">
        <span :class="{ active: feedMode === 'recommend' }" @click="switchFeed('recommend')">推荐</span>
        <span :class="{ active: feedMode === 'following' }" @click="switchFeed('following')">关注</span>
      </div>
    </van-sticky>
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
              <div class="like-btn" :class="{ liked: item.isLiked }" @click.stop="toggleLike(item)">
                <van-icon :name="item.isLiked ? 'like' : 'like-o'" size="16" />
                <span>{{ item.likeCount || 0 }}</span>
              </div>
            </div>
            <div class="note-footer">
              <span class="note-time">{{ formatTime(item.createTime) }}</span>
              <van-button
                v-if="item.userId !== userStore.userInfo?.userId"
                size="mini"
                :type="item.isFollowing ? 'default' : 'primary'"
                :plain="item.isFollowing"
                round
                @click.stop="toggleFollow(item)"
                style="font-size:10px;min-width:44px;height:22px"
              >{{ item.isFollowing ? '已关注' : '+ 关注' }}</van-button>
            </div>
          </div>
        </div>

        <div v-if="notes.length === 0 && !loading" class="empty-state">
          <van-icon name="notes-o" size="48" color="#ccc"/>
          <p>{{ feedMode === 'following' ? '关注的人还没有发布笔记' : '暂无推荐笔记' }}</p>
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

    <div class="publish-btn" @click="showPublish = true">
      <van-icon name="edit" size="22" color="#fff" />
    </div>

    <van-dialog
      v-model:show="showPublish"
      title="发布笔记"
      show-cancel-button
      :before-close="beforePublish"
    >
      <div style="padding: 16px;">
        <van-field
          v-model="publishContent"
          type="textarea"
          placeholder="分享你的想法..."
          rows="4"
          maxlength="500"
          show-word-limit
        />
      </div>
    </van-dialog>

    <BottomNav />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { showToast, showSuccessToast } from 'vant'
import { getRecommendFeed, getFollowingFeed, createNote, likeNote, unlikeNote } from '../api/note'
import { followUser, unfollowUser, checkFollowing } from '../api/user'
import { useUserStore } from '../stores/user'
import BottomNav from '../components/BottomNav.vue'

const router = useRouter()
const userStore = useUserStore()
const notes = ref([])
const showPublish = ref(false)
const publishContent = ref('')
const loading = ref(false)
const refreshing = ref(false)
const loadingMore = ref(false)
const pageSize = 10
const feedMode = ref('recommend')
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

function switchFeed(mode) {
  if (mode === 'following' && !userStore.isLoggedIn) {
    showToast('请先登录')
    return
  }
  if (feedMode.value === mode) return
  feedMode.value = mode
  nextCursor.value = null
  notes.value = []
  loadNotes()
}

async function loadNotes() {
  loading.value = true
  try {
    let res
    if (feedMode.value === 'recommend') {
      res = await getRecommendFeed({ cursor: nextCursor.value, pageSize })
    } else {
      res = await getFollowingFeed({ cursor: nextCursor.value, pageSize })
    }
    const records = res.records || []

    // 批量查关注状态
    if (userStore.isLoggedIn) {
      for (const item of records) {
        if (item.userId === userStore.userInfo?.userId) {
          item.isFollowing = false
        } else {
          try {
            item.isFollowing = await checkFollowing(item.userId)
          } catch { item.isFollowing = false }
        }
        item._loaded = true
      }
    } else {
      records.forEach(r => { r.isFollowing = false; r._loaded = true })
    }

    notes.value = records
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
    let res
    if (feedMode.value === 'recommend') {
      res = await getRecommendFeed({ cursor: nextCursor.value, pageSize })
    } else {
      res = await getFollowingFeed({ cursor: nextCursor.value, pageSize })
    }
    const records = res.records || []

    if (userStore.isLoggedIn) {
      for (const item of records) {
        if (item.userId === userStore.userInfo?.userId) {
          item.isFollowing = false
        } else {
          try {
            item.isFollowing = await checkFollowing(item.userId)
          } catch { item.isFollowing = false }
        }
        item._loaded = true
      }
    } else {
      records.forEach(r => { r.isFollowing = false; r._loaded = true })
    }

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

async function toggleLike(item) {
  if (!userStore.isLoggedIn) {
    showToast('请先登录')
    return
  }
  try {
    if (item.isLiked) {
      await unlikeNote(item.noteId)
      item.isLiked = false
      item.likeCount = Math.max(0, (item.likeCount || 0) - 1)
    } else {
      await likeNote(item.noteId)
      item.isLiked = true
      item.likeCount = (item.likeCount || 0) + 1
    }
  } catch {}
}

function goDetail(noteId) {
  router.push(`/note/${noteId}`)
}

async function beforePublish(action) {
  if (action === 'cancel') return true
  if (!publishContent.value.trim()) {
    showToast('请输入内容')
    return false
  }
  try {
    await createNote(publishContent.value.trim())
    publishContent.value = ''
    showSuccessToast('发布成功')
    nextCursor.value = null
    await loadNotes()
    return true
  } catch {
    return false
  }
}

async function toggleFollow(item) {
  if (!userStore.isLoggedIn) {
    showToast('请先登录')
    return
  }
  try {
    if (item.isFollowing) {
      await unfollowUser(item.userId)
      item.isFollowing = false
      showToast('已取消关注')
    } else {
      await followUser(item.userId)
      item.isFollowing = true
      showToast('关注成功')
    }
  } catch {}
}

onMounted(() => { loadNotes() })
</script>

<style scoped>
.notes-page { padding-bottom: 60px; }
.sort-tabs {
  display: flex;
  background: #fff;
  border-bottom: 1px solid #eee;
  padding: 0 16px;
}
.sort-tabs span {
  flex: 1;
  text-align: center;
  padding: 12px 0;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  position: relative;
  transition: color 0.2s;
}
.sort-tabs span.active {
  color: #667eea;
  font-weight: 600;
}
.sort-tabs span.active::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 24px;
  height: 3px;
  background: #667eea;
  border-radius: 2px;
}
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
  margin-bottom: 6px;
}
.note-author { display: flex; align-items: center; gap: 4px; }
.author-avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
  flex-shrink: 0;
}
.author-name { font-size: 11px; color: #999; max-width: 60px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.like-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: #bbb;
  cursor: pointer;
  user-select: none;
  font-size: 12px;
}
.like-btn.liked { color: #ee0a24; }
.note-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid #f5f5f5;
  padding-top: 6px;
}
.note-time { font-size: 10px; color: #bbb; }
.empty-state { text-align: center; padding: 80px 0; }
.empty-state p { color: #999; margin-top: 8px; }
.loading-state { text-align: center; padding: 20px; }
.load-more-wrap { display: flex; justify-content: center; margin: 16px 0; }
.publish-btn {
  position: fixed;
  right: 20px;
  bottom: 100px;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
  z-index: 100;
  cursor: pointer;
}
</style>
