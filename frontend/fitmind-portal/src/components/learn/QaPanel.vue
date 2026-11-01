<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ChatDotRound } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { pageQuestions, pageReplies, saveQuestion, saveReply } from '@/api/social'
import type { Question, Reply } from '@/types/social'
import { LikeBizType } from '@/types/social'
import { formatDateTime, pageTotal } from '@/utils/format'
import { toggleLikeWithRollback } from '@/utils/like'
import { replyUserLabel, replyUserTag } from '@/utils/reply'

/**
 * 问答讨论区（M10-1 第 2 期）
 *
 * ============================================================
 * ★★ 模型：**一条评论 = 一个问题**
 * ============================================================
 * 直接复用 `interaction_question`（评论）+ `interaction_reply`（回复 / 楼中楼），
 * 不新建表。所以这里的"评论区"本质上就是"该动作下的问题列表 + 每条下面的回答"。
 *
 * ★ 为什么不"给每个动作建一条主问题当容器"：那样 `interaction_question` 的一行
 *   会被反复当成"评论区"复用，语义扭曲；而且"回复的回复"会凭空多出一层。
 *
 * ============================================================
 * ★★ 这里**没有、也永远不该有** AI 提问入口
 * ============================================================
 * 学习视频下面挂着**动作小测**。任何"能在题目旁边自由提问"的 AI 入口
 * 都等于把答案递到手上（M10-1 §1.1 的红线，用户提的）。
 * AI 只在 `/ai` 那个独立页 —— 那里看不到任何题目。
 * ⚠️ 改这个文件时请守住这条；验收脚本里有一条断言专门盯着它。
 *
 * ============================================================
 * ★ 几个后端语义（写错就产生脏数据，这里记一次）
 * ============================================================
 * · `saveReply` 的 `answerId`：**不传 = 顶层回答**；传某个顶层回答的 id = **对它的评论**
 *   （后端会顺手给那条顶层回答 `reply_times + 1`）。
 *   → 所以"回复某条楼中楼"时，`answerId` 仍然要填它**所属的顶层回答**，不是它自己。
 * · `targetReplyId` / `targetUserId` 只是"回复给谁看"，不参与层级。
 *
 * ============================================================
 * ★ 有意偏离方案的一处：默认**不匿名**
 * ============================================================
 * 方案原写"默认匿名"（健身问题偏私人）。实测改成**默认实名**：
 *   ① 用户要的正是"像 B 站那样**能看见头像**"—— 默认匿名就把头像藏了，与需求相反；
 *   ② 匿名该是**主动选择**（隐私动作），不是默认状态。
 * 勾选框保留，想匿名的随时勾。
 */
const props = defineProps<{
  courseId: string
  /** 当前动作所属训练日（`QuestionFormDTO.chapterId` 必填） */
  chapterId: string
  /** 当前动作；切换时评论区要跟着刷新 */
  sectionId?: string
}>()

/** 列表范围 */
const scope = ref<'section' | 'course'>('section')
const onlyMine = ref(false)

