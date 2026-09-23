<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'

import { ChatDotRound, Delete, EditPen, Plus, Star, StarFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { getCourseCatalogues } from '@/api/course'
import { pageMyLessons } from '@/api/learning'
import {
  deleteNote,
  deleteQuestion,
  gatherNote,
  pageNotes,
  pageQuestions,
  removeGatherNote,
  saveNote,
  saveQuestion,
} from '@/api/social'
import type { CataVO } from '@/types/course'
import type { LearningLesson } from '@/types/learning'
import type { Note, Question } from '@/types/social'
import { LikeBizType } from '@/types/social'
import { formatDateTime, formatDuration, pageTotal } from '@/utils/format'
import { toggleLikeWithRollback } from '@/utils/like'

/**
 * 社区（M7-5）：问答 + 笔记
 *
 * ============================================================
 * ★★ 这个页面的一切都由一条后端契约决定：**列表必须带"归属上下文"**
 * ============================================================
 *
 *   实测（`GET /ls/questions/page` 不带任何筛选）：
 *     → `{"code":400,"msg":"课程id和训练动作id不能都为空"}`
 *   笔记接口完全一样，回答接口则是"问题id和回答id不能都为空"。
 *
 *   也就是说：**这个社区没有"全部问答"这种列表** —— 问答和笔记都必须
 *   挂在「课程」或「训练动作」下（这和后端把 `courseId/chapterId/sectionId`
 *   设为提问必填是同一个设计意图：它不是论坛，是"动作级的答疑")。
 *
 *   所以页面结构必须是"**先选课程/动作，再看内容**"。
 *   第一版把它做成了"直接列出全部"，结果接口一直 400、页面一直显示 0 条 ——
 *   而**验收脚本也显示 0 == 0 通过了**，属于典型的"假绿"。
 *
 * ============================================================
 * ★★ 另一个坑：点赞是"切换语义"且跨服务，而且**有两套一致性**
 * ============================================================
 *   `POST /rs/remarks/likes` 用 `liked: true/false` 承担点赞与取消，落在 fm-remark。
 *   · `liked`        —— 立刻一致（返回列表时实时查 Redis SET）
 *   · `likedTimes`   —— 最终一致，最长 20 秒（xxl-job `likedTimesSyncJob` 每 20 秒同步）
 *
 *   ⚠️ 最初写成"提交后重新拉列表"，结果是**心形变红、数字不动**（重拉拿到的是旧计数）。
 *   → 改成乐观更新（`utils/like.ts`）：本地按"本次点击的方向"先加减，
 *     因为 `liked` 与服务端同步翻转，连点两下本地净 0、服务端也净 0，不会出现 +2。
 */
const route = useRoute()
const router = useRouter()

const activeTab = ref<'qa' | 'note'>('qa')
const loading = ref(false)

/** 我的课程（作为社区内容的上下文来源） */
const myLessons = ref<LearningLesson[]>([])
const lessonsLoading = ref(true)
/** 当前选中的课程（★ 必须有值，否则列表接口会 400） */
const selectedCourseId = ref('')
/** 当前选中的训练动作（可选；选了就按动作过滤，不选就按课程过滤） */
const selectedSectionId = ref('')
const catalogs = ref<CataVO[]>([])

const allSections = computed(() => catalogs.value.flatMap((d) => d.sections ?? []))

/** URL 上带的上下文（从学习页/课程详情跳过来） */
const ctxCourseId = computed(() => (route.query.courseId ? String(route.query.courseId) : ''))
const ctxSectionId = computed(() => (route.query.sectionId ? String(route.query.sectionId) : ''))

// ==================== 问答 ====================
const questions = ref<Question[]>([])
const questionTotal = ref(0)
const qPageNo = ref(1)
const qPageSize = ref(10)
const onlyMineQ = ref(false)

const askVisible = ref(false)
const askSubmitting = ref(false)
const askForm = reactive({
  courseId: '',
  chapterId: '',
  sectionId: '',
  title: '',
  description: '',
  anonymity: false,
})
const chaptersOfCourse = computed(() => catalogs.value.filter((c) => c.type === 1))
const sectionsOfChapter = computed(
  () => chaptersOfCourse.value.find((c) => c.id === askForm.chapterId)?.sections ?? [],
)

// ==================== 笔记 ====================
const notes = ref<Note[]>([])
const noteTotal = ref(0)
const nPageNo = ref(1)
const nPageSize = ref(10)
const onlyMineN = ref(false)

const noteVisible = ref(false)
const noteSubmitting = ref(false)
/**
 * ★ 笔记的必填项是**整套**（实测 `NoteFormDTO`）：
 *   `courseId` / `chapterId` / `sectionId` / `noteMoment` / `content` / `isPrivate`
 *   全都是 `@NotNull` / `@NotBlank` —— 漏一个就是 400
 *   （日志里见过 "训练日id不能为空"、"笔记时间点不能为空"）。
 *   所以笔记弹窗也要选训练日 + 动作，而且**时间点不能为空**（默认给 0）。
 */
const noteForm = reactive({
  chapterId: '',
  sectionId: '',
  noteMoment: 0,
  content: '',
  isPrivate: false,
})
const noteSectionsOfChapter = computed(
  () => chaptersOfCourse.value.find((c) => c.id === noteForm.chapterId)?.sections ?? [],
)

/** 列表接口共用的上下文参数：★ 只传一个（选了动作就按动作，否则按课程） */
function contextParams() {
  return selectedSectionId.value
    ? { sectionId: selectedSectionId.value }
    : { courseId: selectedCourseId.value }
}

const hasContext = computed(() => Boolean(selectedCourseId.value))

onMounted(async () => {
  await loadMyLessons()
})

/** 换上下文 → 两个列表都要重新拉（数据是按上下文查的） */
watch([selectedCourseId, selectedSectionId], async ([cid], [oldCid]) => {
  if (cid !== oldCid) {
    await loadCatalogs(cid)
  }
  qPageNo.value = 1
  nPageNo.value = 1
  await Promise.all([loadQuestions(), loadNotes()])
})

async function loadMyLessons() {
  lessonsLoading.value = true
  try {
    myLessons.value = (await pageMyLessons({ pageNo: 1, pageSize: 100 }))?.list ?? []
    // 优先用 URL 带来的课程；否则用我的课程第一门（这样页面一进来就有内容看）
    const preferred =
      (ctxCourseId.value && myLessons.value.some((l) => String(l.courseId) === ctxCourseId.value)
        ? ctxCourseId.value
        : '') || String(myLessons.value[0]?.courseId ?? '')
    selectedCourseId.value = preferred
    if (ctxSectionId.value) selectedSectionId.value = ctxSectionId.value
    if (!preferred) {
      // 没有课程 → 没有上下文 → 列表接口会 400，直接不调用
      await Promise.all([loadQuestions(), loadNotes()])
    }
  } catch {
    myLessons.value = []
  } finally {
    lessonsLoading.value = false
  }
}

async function loadCatalogs(courseId: string) {
  catalogs.value = []
  if (!courseId) return
  try {
    catalogs.value = (await getCourseCatalogues(courseId)) ?? []
  } catch {
    catalogs.value = []
  }
}

// ---------- 问答 ----------
async function loadQuestions() {
  if (!hasContext.value) {
    questions.value = []
    questionTotal.value = 0
    return
  }
  loading.value = true
  try {
    const page = await pageQuestions({
      pageNo: qPageNo.value,
      pageSize: qPageSize.value,
      onlyMine: onlyMineQ.value || undefined,
      ...contextParams(),
    })
    questions.value = page?.list ?? []
    questionTotal.value = pageTotal(page)
  } catch {
    questions.value = []
    questionTotal.value = 0
  } finally {
    loading.value = false
  }
}

async function openAskDialog() {
  if (!selectedCourseId.value) {
    ElMessage.warning('先选一门课程')
    return
  }
  Object.assign(askForm, {
    courseId: selectedCourseId.value,
    chapterId: '',
    sectionId: selectedSectionId.value,
    title: '',
    description: '',
    anonymity: false,
  })
  // 若从 URL 带来了 sectionId，反推出它属于哪个训练日
  if (askForm.sectionId) {
    const day = catalogs.value.find((d) => (d.sections ?? []).some((s) => s.id === askForm.sectionId))
    if (day) askForm.chapterId = day.id
  }
  askVisible.value = true
}

function handleAskChapterChange() {
  askForm.sectionId = ''
}

async function submitQuestion() {
  if (!askForm.courseId) return ElMessage.warning('请选择课程')
  if (!askForm.chapterId) return ElMessage.warning('请选择训练日')
  if (!askForm.sectionId) return ElMessage.warning('请选择训练动作')
  if (!askForm.title.trim()) return ElMessage.warning('请填写问题标题')
  if (!askForm.description.trim()) return ElMessage.warning('请填写问题描述（后端必填）')

  askSubmitting.value = true
  try {
    await saveQuestion({
      courseId: askForm.courseId,
      chapterId: askForm.chapterId,
      sectionId: askForm.sectionId,
      title: askForm.title.trim(),
      description: askForm.description.trim(),
      anonymity: askForm.anonymity,
    })
    askVisible.value = false
    ElMessage.success('提问已发布')
    qPageNo.value = 1
    await loadQuestions()
  } catch {
    /* request.ts 已提示 */
  } finally {
    askSubmitting.value = false
  }
}

async function removeQuestion(q: Question) {
  try {
    await ElMessageBox.confirm(`确定要删除「${q.title}」吗？`, '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteQuestion(q.id)
  ElMessage.success('已删除')
  await loadQuestions()
}

// ---------- 笔记 ----------
async function loadNotes() {
  if (!hasContext.value) {
    notes.value = []
    noteTotal.value = 0
    return
  }
  loading.value = true
  try {
    const page = await pageNotes({
      pageNo: nPageNo.value,
      pageSize: nPageSize.value,
      onlyMine: onlyMineN.value || undefined,
      ...contextParams(),
    })
    notes.value = page?.list ?? []
    noteTotal.value = pageTotal(page)
  } catch {
    notes.value = []
    noteTotal.value = 0
  } finally {
    loading.value = false
  }
}

function openNoteDialog() {
  Object.assign(noteForm, {
    chapterId: '',
    sectionId: selectedSectionId.value,
    noteMoment: 0,
    content: '',
    isPrivate: false,
  })
  // 页面已经选了动作 → 反推它属于哪个训练日，省得用户再点一遍
  if (noteForm.sectionId) {
    const day = catalogs.value.find((d) => (d.sections ?? []).some((s) => s.id === noteForm.sectionId))
    if (day) noteForm.chapterId = day.id
  }
  noteVisible.value = true
}

async function submitNote() {
  if (!noteForm.chapterId) return ElMessage.warning('请选择训练日')
  if (!noteForm.sectionId) return ElMessage.warning('请选择训练动作')
  if (!noteForm.content.trim()) return ElMessage.warning('请写点什么')
  noteSubmitting.value = true
  try {
    await saveNote({
      courseId: selectedCourseId.value,
      chapterId: noteForm.chapterId,
      sectionId: noteForm.sectionId,
      // ★ 后端 `noteMoment` 是 @NotNull：不能传 undefined（`|| undefined` 会把 0 变成 undefined）
      noteMoment: noteForm.noteMoment ?? 0,
      content: noteForm.content.trim(),
      isPrivate: noteForm.isPrivate,
    })
    noteVisible.value = false
    ElMessage.success('笔记已保存')
    nPageNo.value = 1
    await loadNotes()
  } catch {
    /* 已提示 */
  } finally {
    noteSubmitting.value = false
  }
}

async function removeNote(n: Note) {
  try {
    await ElMessageBox.confirm('确定要删除这条笔记吗？', '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteNote(n.id)
  ElMessage.success('已删除')
  await loadNotes()
}

async function toggleNoteLike(n: Note) {
  try {
    // ★ 乐观更新：`liked` 立刻翻转，计数先按预测走（服务端计数每 20 秒才同步一次）。
    //   推导见 utils/like.ts —— 关键是 `liked` 与服务端同步翻转，所以连点不会 +2
    await toggleLikeWithRollback(n, LikeBizType.NOTE)
  } catch {
    /* request.ts 已提示，rollback 已在 util 里完成 */
  }
}

/**
 * 这条笔记卡片当前是不是"已采集"状态。
 *
 * ★ 两个字段**必须合起来看**（`NoteVO` 的注释里也专门区分了它俩）：
 *   · `isGathered`   —— 这条笔记**本身是**我从别人那儿采集来的副本（出现在的"只看我的"列表里）
 *   · `gatheredByMe` —— 这条（别人的）笔记**我采集过**（公共列表里用来点亮星标）
 * 只看 `gatheredByMe` 的话，"我采集来的副本"会显示成"采集"按钮 ——
 * 点下去会把**自己的副本再采集一份**，而后端因为副本强制私有会报"笔记不存在"，
 * 用户看到的是一句莫名其妙的报错。
 */
function isGatheredCard(n: Note): boolean {
  return Boolean(n.isGathered) || Boolean(n.gatheredByMe)
}

function gatherLabel(n: Note): string {
  if (n.isGathered) return '移除副本'
  return n.gatheredByMe ? '已采集' : '采集'
}

/**
 * 采集 / 取消采集。
 *
 * ★★ 这里有个**静默无效**的坑（实测）：
 *   `DELETE /ls/notes/gathers/{id}` 的入参是**原笔记 id**（后端 JavaDoc 明确写了
 *   "传原笔记id"，实现是按 `gathered_note_id = #{id}` 删当前用户的副本）。
 *   如果传"副本自己的 id"，接口**返回 200 OK 但什么都不删** ——
 *   界面上按钮点下去毫无反应，也没有任何报错。
 *
 *   而 `NoteVO` **没有暴露** `gatheredNoteId`，所以前端**拿不到副本对应的原笔记 id**。
 *   → 对"副本卡片"改用 `DELETE /ls/notes/{副本id}`：
 *     同样是"取消采集"的最终效果（把这份副本删掉），而且 id 我们手里就有。
 */
async function toggleGather(n: Note) {
  try {
    if (n.isGathered) {
      // 这是我采集来的副本 → 直接删掉这份副本
      await deleteNote(n.id)
      ElMessage.success('已移除副本')
    } else if (n.gatheredByMe) {
      // 这是别人的笔记、我采集过 → 传**原笔记 id**（就是 n.id，因为卡片显示的就是原笔记）
      await removeGatherNote(n.id)
      ElMessage.success('已取消采集')
    } else {
      await gatherNote(n.id)
      ElMessage.success('已采集到我的笔记')
    }
    await loadNotes()
  } catch {
    /* 已提示 */
  }
}
</script>

<template>
  <div class="community-page">
    <div class="page-head">
      <h2>社区</h2>
      <span class="head-count">问答与笔记都挂在具体课程下</span>
    </div>

    <!-- ★ 上下文选择器：不选就没有内容（后端列表接口强制要求归属） -->
    <el-card v-loading="lessonsLoading" shadow="never" class="ctx-card">
      <div class="ctx-row">
        <span class="ctx-label">课程</span>
        <el-select
          v-model="selectedCourseId"
          placeholder="选择课程"
          style="width: 260px"
          data-testid="ctx-course"
        >
          <el-option
            v-for="l in myLessons"
            :key="l.courseId"
            :label="l.courseName"
            :value="l.courseId"
          />
        </el-select>

        <span class="ctx-label">训练动作</span>
        <el-select
          v-model="selectedSectionId"
          placeholder="全部动作"
          clearable
          :disabled="!selectedCourseId"
          style="width: 220px"
          data-testid="ctx-section"
        >
          <el-option v-for="s in allSections" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>

        <span class="ctx-tip">（不选动作 = 看整门课的内容）</span>
      </div>
    </el-card>

    <el-empty v-if="!lessonsLoading && !myLessons.length" description="你还没有报名任何课程">
      <el-button type="primary" @click="router.push('/courses')">去挑课程</el-button>
    </el-empty>

    <template v-else>
      <el-tabs v-model="activeTab" class="main-tabs">
        <el-tab-pane label="问答" name="qa">
          <div class="toolbar">
            <div class="toolbar-left">
              <el-switch v-model="onlyMineQ" active-text="只看我提问的" @change="loadQuestions" />
              <span class="total-tip" data-testid="question-total">共 {{ questionTotal }} 个问题</span>
            </div>
            <el-button
              type="primary"
              :icon="Plus"
              :disabled="!selectedCourseId"
              data-testid="question-ask-btn"
              @click="openAskDialog"
            >
              我要提问
            </el-button>
          </div>

          <el-empty
            v-if="!questions.length && !loading"
            description="这门课下还没有人提问"
            data-testid="question-empty"
          />

          <el-card
            v-for="q in questions"
            :key="q.id"
            shadow="never"
            class="item-card"
            data-testid="question-item"
          >
            <div class="item-head" @click="router.push(`/community/question/${q.id}`)">
              <span class="item-title">{{ q.title }}</span>
              <el-tag v-if="q.anonymity" type="info" size="small">匿名</el-tag>
            </div>
            <div v-if="q.description" class="item-desc" @click="router.push(`/community/question/${q.id}`)">
              {{ q.description }}
            </div>
            <div v-if="q.latestReplyContent" class="item-latest">
              最新回答：{{ q.latestReplyContent }}
              <span v-if="q.latestReplyUser" class="latest-user">—— {{ q.latestReplyUser }}</span>
            </div>
            <div class="item-foot">
              <span class="foot-left">
                <el-icon><ChatDotRound /></el-icon>
                <span data-testid="question-answer-times">{{ q.answerTimes ?? 0 }} 个回答</span>
                <span class="dot">·</span>
                <span>{{ q.userName || '匿名用户' }}</span>
                <span class="dot">·</span>
                <span>{{ formatDateTime(q.createTime) }}</span>
              </span>
              <el-button link type="danger" :icon="Delete" size="small" @click.stop="removeQuestion(q)">
                删除
              </el-button>
            </div>
          </el-card>

          <el-pagination
            v-if="questionTotal > qPageSize"
            v-model:current-page="qPageNo"
            class="pager"
            layout="prev, pager, next"
            :page-size="qPageSize"
            :total="questionTotal"
            @current-change="loadQuestions"
          />
        </el-tab-pane>

        <el-tab-pane label="笔记" name="note">
          <div class="toolbar">
            <div class="toolbar-left">
              <el-switch v-model="onlyMineN" active-text="只看我的" @change="loadNotes" />
              <span class="total-tip" data-testid="note-total">共 {{ noteTotal }} 条笔记</span>
            </div>
            <el-button
              type="primary"
              :icon="EditPen"
              :disabled="!selectedCourseId"
              data-testid="note-write-btn"
              @click="openNoteDialog"
            >
              写笔记
            </el-button>
          </div>

          <el-empty v-if="!notes.length && !loading" description="这门课下还没有笔记" data-testid="note-empty" />

          <el-card v-for="n in notes" :key="n.id" shadow="never" class="item-card" data-testid="note-item">
            <div class="note-head">
              <el-avatar :size="24" :src="n.authorIcon ?? undefined">
                {{ (n.authorName || '?').slice(0, 1) }}
              </el-avatar>
              <span class="note-author">{{ n.authorName || '未知' }}</span>
              <el-tag v-if="n.isGathered" type="warning" size="small">采集</el-tag>
              <el-tag v-if="n.isPrivate" type="info" size="small">私密</el-tag>
              <span v-if="n.noteMoment" class="note-moment">视频 {{ formatDuration(n.noteMoment) }} 处</span>
              <span class="note-time">{{ formatDateTime(n.createTime) }}</span>
            </div>

            <div class="item-desc">{{ n.content }}</div>

            <div class="item-foot">
              <span class="foot-left">
                <el-button
                  link
                  :type="n.liked ? 'primary' : 'default'"
                  size="small"
                  data-testid="note-like-btn"
                  @click="toggleNoteLike(n)"
                >
                  ♥ <span data-testid="note-like-times">{{ n.likedTimes ?? 0 }}</span>
                </el-button>
                <el-button
                  link
                  :icon="isGatheredCard(n) ? StarFilled : Star"
                  :type="isGatheredCard(n) ? 'warning' : 'default'"
                  size="small"
                  data-testid="note-gather-btn"
                  @click="toggleGather(n)"
                >
                  {{ gatherLabel(n) }}
                </el-button>
              </span>
              <el-button link type="danger" :icon="Delete" size="small" @click="removeNote(n)">
                删除
              </el-button>
            </div>
          </el-card>

          <el-pagination
            v-if="noteTotal > nPageSize"
            v-model:current-page="nPageNo"
            class="pager"
            layout="prev, pager, next"
            :page-size="nPageSize"
            :total="noteTotal"
            @current-change="loadNotes"
          />
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 提问弹窗：★ 必须选定「课程 > 训练日 > 动作」三级上下文 -->
    <el-dialog v-model="askVisible" title="我要提问" width="620px">
      <el-alert class="ask-tip" type="info" :closable="false" show-icon>
        <template #title>
          提问要绑定到**具体的训练动作** —— 这样回答的人才知道你在问哪个动作的哪一步。
        </template>
      </el-alert>

      <el-form label-position="top">
        <el-form-item label="训练日" required>
          <el-select
            v-model="askForm.chapterId"
            placeholder="选择训练日"
            style="width: 100%"
            data-testid="question-chapter"
            @change="handleAskChapterChange"
          >
            <el-option
              v-for="d in chaptersOfCourse"
              :key="d.id"
              :label="`Day ${d.index} · ${d.name}`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="训练动作" required>
          <el-select
            v-model="askForm.sectionId"
            placeholder="先选训练日"
            :disabled="!askForm.chapterId"
            style="width: 100%"
            data-testid="question-section"
          >
            <el-option v-for="s in sectionsOfChapter" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="标题" required>
          <el-input
            v-model="askForm.title"
            maxlength="80"
            show-word-limit
            placeholder="一句话说清问题，例如：这个动作手腕疼怎么办"
            data-testid="question-title"
          />
        </el-form-item>

        <el-form-item label="问题描述" required>
          <el-input
            v-model="askForm.description"
            type="textarea"
            :rows="4"
            maxlength="300"
            show-word-limit
            placeholder="做得时候是什么感觉、用了多大重量、第几组开始……越具体越容易被回答"
            data-testid="question-description"
          />
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="askForm.anonymity">匿名提问（别人看不到你的名字）</el-checkbox>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="askVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="askSubmitting"
          data-testid="question-submit"
          @click="submitQuestion"
        >
          发布
        </el-button>
      </template>
    </el-dialog>

    <!-- 写笔记弹窗：★ 也要选到「训练日 + 动作」（后端三个 id 全是必填） -->
    <el-dialog v-model="noteVisible" title="写笔记" width="560px">
      <el-alert class="ask-tip" type="info" :closable="false" show-icon>
        <template #title>
          笔记要落在具体的训练动作上 —— 同学练到这个动作时就能看到你的心得。
        </template>
      </el-alert>

      <el-form label-position="top">
        <el-form-item label="训练日" required>
          <el-select
            v-model="noteForm.chapterId"
            placeholder="选择训练日"
            style="width: 100%"
            data-testid="note-chapter"
            @change="noteForm.sectionId = ''"
          >
            <el-option
              v-for="d in chaptersOfCourse"
              :key="d.id"
              :label="`Day ${d.index} · ${d.name}`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="训练动作" required>
          <el-select
            v-model="noteForm.sectionId"
            placeholder="先选训练日"
            :disabled="!noteForm.chapterId"
            style="width: 100%"
            data-testid="note-section"
          >
            <el-option v-for="s in noteSectionsOfChapter" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="内容" required>
          <el-input
            v-model="noteForm.content"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="例如：肩胛骨收紧再推，感觉发力点立刻不一样"
            data-testid="note-content"
          />
        </el-form-item>

        <el-form-item label="对应视频时间点（秒）" required>
          <el-input-number v-model="noteForm.noteMoment" :min="0" :max="100000" />
          <span class="form-tip">后端必填；填 0 表示"不指定具体位置"</span>
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="noteForm.isPrivate">设为隐私笔记（只有自己能看到）</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="noteVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="noteSubmitting"
          data-testid="note-submit"
          @click="submitNote"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.community-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.head-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.ctx-card {
  margin-bottom: 12px;
}

.ctx-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ctx-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.ctx-tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.main-tabs {
  margin-bottom: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.total-tip {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.item-card {
  margin-bottom: 12px;
}

.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.item-title {
  font-size: 15px;
  font-weight: 600;
}

.item-title:hover {
  color: var(--el-color-primary);
}

.item-desc {
  margin-top: 8px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-word;
}

.item-latest {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.latest-user {
  color: var(--el-text-color-secondary);
}

.item-foot {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.foot-left {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dot {
  color: var(--el-text-color-placeholder);
}

.note-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.note-author {
  font-size: 13px;
  font-weight: 600;
}

.note-moment {
  font-size: 12px;
  color: var(--el-color-primary);
}

.note-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ask-tip {
  margin-bottom: 14px;
}

.pager {
  margin-top: 16px;
  justify-content: center;
}

.form-tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
