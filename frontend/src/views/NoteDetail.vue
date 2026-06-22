<template>
  <div class="detail-page">
    <van-nav-bar title="笔记详情" left-text="返回" left-arrow @click-left="$router.back()" fixed placeholder />

    <div v-if="loading" style="text-align:center;padding:60px 0">
      <van-loading size="24" />
    </div>

    <template v-else-if="note">
      <!-- 笔记卡片 -->
      <div class="note-detail-card">
        <div class="detail-cover" :style="{ background: coverGradient }">
          <span class="cover-emoji">{{ coverEmoji }}</span>
        </div>
        <div class="detail-body">
          <div class="detail-author">
            <div class="author-avatar">{{ (note.nickname || 'U')[0] }}</div>
            <span class="author-name">{{ note.nickname }}</span>
          </div>
          <div class="detail-content">{{ note.content }}</div>
          <div class="detail-footer">
            <span class="detail-time">{{ formatTime(note.createTime) }}</span>
            <div class="detail-like" :class="{ liked: note.isLiked }" @click="toggleLike">
              <van-icon :name="note.isLiked ? 'like' : 'like-o'" size="18" />
              <span>{{ note.likeCount || 0 }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 评论区域 -->
      <div class="comment-section">
        <div class="comment-section-header">
          <span>评论 ({{ totalComments }})</span>
        </div>

        <!-- 评论输入框 -->
        <div class="comment-input-box">
          <van-field
            v-model="commentText"
            placeholder="写下你的评论..."
            rows="1"
            autosize
            :disabled="!userStore.isLoggedIn"
          >
            <template #button>
              <van-button size="small" type="primary" :disabled="!commentText.trim()" @click="submitComment" round>发送</van-button>
            </template>
          </van-field>
          <div v-if="!userStore.isLoggedIn" class="login-hint" @click="$router.push('/login')">登录后即可评论</div>
        </div>

        <!-- 评论列表 -->
        <div v-if="comments.length > 0" class="comment-list">
          <div v-for="root in comments" :key="root.commentId" class="comment-item">
            <!-- 一级评论 -->
            <div class="comment-main">
              <div class="comment-avatar">{{ (root.nickname || 'U')[0] }}</div>
              <div class="comment-right">
                <div class="comment-top">
                  <span class="comment-name">{{ root.nickname }}</span>
                  <span class="comment-time">{{ formatTime(root.createTime) }}</span>
                  <span v-if="root.userId === userStore.userInfo?.userId" class="comment-delete" @click="doDelete(root.commentId)">删除</span>
                </div>
                <div class="comment-text">{{ root.content }}</div>
                <div class="comment-actions">
                  <span class="reply-btn" @click="startReply(root.commentId, root.commentId, root.userId, root.nickname)">回复</span>
                </div>
                <!-- 回复输入框 -->
                <div v-if="replyingTo === root.commentId" class="reply-input">
                  <van-field
                    v-model="replyText"
                    :placeholder="'回复 ' + replyToName + '...'"
                    autosize
                    rows="1"
                  >
                    <template #button>
                      <van-button size="mini" type="primary" :disabled="!replyText.trim()" @click="submitReply()" round>回复</van-button>
                    </template>
                  </van-field>
                </div>
              </div>
            </div>
            <!-- 子评论列表 -->
            <div v-if="root.children && root.children.length > 0" class="child-list">
              <div v-for="child in root.children" :key="child.commentId" class="child-item">
                <div class="comment-avatar small">{{ (child.nickname || 'U')[0] }}</div>
                <div class="comment-right">
                  <div class="comment-top">
                    <span class="comment-name">{{ child.nickname }}</span>
                    <span v-if="child.replyToNickname" class="reply-to"> 回复 {{ child.replyToNickname }}</span>
                    <span class="comment-time">{{ formatTime(child.createTime) }}</span>
                    <span v-if="child.userId === userStore.userInfo?.userId" class="comment-delete" @click="doDelete(child.commentId)">删除</span>
                  </div>
                  <div class="comment-text">{{ child.content }}</div>
                  <div class="comment-actions">
                    <span class="reply-btn" @click="startReply(root.commentId, child.commentId, child.userId, child.nickname)">回复</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-else-if="!loadingComments" class="empty-comments">
          <p>暂无评论，来说两句吧</p>
        </div>

        <div v-if="loadingComments" style="text-align:center;padding:20px">
          <van-loading size="20" />
        </div>

        <div v-if="commentPages > 1" class="pagination-wrap">
          <van-pagination
            v-model="commentPage"
            :page-count="commentPages"
            mode="simple"
            @change="loadComments"
          />
        </div>
      </div>
    </template>

    <div v-else class="empty-state">
      <p>笔记不存在</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { showToast, showConfirmDialog } from 'vant'
import { getNoteDetail, likeNote, unlikeNote, getComments, createComment, deleteComment } from '../api/note'
import { useUserStore } from '../stores/user'

const route = useRoute()
const userStore = useUserStore()
const noteId = Number(route.params.id)

const note = ref(null)
const loading = ref(true)
const comments = ref([])
const totalComments = ref(0)
const loadingComments = ref(false)
const commentPage = ref(1)
const commentPageSize = 10
const commentText = ref('')
const replyText = ref('')
const replyingTo = ref(null)
const parentCommentId = ref(null)
const replyToUid = ref(null)
const replyToName = ref('')

const commentPages = computed(() => Math.max(1, Math.ceil(totalComments.value / commentPageSize)))

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

const coverGradient = computed(() => gradients[noteId % gradients.length])
const coverEmoji = computed(() => emojis[noteId % emojis.length])

function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadNote() {
  loading.value = true
  try {
    const res = await getNoteDetail(noteId)
    note.value = res
  } catch {
    note.value = null
  } finally {
    loading.value = false
  }
}

async function loadComments() {
  loadingComments.value = true
  try {
    const res = await getComments(noteId, { page: commentPage.value, pageSize: commentPageSize })
    comments.value = res.records || []
    totalComments.value = res.total || 0
  } catch {
    comments.value = []
  } finally {
    loadingComments.value = false
  }
}

async function toggleLike() {
  if (!userStore.isLoggedIn) {
    showToast('请先登录')
    return
  }
  try {
    if (note.value.isLiked) {
      await unlikeNote(noteId)
      note.value.isLiked = false
      note.value.likeCount = Math.max(0, (note.value.likeCount || 0) - 1)
    } else {
      await likeNote(noteId)
      note.value.isLiked = true
      note.value.likeCount = (note.value.likeCount || 0) + 1
    }
  } catch {}
}

async function submitComment() {
  if (!commentText.value.trim()) return
  try {
    await createComment(noteId, { content: commentText.value.trim() })
    commentText.value = ''
    showToast('评论成功')
    commentPage.value = 1
    await loadComments()
  } catch {}
}

function startReply(rootCommentId, commentId, uid, name) {
  if (!userStore.isLoggedIn) {
    showToast('请先登录')
    return
  }
  replyingTo.value = rootCommentId
  parentCommentId.value = commentId
  replyToUid.value = uid
  replyToName.value = name
  replyText.value = ''
}

async function submitReply() {
  if (!replyText.value.trim()) return
  try {
    await createComment(noteId, {
      content: replyText.value.trim(),
      parentId: parentCommentId.value,
      replyToUid: replyToUid.value
    })
    replyText.value = ''
    replyingTo.value = null
    parentCommentId.value = null
    replyToName.value = ''
    replyToUid.value = null
    await loadComments()
  } catch {}
}

async function doDelete(commentId) {
  try {
    await showConfirmDialog({ title: '删除评论', message: '确定要删除这条评论吗？' })
  } catch {
    return
  }
  try {
    await deleteComment(noteId, commentId)
    showToast('已删除')
    await loadComments()
  } catch {}
}

onMounted(() => {
  loadNote()
  loadComments()
})
</script>

<style scoped>
.detail-page { padding-bottom: 30px; min-height: 100vh; background: #f7f8fa; }

.note-detail-card {
  margin: 8px 12px;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0,0,0,.04);
}
.detail-cover {
  height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cover-emoji { font-size: 64px; }
.detail-body { padding: 16px; }
.detail-author {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.author-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
}
.author-name { font-size: 14px; color: #333; font-weight: 500; }
.detail-content {
  font-size: 15px;
  color: #333;
  line-height: 1.7;
  margin-bottom: 12px;
}
.detail-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid #f5f5f5;
  padding-top: 10px;
}
.detail-time { font-size: 12px; color: #bbb; }
.detail-like {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #999;
  cursor: pointer;
  font-size: 14px;
}
.detail-like.liked { color: #ee0a24; }

.comment-section { margin: 0 12px; }
.comment-section-header {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  padding: 16px 0 10px;
}

.comment-input-box {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 12px;
}
.comment-input-box :deep(.van-field) {
  padding: 10px 12px;
}
.login-hint {
  text-align: center;
  font-size: 12px;
  color: #667eea;
  padding: 0 12px 10px;
  cursor: pointer;
}

.comment-list { background: #fff; border-radius: 8px; overflow: hidden; }
.comment-item {
  padding: 12px 12px 0;
  border-bottom: 1px solid #f5f5f5;
}
.comment-item:last-child { border-bottom: none; }

.comment-main {
  display: flex;
  gap: 8px;
}
.child-list {
  margin-left: 40px;
  padding-bottom: 4px;
}
.child-item {
  display: flex;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid #f9f9f9;
}
.child-item:first-child { border-top: none; }

.comment-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.comment-avatar.small { width: 26px; height: 26px; font-size: 11px; }
.comment-right { flex: 1; min-width: 0; }
.comment-top {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.comment-name { font-size: 13px; color: #333; font-weight: 500; }
.reply-to { font-size: 11px; color: #667eea; }
.comment-time { font-size: 11px; color: #bbb; }
.comment-delete { font-size: 11px; color: #999; margin-left: auto; cursor: pointer; }
.comment-text { font-size: 14px; color: #333; line-height: 1.5; margin-bottom: 4px; word-break: break-all; }
.comment-actions { margin-bottom: 4px; }
.reply-btn { font-size: 12px; color: #999; cursor: pointer; }

.reply-input { margin-top: 8px; }
.reply-input :deep(.van-field) {
  background: #f7f8fa;
  border-radius: 8px;
  padding: 8px 10px;
}

.empty-comments { text-align: center; padding: 40px 0; color: #bbb; font-size: 13px; }
.pagination-wrap { display: flex; justify-content: center; margin-top: 16px; }
.empty-state { text-align: center; padding: 80px 0; color: #999; }
</style>
