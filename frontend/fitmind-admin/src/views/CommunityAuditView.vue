<script setup lang="ts">
import { Refresh, Search, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import {
  getNoteDetail,
  getNotePage,
  getQuestionDetail,
  getQuestionPage,
  getReplyDetail,
  getReplyPage,
  setNoteHidden,
  setQuestionHidden,
  setReplyHidden,
} from '@/api/community'
import type { NoteAdminDetailVO, NoteAdminVO, QuestionAdminVO, ReplyVO } from '@/types/admin'
import { QUESTION_ADMIN_STATUS_OPTIONS, REPLIER_TYPE } from '@/types/admin'
import { formatDateTime, pageTotal } from '@/utils/format'

/**
 * 社区内容审核（M7-12 第 3 期新增）
 *
 * ============================================================
 * ★★ 三个模块只有一种处置动作：**隐藏 / 折叠**（没有物理删除）
 * ============================================================
 * 这是刻意的设计，不是缺功能：审核要**可追溯、可撤销** ——
 * 真删掉一条违规内容，事后没人能证明它存在过，也没法复议。
 *
 * 隐藏的效果是"**学员端查不到**"，而管理端**照常能查到**
 * （`/admin/*` 分页的注释原话："含被隐藏的"）→ 藏了还能找回来。
 * 所以这一页是**唯一的"撤销"入口**：`hidden` 那一列要显眼。
 *
 * ============================================================
 * ★★ 三处"照后端语义来"的措辞与提示
 * ============================================================
 * 1. 问题 / 回答 叫**隐藏**，笔记叫**折叠** —— 后端就是这么叫的（`NoteAdminController`
 *    的 `@Operation` 原文"折叠 / 恢复笔记"）。统一改成"删除"会让运营以为内容没了。
 * 2. **隐藏顶层回答会连带隐藏它下面的所有评论**（后端行为）→ 确认框里必须写明。
 * 3. ★ **匿名内容在管理端会返回真实用户**（`QuestionAdminVO` 的注释：
 *    "匿名返回真实用户"）—— 这正是审核需要的能力，但界面上要**标明这是匿名内容**，
 *    免得运营把"匿名"当成"没填名字"。
 */
const activeTab = ref<'question' | 'reply' | 'note'>('question')
const loading = ref(false)
const acting = ref<string | null>(null)

// ==================== 三个列表（各自独立的分页与加载） ====================

const questionQuery = reactive({
  pageNo: 1,
  pageSize: 10,
  courseId: '',
  status: undefined as number | undefined,
})
const questions = ref<QuestionAdminVO[]>([])
const questionTotal = ref(0)

const replyQuery = reactive({ pageNo: 1, pageSize: 10, questionId: '' })
const replies = ref<ReplyVO[]>([])
const replyTotal = ref(0)

const noteQuery = reactive({
  pageNo: 1,
  pageSize: 10,
  courseId: '',
  hidden: undefined as boolean | undefined,
})
const notes = ref<NoteAdminVO[]>([])
const noteTotal = ref(0)

async function loadQuestions() {
  loading.value = true
  try {
    const page = await getQuestionPage({
      pageNo: questionQuery.pageNo,
      pageSize: questionQuery.pageSize,
      courseId: questionQuery.courseId || undefined,
      status: questionQuery.status,
    })
    questions.value = page?.list ?? []
    questionTotal.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

async function loadReplies() {
  loading.value = true
  try {
    const page = await getReplyPage({
      pageNo: replyQuery.pageNo,
      pageSize: replyQuery.pageSize,
      // ★ 这个接口的过滤维度就是"挂在哪个问题下"，不传等于查全部
      questionId: replyQuery.questionId || undefined,
    })
    replies.value = page?.list ?? []
    replyTotal.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

async function loadNotes() {
  loading.value = true
  try {
    const page = await getNotePage({
      pageNo: noteQuery.pageNo,
      pageSize: noteQuery.pageSize,
      courseId: noteQuery.courseId || undefined,
      hidden: noteQuery.hidden,
    })
    notes.value = page?.list ?? []
    noteTotal.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

/** 切 tab 时按需加载（不预加载三个列表 —— 审核一次只看一个模块） */
function handleTabChange() {
  if (activeTab.value === 'question' && !questions.value.length) loadQuestions()
  if (activeTab.value === 'reply' && !replies.value.length) loadReplies()
  if (activeTab.value === 'note' && !notes.value.length) loadNotes()
}

// ==================== 隐藏 / 恢复 ====================

async function toggleQuestion(row: QuestionAdminVO) {
  const next = !row.hidden
  const ok = await confirmToggle({
    action: next ? '隐藏' : '显示',
    what: `问题「${row.title}」`,
    effect: next
      ? '隐藏后，学员端的问题列表里**看不到**它（管理端仍可查到，随时可以恢复）。'
      : '显示后，学员端会重新看到它。',
  })
  if (!ok) return
  acting.value = row.id
  try {
    await setQuestionHidden(row.id, next)
    ElMessage.success(next ? '已隐藏' : '已显示')
    await loadQuestions()
  } catch {
    /* 已提示 */
  } finally {
    acting.value = null
  }
}

async function toggleReply(row: ReplyVO) {
  const next = !row.hidden
  const isTopLevel = !row.answerId || String(row.answerId) === '0'
  const ok = await confirmToggle({
    action: next ? '隐藏' : '显示',
    what: `来自${REPLIER_TYPE[row.userType ?? 2] ?? '用户'}的回答`,
    effect: next
      ? isTopLevel
        ? '★ 这是一条**顶层回答**：隐藏它会**连带隐藏它下面的所有评论**。管理端仍可查到，随时可以恢复。'
        : '隐藏后，学员端看不到这条评论。'
      : '显示后，学员端会重新看到它。',
  })
  if (!ok) return
  acting.value = row.id
  try {
    await setReplyHidden(row.id, next)
    ElMessage.success(next ? '已隐藏' : '已显示')
    await loadReplies()
  } catch {
    /* 已提示 */
  } finally {
    acting.value = null
  }
}

async function toggleNote(row: NoteAdminVO) {
  const next = !row.hidden
  const ok = await confirmToggle({
    action: next ? '折叠' : '恢复',
    what: `「${row.authorName ?? '匿名'}」的笔记`,
    effect: next
      ? '折叠后，学员端的笔记列表里看不到它（管理端仍可查到，随时可以恢复）。'
      : '恢复后，学员端会重新看到它。',
  })
  if (!ok) return
  acting.value = row.id
  try {
    await setNoteHidden(row.id, next)
    ElMessage.success(next ? '已折叠' : '已恢复')
    await loadNotes()
  } catch {
    /* 已提示 */
  } finally {
    acting.value = null
  }
}

async function confirmToggle(o: { action: string; what: string; effect: string }): Promise<boolean> {
  try {
    await ElMessageBox.confirm(`${o.what}\n\n${o.effect}`, `${o.action}确认`, {
      type: 'warning',
      confirmButtonText: o.action,
      cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
}

// ==================== 详情抽屉 ====================

const detail = reactive({
  visible: false,
  loading: false,
  /** 当前详情属于哪个模块 */
  kind: 'question' as 'question' | 'reply' | 'note',
  question: null as QuestionAdminVO | null,
  reply: null as ReplyVO | null,
  note: null as NoteAdminDetailVO | null,
})

/**
 * 打开详情。
 *
 * ★ 为什么要**单独拉详情**而不是直接用列表行：
 *   笔记详情的 `NoteAdminDetailVO` 比列表项多两个字段 ——
 *   **作者手机号**与**采集者名单**，而这两个恰恰是审核判断"要不要处置"的关键依据
 *   （谁发的、扩散给谁了）。列表给"是什么"，详情给"牵涉到谁"。
 */
async function openDetail(kind: 'question' | 'reply' | 'note', id: string) {
  detail.kind = kind
  detail.visible = true
  detail.loading = true
  try {
    if (kind === 'question') detail.question = await getQuestionDetail(id)
    else if (kind === 'reply') detail.reply = await getReplyDetail(id)
    else detail.note = await getNoteDetail(id)
  } catch {
    detail.visible = false
  } finally {
    detail.loading = false
  }
}

/** 内容摘要（列表里长文本只显示一行） */
const brief = (s: string | null | undefined, n = 48) => {
  const t = (s ?? '').replace(/\s+/g, ' ').trim()
  return t.length > n ? `${t.slice(0, n)}…` : t || '—'
}

onMounted(loadQuestions)
</script>

<template>
  <div class="fm-page community-audit">
    <el-card shadow="never" class="filter-card">
      <div class="hint-line">
        <p>
          <strong>处置动作只有"隐藏 / 折叠"，没有删除</strong> —— 审核要
          <strong>可追溯、可撤销</strong>。隐藏后<strong>学员端看不到</strong>，
          而<strong>管理端始终能查到</strong>（所以这一页也是唯一的"恢复"入口）。
        </p>
        <p>
          ★ 匿名内容在这里会显示<strong>真实用户</strong>（审核需要）；
          <strong>隐藏顶层回答会连带隐藏它下面所有评论</strong>。
        </p>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeTab" data-testid="audit-tabs" @tab-change="handleTabChange">
        <!-- ==================== 问题 ==================== -->
        <el-tab-pane label="问题" name="question">
          <div class="filter-row">
            <el-input
              v-model="questionQuery.courseId"
              placeholder="课程 id（可选）"
              clearable
              style="width: 180px"
              data-testid="audit-question-course"
              @keyup.enter="questionQuery.pageNo = 1; loadQuestions()"
            />
            <el-select
              v-model="questionQuery.status"
              placeholder="查看状态"
              clearable
              style="width: 140px"
              data-testid="audit-question-status"
            >
              <el-option
                v-for="s in QUESTION_ADMIN_STATUS_OPTIONS"
                :key="s.value"
                :label="s.label"
                :value="s.value"
              />
            </el-select>
            <el-button
              type="primary"
              :icon="Search"
              data-testid="audit-question-search"
              @click="questionQuery.pageNo = 1; loadQuestions()"
            >
              查询
            </el-button>
            <el-button :icon="Refresh" data-testid="audit-question-refresh" @click="loadQuestions">刷新</el-button>
            <span class="total-hint" data-testid="audit-question-total">共 {{ questionTotal }} 条</span>
          </div>

          <el-table v-loading="loading" :data="questions" data-testid="audit-question-table">
            <el-table-column label="标题 / 描述" min-width="280">
              <template #default="{ row }">
                <div class="title-line">
                  <span class="title" :data-testid="`audit-question-title-${row.id}`">{{ row.title }}</span>
                  <el-tag v-if="row.anonymity" size="small" type="warning">匿名</el-tag>
                  <el-tag v-if="row.hidden" size="small" type="danger" data-testid="audit-question-hidden-tag">
                    已隐藏
                  </el-tag>
                </div>
                <div class="sub">{{ brief(row.description, 60) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="课程 / 动作" min-width="180">
              <template #default="{ row }">
                <div class="sub">{{ row.courseName ?? '—' }}</div>
                <div class="sub">{{ row.sectionName ?? row.chapterName ?? '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="提问者" width="140">
              <template #default="{ row }">
                <div>{{ row.userName ?? '—' }}</div>
                <div class="sub">id {{ row.userId ?? '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="回答数" width="90" prop="answerTimes" />
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :icon="View"
                  :data-testid="`audit-question-detail-${row.id}`"
                  @click="openDetail('question', row.id)"
                >
                  详情
                </el-button>
                <el-button
                  link
                  :type="row.hidden ? 'success' : 'danger'"
                  :loading="acting === row.id"
                  :data-testid="`audit-question-toggle-${row.id}`"
                  @click="toggleQuestion(row as QuestionAdminVO)"
                >
                  {{ row.hidden ? '显示' : '隐藏' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="questionTotal > questionQuery.pageSize"
            v-model:current-page="questionQuery.pageNo"
            class="pager"
            layout="prev, pager, next"
            :page-size="questionQuery.pageSize"
            :total="questionTotal"
            data-testid="audit-question-pager"
            @current-change="loadQuestions"
          />
        </el-tab-pane>

        <!-- ==================== 回答 ==================== -->
        <el-tab-pane label="回答 / 评论" name="reply">
          <div class="filter-row">
            <el-input
              v-model="replyQuery.questionId"
              placeholder="问题 id（可筛选某问题下的回答）"
              clearable
              style="width: 260px"
              data-testid="audit-reply-question"
              @keyup.enter="replyQuery.pageNo = 1; loadReplies()"
            />
            <el-button
              type="primary"
              :icon="Search"
              data-testid="audit-reply-search"
              @click="replyQuery.pageNo = 1; loadReplies()"
            >
              查询
            </el-button>
            <el-button :icon="Refresh" data-testid="audit-reply-refresh" @click="loadReplies">刷新</el-button>
            <span class="total-hint" data-testid="audit-reply-total">共 {{ replyTotal }} 条</span>
          </div>

          <el-table v-loading="loading" :data="replies" data-testid="audit-reply-table">
            <el-table-column label="内容" min-width="300">
              <template #default="{ row }">
                <div class="title-line">
                  <span :data-testid="`audit-reply-content-${row.id}`">{{ brief(row.content, 70) }}</span>
                  <el-tag v-if="row.anonymity" size="small" type="warning">匿名</el-tag>
                  <el-tag v-if="row.hidden" size="small" type="danger">已隐藏</el-tag>
                </div>
                <div v-if="row.targetUserName" class="sub">回复 {{ row.targetUserName }}</div>
              </template>
            </el-table-column>
            <el-table-column label="层级" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="!row.answerId || String(row.answerId) === '0' ? 'primary' : 'info'">
                  {{ !row.answerId || String(row.answerId) === '0' ? '顶层回答' : '评论' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="回答者" width="140">
              <template #default="{ row }">
                <div>{{ row.userName ?? '匿名' }}</div>
                <div class="sub">{{ REPLIER_TYPE[row.userType ?? 2] ?? '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="问题 id" width="120" prop="questionId" />
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :icon="View"
                  :data-testid="`audit-reply-detail-${row.id}`"
                  @click="openDetail('reply', row.id)"
                >
                  详情
                </el-button>
                <el-button
                  link
                  :type="row.hidden ? 'success' : 'danger'"
                  :loading="acting === row.id"
                  :data-testid="`audit-reply-toggle-${row.id}`"
                  @click="toggleReply(row as ReplyVO)"
                >
                  {{ row.hidden ? '显示' : '隐藏' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="replyTotal > replyQuery.pageSize"
            v-model:current-page="replyQuery.pageNo"
            class="pager"
            layout="prev, pager, next"
            :page-size="replyQuery.pageSize"
            :total="replyTotal"
            data-testid="audit-reply-pager"
            @current-change="loadReplies"
          />
        </el-tab-pane>

        <!-- ==================== 笔记 ==================== -->
        <el-tab-pane label="笔记" name="note">
          <div class="filter-row">
            <el-input
              v-model="noteQuery.courseId"
              placeholder="课程 id（可选）"
              clearable
              style="width: 180px"
              data-testid="audit-note-course"
              @keyup.enter="noteQuery.pageNo = 1; loadNotes()"
            />
            <el-select
              v-model="noteQuery.hidden"
              placeholder="折叠状态"
              clearable
              style="width: 140px"
              data-testid="audit-note-hidden"
            >
              <el-option label="正常" :value="false" />
              <el-option label="已折叠" :value="true" />
            </el-select>
            <el-button
              type="primary"
              :icon="Search"
              data-testid="audit-note-search"
              @click="noteQuery.pageNo = 1; loadNotes()"
            >
              查询
            </el-button>
            <el-button :icon="Refresh" data-testid="audit-note-refresh" @click="loadNotes">刷新</el-button>
            <span class="total-hint" data-testid="audit-note-total">共 {{ noteTotal }} 条</span>
          </div>

          <el-table v-loading="loading" :data="notes" data-testid="audit-note-table">
            <el-table-column label="内容" min-width="300">
              <template #default="{ row }">
                <div class="title-line">
                  <span :data-testid="`audit-note-content-${row.id}`">{{ brief(row.content, 70) }}</span>
                  <el-tag v-if="row.isPrivate" size="small" type="info">私密</el-tag>
                  <el-tag v-if="row.hidden" size="small" type="danger">已折叠</el-tag>
                </div>
                <div v-if="row.hiddenReason" class="sub">折叠原因：{{ row.hiddenReason }}</div>
              </template>
            </el-table-column>
            <el-table-column label="作者" width="130">
              <template #default="{ row }">
                <div>{{ row.authorName ?? '—' }}</div>
                <div class="sub">id {{ row.authorId ?? '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="课程" min-width="150">
              <template #default="{ row }">
                <div class="sub">{{ row.courseName ?? '—' }}</div>
                <div class="sub">{{ row.sectionName ?? '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="被采集" width="90">
              <template #default="{ row }">
                <span :data-testid="`audit-note-used-${row.id}`">{{ row.usedTimes ?? 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="点赞" width="80" prop="likedTimes" />
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :icon="View"
                  :data-testid="`audit-note-detail-${row.id}`"
                  @click="openDetail('note', row.id)"
                >
                  详情
                </el-button>
                <el-button
                  link
                  :type="row.hidden ? 'success' : 'danger'"
                  :loading="acting === row.id"
                  :data-testid="`audit-note-toggle-${row.id}`"
                  @click="toggleNote(row as NoteAdminVO)"
                >
                  {{ row.hidden ? '恢复' : '折叠' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="noteTotal > noteQuery.pageSize"
            v-model:current-page="noteQuery.pageNo"
            class="pager"
            layout="prev, pager, next"
            :page-size="noteQuery.pageSize"
            :total="noteTotal"
            data-testid="audit-note-pager"
            @current-change="loadNotes"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ==================== 详情抽屉 ==================== -->
    <el-drawer v-model="detail.visible" title="审核详情" size="560px" data-testid="audit-detail-drawer">
      <div v-loading="detail.loading">
        <!-- 问题 -->
        <template v-if="detail.kind === 'question' && detail.question">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="问题 id">
              <span data-testid="audit-detail-id">{{ detail.question.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="标题">{{ detail.question.title }}</el-descriptions-item>
            <el-descriptions-item label="描述">{{ detail.question.description ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="课程 / 动作">
              {{ detail.question.courseName ?? '—' }} / {{ detail.question.sectionName ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="提问者">
              <span data-testid="audit-detail-user">{{ detail.question.userName ?? '—' }}</span>
              （id {{ detail.question.userId ?? '—' }}）
              <el-tag v-if="detail.question.anonymity" size="small" type="warning" class="ml">
                学员端显示为匿名
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="回答数">{{ detail.question.answerTimes ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="当前状态">
              <el-tag :type="detail.question.hidden ? 'danger' : 'success'" size="small">
                {{ detail.question.hidden ? '已隐藏（学员端不可见）' : '正常（学员端可见）' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="提问时间">
              {{ formatDateTime(detail.question.createTime) }}
            </el-descriptions-item>
          </el-descriptions>
        </template>

        <!-- 回答 -->
        <template v-else-if="detail.kind === 'reply' && detail.reply">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="回答 id">
              <span data-testid="audit-detail-id">{{ detail.reply.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="所属问题">{{ detail.reply.questionId ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="楼层">
              {{ !detail.reply.answerId || String(detail.reply.answerId) === '0' ? '顶层回答' : `评论（挂在回答 ${detail.reply.answerId}）` }}
            </el-descriptions-item>
            <el-descriptions-item label="内容">{{ detail.reply.content ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="回答者">
              <span data-testid="audit-detail-user">{{ detail.reply.userName ?? '匿名' }}</span>
              （{{ REPLIER_TYPE[detail.reply.userType ?? 2] ?? '—' }}）
            </el-descriptions-item>
            <el-descriptions-item label="回复对象">{{ detail.reply.targetUserName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="当前状态">
              <el-tag :type="detail.reply.hidden ? 'danger' : 'success'" size="small">
                {{ detail.reply.hidden ? '已隐藏（学员端不可见）' : '正常（学员端可见）' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="时间">{{ formatDateTime(detail.reply.createTime) }}</el-descriptions-item>
          </el-descriptions>
        </template>

        <!-- 笔记（多两个只有审核该看到的字段） -->
        <template v-else-if="detail.kind === 'note' && detail.note">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="笔记 id">
              <span data-testid="audit-detail-id">{{ detail.note.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="内容">{{ detail.note.content ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="作者">
              <span data-testid="audit-detail-user">{{ detail.note.authorName ?? '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="作者手机号">
              <!-- ★ 列表里没有这个字段，只有管理端详情才给 -->
              <span data-testid="audit-detail-phone">{{ detail.note.authorPhone ?? '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="被采集次数">
              {{ detail.note.usedTimes ?? 0 }}
            </el-descriptions-item>
            <el-descriptions-item label="采集者名单">
              <!-- ★ 同上：谁把这条笔记收藏走了，只有审核看得到 -->
              <span data-testid="audit-detail-gathers">
                {{ (detail.note.gathers ?? []).length ? (detail.note.gathers ?? []).join('、') : '（还没有人采集）' }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="课程 / 动作">
              {{ detail.note.courseName ?? '—' }} / {{ detail.note.sectionName ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="当前状态">
              <el-tag :type="detail.note.hidden ? 'danger' : 'success'" size="small">
                {{ detail.note.hidden ? '已折叠（学员端不可见）' : '正常（学员端可见）' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(detail.note.createTime) }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.total-hint {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hint-line {
  font-size: 12px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
}

.hint-line p {
  margin: 0;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.title {
  font-weight: 500;
}

.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.pager {
  margin-top: 16px;
  justify-content: center;
}

.ml {
  margin-left: 6px;
}
</style>
