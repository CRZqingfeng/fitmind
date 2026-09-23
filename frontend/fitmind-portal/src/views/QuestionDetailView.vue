<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ArrowLeft, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { deleteQuestion, getQuestion, pageReplies, saveReply } from '@/api/social'
import type { Question, Reply } from '@/types/social'
import { LikeBizType, ReplyUserType } from '@/types/social'
import { formatDateTime, pageTotal } from '@/utils/format'
import { toggleLikeWithRollback } from '@/utils/like'

/**
 * 问题详情（M7-5）
 *
 * ★★ 关于点赞：`ReplyVO.liked` 是后端**实时**向 fm-remark 查出来的，
 *   所以页面**直接用它**渲染心形状态，不需要前端再猜、也不需要额外调接口。
 *   点赞动作本身在 fm-remark（`POST /rs/remarks/likes`），是"切换语义"。
 *
 * ⚠️ 这里最初的做法是"提交后重新拉列表"，但**体验是坏的**：
 *   服务端的 `likedTimes` 由 xxl-job 每 20 秒同步一次（最终一致），
 *   所以重拉拿到的是**旧计数** —— 心形变红了、数字纹丝不动，用户以为没点上。
 *   → 改成乐观更新（见 `utils/like.ts` 的完整推导）。
 */
const route = useRoute()
const router = useRouter()

const questionId = computed(() => String(route.params.id ?? ''))

const loading = ref(true)
const question = ref<Question | null>(null)
const replies = ref<Reply[]>([])
const replyTotal = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)

const replyVisible = ref(false)
const replySubmitting = ref(false)
const replyForm = reactive({ content: '', anonymity: false })

onMounted(async () => {
  await Promise.all([loadQuestion(), loadReplies()])
  loading.value = false
})

async function loadQuestion() {
  try {
    question.value = await getQuestion(questionId.value)
  } catch {
    question.value = null
  }
}

async function loadReplies() {
  try {
    const page = await pageReplies({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      questionId: questionId.value,
    })
    replies.value = page?.list ?? []
    replyTotal.value = pageTotal(page)
  } catch {
    replies.value = []
    replyTotal.value = 0
  }
}

function openReply() {
  replyForm.content = ''
  replyForm.anonymity = false
  replyVisible.value = true
}

async function submitReply() {
  if (!replyForm.content.trim()) {
    ElMessage.warning('请填写回答内容')
    return
  }
  replySubmitting.value = true
  try {
    await saveReply({
      questionId: questionId.value,
      content: replyForm.content.trim(),
      anonymity: replyForm.anonymity,
    })
    replyVisible.value = false
    ElMessage.success('回答已发布')
    // 回答数会变，问题信息（answerTimes / latestReply）也要跟着刷新
    await Promise.all([loadQuestion(), loadReplies()])
  } catch {
    /* 已提示 */
  } finally {
    replySubmitting.value = false
  }
}

/**
 * 点赞 / 取消赞（乐观更新，详见 `utils/like.ts` 里的完整推导）
 *
 * ★ 前端**只翻转 `liked`**，不在本地按"服务端口径"去猜状态 ——
 *   因为服务端的 `liked` 是实时的，本地翻转它和它一定一致。
 */
async function toggleLike(reply: Reply) {
  try {
    await toggleLikeWithRollback(reply, LikeBizType.QA)
  } catch {
    /* request.ts 已提示，rollback 已在 util 里完成 */
  }
}

async function removeReply(reply: Reply) {
  // 后端没有"删除单条回答"的接口（只有管理端隐藏），这里明确告诉用户而不是假装能删
  ElMessage.info(`回答 ${reply.id} 的删除只能由管理员操作（后端未开放学员自删）`)
}