const loading = ref(false)
const questions = ref<Question[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 10

/** 提问草稿 */
const draft = ref('')
const anonymity = ref(false)
const submitting = ref(false)

/** 展开状态：questionId → 该问题下的回答 */
const expanded = ref<Record<string, boolean>>({})
const replies = ref<Record<string, Reply[]>>({})
const repliesLoading = ref<Record<string, boolean>>({})

/** 正在回复哪一条：`questionId` + 可选的顶层回答 id 与"回复给谁" */
const replyTarget = ref<{
  questionId: string
  answerId?: string
  targetReplyId?: string
  targetUserId?: string
  targetUserName?: string
} | null>(null)
const replyDraft = ref('')
const replySubmitting = ref(false)

const replyPlaceholder = computed(() =>
  replyTarget.value?.targetUserName
    ? `回复 ${replyTarget.value.targetUserName}…`
    : '写下你的回答…',
)

/**
 * 拉问题列表。
 *
 * ★ `onlyMine` 只在为真时才带 —— 后端是 `eq(Boolean.TRUE.equals(onlyMine), …)`，
 *   传 `false` 和 `undefined` 等价，但显式传 `false` 容易被后来人误读成"必须传"。
 */
async function load() {
  if (!props.courseId) return
  loading.value = true
  try {
    const res = await pageQuestions({
      pageNo: pageNo.value,
      pageSize,
      courseId: props.courseId,
      ...(scope.value === 'section' && props.sectionId ? { sectionId: props.sectionId } : {}),
      ...(onlyMine.value ? { onlyMine: true } : {}),
    })
    questions.value = res?.list ?? []
    total.value = pageTotal(res)
  } catch {
    questions.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.sectionId, () => {
  // 换动作 = 换讨论区：展开状态、回复框都要清掉（否则会把 A 动作的回答显示到 B 动作下）
  expanded.value = {}
  replies.value = {}
  replyTarget.value = null
  pageNo.value = 1
  void load()
})
watch([scope, onlyMine], () => {
  pageNo.value = 1
  void load()
})

/** 提问：只写正文，标题由服务端取前 20 字 */
async function submitQuestion() {
  const text = draft.value.trim()
  if (!text) {
    ElMessage.warning('说点什么吧')
    return
  }
  if (!props.sectionId) {
    ElMessage.warning('先选一个训练动作')
    return
  }
  submitting.value = true
  try {
    await saveQuestion({
      courseId: props.courseId,
      chapterId: props.chapterId,
      sectionId: props.sectionId,
      description: text,
      // ★ 不传 title：服务端会用 description 兜一个（M10-1 第 2 期新行为）
      anonymity: anonymity.value,
    })
    draft.value = ''
    ElMessage.success('已发布')
    pageNo.value = 1
    await load()
  } catch {
    /* request.ts 已提示 */
  } finally {
    submitting.value = false
  }
}

/** 展开 / 收起某条问题的回答 */
async function toggleExpand(q: Question) {
  const id = String(q.id)
  if (expanded.value[id]) {
    expanded.value[id] = false
    return
  }
  expanded.value[id] = true
  if (!replies.value[id]) {
    await loadReplies(id)
  }
}

async function loadReplies(questionId: string) {
  repliesLoading.value[questionId] = true
  try {
    const res = await pageReplies({ questionId, pageNo: 1, pageSize: 20 })
    // ★ 后端已是「点赞多的在前，再按时间」—— 评论区要的就是这个序，前端不要再排
    replies.value[questionId] = res?.list ?? []
  } catch {
    replies.value[questionId] = []
  } finally {
    repliesLoading.value[questionId] = false
  }
}

/** 开始回复：`reply` 为空 = 回答这个问题本身 */
function startReply(q: Question, reply?: Reply) {
  const id = String(q.id)
  expanded.value[id] = true
  if (!replies.value[id]) void loadReplies(id)

  if (!reply) {
    replyTarget.value = { questionId: id }
  } else {
    // ★ 楼中楼也要挂在**顶层回答**下：`reply.answerId` 为 '0' 说明它自己就是顶层
    const topId = !reply.answerId || reply.answerId === '0' ? reply.id : reply.answerId
    replyTarget.value = {
      questionId: id,
      answerId: topId,
      targetReplyId: reply.id,
      targetUserId: reply.userId,
      targetUserName: reply.userName ?? undefined,
    }
  }
  replyDraft.value = ''
}

function cancelReply() {
  replyTarget.value = null
  replyDraft.value = ''
}

async function submitReply() {
  const target = replyTarget.value
  if (!target) return
  const text = replyDraft.value.trim()
  if (!text) {
    ElMessage.warning('说点什么吧')
    return
  }
  replySubmitting.value = true
  try {
    await saveReply({
      questionId: target.questionId,
      ...(target.answerId ? { answerId: target.answerId } : {}),
      ...(target.targetReplyId ? { targetReplyId: target.targetReplyId } : {}),
      ...(target.targetUserId ? { targetUserId: target.targetUserId } : {}),
      content: text,
    })
    cancelReply()
    ElMessage.success('已回答')
    // 刷新这一条问题：回答数 / 最新回答都变了
    await loadReplies(target.questionId)
    await load()
  } catch {
    /* request.ts 已提示 */
  } finally {
    replySubmitting.value = false
  }
}

/** 给回答点赞（乐观更新 + 失败回滚，工具里已做） */
async function likeReply(reply: Reply) {
  try {
    await toggleLikeWithRollback(reply, LikeBizType.QA)
  } catch {
    /* 已回滚并提示 */
  }
}

/** 匿名问题后端不下发昵称，统一显示成"匿名用户" */
function displayName(q: Question): string {
  return q.anonymity ? '匿名用户' : (q.userName || '用户')
}
</script>

<template>
  <div class="qa-panel" data-testid="qa-panel">
    <!-- ==================== 提问 ==================== -->
    <div class="ask-box">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        maxlength="500"
        show-word-limit
        resize="none"
        :placeholder="sectionId ? '问这个动作的问题…（不用写标题）' : '先选一个训练动作'"
        :disabled="!sectionId"
        data-testid="qa-input"
      />
      <div class="ask-foot">
        <el-checkbox v-model="anonymity" data-testid="qa-anonymous">匿名提问</el-checkbox>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!sectionId"
          data-testid="qa-submit"
          @click="submitQuestion"
        >
          发布
        </el-button>
      </div>
    </div>

    <!-- ==================== 工具条 ==================== -->
    <div class="qa-toolbar">
      <el-radio-group v-model="scope" size="small" data-testid="qa-scope">
        <el-radio-button value="section">本动作</el-radio-button>
        <el-radio-button value="course">整门课</el-radio-button>
      </el-radio-group>
      <el-checkbox v-model="onlyMine" size="small" data-testid="qa-only-mine">只看我提问的</el-checkbox>
      <span class="qa-count" data-testid="qa-total">共 {{ total }} 个问题</span>
    </div>

    <!-- ==================== 列表 ==================== -->
    <el-empty
      v-if="!loading && !questions.length"
      :image-size="60"
      :description="
        onlyMine ? '你还没在这个范围里提过问题' : '这个范围还没有人提问，你可以问第一个'
      "
      data-testid="qa-empty"
    />

    <div v-else class="qa-list">
      <div v-for="q in questions" :key="q.id" class="qa-item" data-testid="qa-item">
        <el-avatar :size="34" :src="q.userIcon ?? undefined" class="qa-avatar">
          {{ displayName(q).slice(0, 1) }}
        </el-avatar>

        <div class="qa-main">
          <div class="qa-head">
            <span class="qa-name" data-testid="qa-name">{{ displayName(q) }}</span>
            <el-tag v-if="q.anonymity" type="info" size="small">匿名</el-tag>
            <span class="qa-time">{{ formatDateTime(q.createTime) }}</span>
          </div>

          <!-- ★ 正文用 description（完整），不是 title（被截到 20 字）—— 评论区要看全文 -->
          <p class="qa-content" data-testid="qa-content">{{ q.description || q.title }}</p>

          <div class="qa-foot">
            <el-button
              link
              type="primary"
              size="small"
              :icon="ChatDotRound"
              data-testid="qa-expand"
              @click="toggleExpand(q)"
            >
              {{ q.answerTimes ?? 0 }} 条回答
              <span class="caret">{{ expanded[String(q.id)] ? '▴' : '▾' }}</span>
            </el-button>
            <el-button link size="small" data-testid="qa-reply-btn" @click="startReply(q)">
              回复
            </el-button>
          </div>

          <!-- ==================== 回答（展开后） ==================== -->
          <div v-if="expanded[String(q.id)]" class="reply-list" data-testid="qa-reply-list">
            <p v-if="repliesLoading[String(q.id)]" class="reply-loading">加载回答…</p>

            <p v-else-if="!replies[String(q.id)]?.length" class="reply-empty" data-testid="qa-reply-empty">
              还没有人回答，来做第一个
            </p>

            <div
              v-for="r in replies[String(q.id)] ?? []"
              :key="r.id"
              class="reply-item"
              data-testid="qa-reply-item"
            >
              <el-avatar :size="24" :src="r.userIcon ?? undefined" class="reply-avatar">
                {{ (r.userName || '匿').slice(0, 1) }}
              </el-avatar>

              <div class="reply-main">
                <div class="reply-head">
                  <span class="reply-name">{{ r.userName || '匿名用户' }}</span>
                  <!-- ★ 身份徽章：管理员 / 教练 / AI 助手（userType 决定，见 utils/reply.ts） -->
                  <el-tag
                    :type="replyUserTag(r.userType)"
                    size="small"
                    effect="plain"
                    data-testid="qa-reply-usertype"
                  >
                    {{ replyUserLabel(r.userType) }}
                  </el-tag>
                  <span class="reply-time">{{ formatDateTime(r.createTime) }}</span>
                </div>

                <p class="reply-content" data-testid="qa-reply-content">
                  <span v-if="r.targetUserName" class="reply-to">回复 {{ r.targetUserName }}：</span>
                  {{ r.content }}
                </p>

                <div class="reply-foot">
                  <el-button
                    link
                    :type="r.liked ? 'primary' : 'default'"
                    size="small"
                    data-testid="qa-reply-like"
                    @click="likeReply(r)"
                  >
                    ♥ <span data-testid="qa-reply-likes">{{ r.likedTimes ?? 0 }}</span>
                  </el-button>
                  <el-button link size="small" data-testid="qa-reply-to" @click="startReply(q, r)">
                    回复
                  </el-button>
                </div>
              </div>
            </div>
          </div>

          <!-- ==================== 写回答 / 写回复 ==================== -->
          <div v-if="replyTarget?.questionId === String(q.id)" class="reply-box">
            <el-input
              v-model="replyDraft"
              size="small"
              :placeholder="replyPlaceholder"
              maxlength="500"
              data-testid="qa-reply-input"
              @keydown.enter.exact.prevent="submitReply"
            />
            <el-button
              type="primary"
              size="small"
              :loading="replySubmitting"
              data-testid="qa-reply-submit"
              @click="submitReply"
            >
              发送
            </el-button>
            <el-button size="small" @click="cancelReply">取消</el-button>
          </div>
        </div>
      </div>
    </div>

    <el-pagination
      v-if="total > pageSize"
      v-model:current-page="pageNo"
      class="qa-pager"
      layout="prev, pager, next"
      :page-size="pageSize"
      :total="total"
      data-testid="qa-pager"
      @current-change="load"
    />
  </div>
</template>

<style scoped>
.qa-panel {
  min-height: 120px;
}

/* ---- 提问 ---- */
.ask-box {
  margin-bottom: 12px;
}

.ask-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

/* ---- 工具条 ---- */
.qa-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.qa-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* ---- 一条评论 ---- */
.qa-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.qa-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.qa-avatar {
  flex: 0 0 auto;
}

.qa-main {
  flex: 1 1 auto;
  min-width: 0;
}

.qa-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.qa-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.qa-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.qa-content {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.qa-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
}

.caret {
  margin-left: 2px;
}

/* ---- 回答区：左侧一条竖线表示"从属于上面那条评论" ---- */
.reply-list {
  margin-top: 6px;
  padding-left: 10px;
  border-left: 2px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.reply-loading,
.reply-empty {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.reply-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.reply-avatar {
  flex: 0 0 auto;
}

.reply-main {
  flex: 1 1 auto;
  min-width: 0;
}

.reply-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.reply-name {
  font-size: 12px;
  font-weight: 600;
}

.reply-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.reply-content {
  margin: 2px 0 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* "回复 张三：" 用弱化色，避免和正文混在一起 */
.reply-to {
  color: var(--el-color-primary);
}

.reply-foot {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* ---- 回复输入 ---- */
.reply-box {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.qa-pager {
  margin-top: 12px;
  justify-content: center;
}
</style>