async function removeQuestion() {
  if (!question.value) return
  try {
    await ElMessageBox.confirm('确定要删除这个问题吗？', '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteQuestion(questionId.value)
  ElMessage.success('已删除')
  router.push('/community')
}

function userTypeLabel(t: number | null | undefined): string {
  if (t === ReplyUserType.ADMIN) return '管理员'
  if (t === ReplyUserType.COACH) return '教练'
  return '学员'
}

function userTypeTag(t: number | null | undefined): 'danger' | 'success' | 'info' {
  if (t === ReplyUserType.ADMIN) return 'danger'
  if (t === ReplyUserType.COACH) return 'success'
  return 'info'
}
</script>

<template>
  <div v-loading="loading" class="question-page">
    <el-button class="back" text :icon="ArrowLeft" @click="router.push('/community')">
      返回社区
    </el-button>

    <el-empty v-if="!loading && !question" description="问题不存在或已删除" />

    <template v-if="question">
      <el-card shadow="never" class="block">
        <div class="q-head">
          <h2 class="q-title" data-testid="question-detail-title">{{ question.title }}</h2>
          <el-button v-if="!question.anonymity" link type="danger" :icon="Delete" @click="removeQuestion">
            删除
          </el-button>
        </div>
        <div v-if="question.description" class="q-desc">{{ question.description }}</div>
        <div class="q-meta">
          <span>{{ question.userName || '匿名用户' }}</span>
          <span class="dot">·</span>
          <span>{{ formatDateTime(question.createTime) }}</span>
          <span class="dot">·</span>
          <span data-testid="question-detail-answers">{{ question.answerTimes ?? 0 }} 个回答</span>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          <div class="block-header">
            <span class="block-title" data-testid="answer-count">全部回答（{{ replyTotal }}）</span>
            <el-button type="primary" size="small" data-testid="answer-open-btn" @click="openReply">
              写回答
            </el-button>
          </div>
        </template>

        <el-empty v-if="!replies.length" description="还没有人回答，来做第一个" :image-size="70" data-testid="answer-empty" />

        <div v-for="r in replies" :key="r.id" class="answer-item" data-testid="answer-item">
          <div class="a-head">
            <el-avatar :size="26" :src="r.userIcon ?? undefined">
              {{ (r.userName || '?').slice(0, 1) }}
            </el-avatar>
            <span class="a-name">{{ r.userName || '匿名用户' }}</span>
            <el-tag :type="userTypeTag(r.userType)" size="small" effect="plain">
              {{ userTypeLabel(r.userType) }}
            </el-tag>
            <el-tag v-if="r.anonymity" type="info" size="small">匿名</el-tag>
            <span class="a-time">{{ formatDateTime(r.createTime) }}</span>
          </div>

          <div class="a-content" data-testid="answer-content">{{ r.content }}</div>

          <div class="a-foot">
            <el-button
              link
              :type="r.liked ? 'primary' : 'default'"
              size="small"
              data-testid="answer-like-btn"
              @click="toggleLike(r)"
            >
              ♥ <span data-testid="answer-like-times">{{ r.likedTimes ?? 0 }}</span>
            </el-button>
            <span v-if="r.replyTimes" class="a-comment">{{ r.replyTimes }} 条评论</span>
            <el-button link type="danger" size="small" @click="removeReply(r)">删除</el-button>
          </div>
        </div>

        <el-pagination
          v-if="replyTotal > pageSize"
          v-model:current-page="pageNo"
          class="pager"
          layout="prev, pager, next"
          :page-size="pageSize"
          :total="replyTotal"
          @current-change="loadReplies"
        />
      </el-card>
    </template>

    <!-- 写回答 -->
    <el-dialog v-model="replyVisible" title="写回答" width="560px">
      <el-form label-position="top">
        <el-form-item label="回答内容" required>
          <el-input
            v-model="replyForm.content"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="结合你的经验说说看"
            data-testid="answer-content-input"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="replyForm.anonymity">匿名回答</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="replyVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="replySubmitting"
          data-testid="answer-submit"
          @click="submitReply"
        >
          发布
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.question-page {
  max-width: 860px;
  margin: 0 auto;
}

.back {
  margin-bottom: 12px;
}

.block {
  margin-bottom: 16px;
}

.q-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.q-title {
  margin: 0;
  font-size: 18px;
  line-height: 1.5;
}

.q-desc {
  margin-top: 12px;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.q-meta {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dot {
  color: var(--el-text-color-placeholder);
}

.block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.block-title {
  font-weight: 600;
}

.answer-item {
  padding: 14px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.answer-item:last-of-type {
  border-bottom: none;
}

.a-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.a-name {
  font-size: 13px;
  font-weight: 600;
}

.a-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.a-content {
  margin-top: 10px;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.a-foot {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.a-comment {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
