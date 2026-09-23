<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import { ArrowLeft, ArrowRight, Check, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { getCourseCatalogues } from '@/api/course'
import { getPractice, submitPractice } from '@/api/assessment'
import {
  getCourseProgress,
  getLessonByCourse,
  reportLearningRecord,
} from '@/api/learning'
import { getPlayUrl } from '@/api/media'
import type { CataVO } from '@/types/course'
import type { LearningLesson, LearningProgress } from '@/types/learning'
import { CataType } from '@/types/learning'
import type { PracticeQuestion, PracticeResult } from '@/types/assessment'
import { JUDGE_OPTIONS, SUBJECT_TYPES } from '@/types/assessment'
import { formatDuration } from '@/utils/format'

/**
 * 学习页（M7-4）
 *
 * ============================================================
 * ★★ 这一页最关键的一个决定：**什么时候上报学习进度**
 * ============================================================
 *
 * 后端的行为是"**上报即完成**" —— 实测上报 `moment=60 / duration=120`（只看了一半），
 * 返回的学习记录里 `finished` 已经是 `true`。它不判断"看了百分之多少"。
 *
 * 这意味着：**如果跟着 `timeupdate` 频繁上报，用户点开视频几秒，
 * 这个动作就被记成"练完了"** —— 进度条会骗人，而且这种"错误"看起来像功能正常。
 *
 * 所以本页只在四个时机上报：
 *   ① 视频**暂停**（自然停顿，是"我看到这"的真实信号）
 *   ② **切换动作**前（先把上一个的断点存下来）
 *   ③ **离开页面**前（路由切换时补一次）
 *   ④ 用户点**「标记完成」**（明确表达"练完了"，这时才用 moment = duration）
 *
 * 代价是"看到一半直接关标签页"可能丢几秒断点 —— 这个代价远小于"进度都是假的"。
 */
const route = useRoute()
const router = useRouter()

const courseId = computed(() => String(route.params.courseId ?? ''))

const loading = ref(true)
const lesson = ref<LearningLesson | null>(null)
const catalogues = ref<CataVO[]>([])
const progress = ref<LearningProgress | null>(null)

/** 当前正在练的动作 */
const currentSection = ref<CataVO | null>(null)
/** 视频地址（预签名，会过期 → 每次切动作重新取） */
const playUrl = ref('')
const videoLoading = ref(false)
const videoFailed = ref(false)
/** 进度上报进行中（避免重复提交） */
const reporting = ref(false)

const videoRef = ref<HTMLVideoElement | null>(null)

/** 把所有动作摊平（带着所属训练日名），方便"上一个/下一个"和进度统计 */
const flatSections = computed(() => {
  const out: { day: string; section: CataVO }[] = []
  for (const day of catalogues.value) {
    if (day.type !== CataType.DAY) continue
    for (const sec of day.sections ?? []) {
      out.push({ day: day.name ?? '', section: sec })
    }
  }
  return out
})

/** sectionId → 学习记录（用于打勾与断点续播） */
const recordMap = computed(() => {
  const map = new Map<string, { moment: number; finished: boolean }>()
  for (const r of progress.value?.records ?? []) {
    map.set(r.sectionId, { moment: r.moment ?? 0, finished: Boolean(r.finished) })
  }
  return map
})

const finishedCount = computed(() => flatSections.value.filter((s) => isFinished(s.section.id)).length)
const totalCount = computed(() => flatSections.value.length)

const currentIndex = computed(() =>
  flatSections.value.findIndex((s) => s.section.id === currentSection.value?.id),
)

function isFinished(sectionId: string): boolean {
  return recordMap.value.get(sectionId)?.finished === true
}

function isCurrent(sectionId: string): boolean {
  return currentSection.value?.id === sectionId
}

onMounted(async () => {
  await loadAll()
  // 默认定位到"上次学到的那个动作"，没学过就从头开始
  const resumeId = progress.value?.latestSectionId
  const target =
    flatSections.value.find((s) => s.section!.id === resumeId)?.section ??
    flatSections.value[0]?.section ??
    null
  if (target) await selectSection(target, { skipReport: true })
  loading.value = false
})

/**
 * 离开页面前把当前断点补上一次。
 *
 * ★ 用 `onBeforeUnmount`（SPA 路由切换会正常触发）而不是 `pagehide`：
 *   `pagehide` 时页面正在卸载，异步请求经常发不出去，反而给人一种"上报了"的错觉。
 *   这里如实说明：**直接关标签页可能丢最后几秒断点**，这个取舍是有意的。
 */
onBeforeUnmount(async () => {
  await reportNow({ silent: true })
})

async function loadAll() {
  try {
    const [l, catas] = await Promise.all([
      getLessonByCourse(courseId.value),
      getCourseCatalogues(courseId.value),
    ])
    lesson.value = l
    catalogues.value = catas ?? []
  } catch {
    // 没报名 / 课程不存在 → 后端报错，页面给出去报名的引导
    lesson.value = null
    loading.value = false
    return
  }
  await loadProgress()
}

async function loadProgress() {
  try {
    progress.value = await getCourseProgress(courseId.value)
  } catch {
    progress.value = null
  }
}

/**
 * 切换动作：**先存上一个的断点，再换**。
 *
 * 顺序不能反 —— 反了就会把"新动作的 0 秒"记到旧动作头上。
 */
async function selectSection(section: CataVO, opts: { skipReport?: boolean } = {}) {
  if (!opts.skipReport && currentSection.value && currentSection.value.id !== section.id) {
    await reportNow()
  }

  currentSection.value = section
  playUrl.value = ''
  videoFailed.value = false

  if (!section.mediaId) {
    // 目录里没绑视频（后端上架时是有校验的，这里是防御）
    videoFailed.value = true
    return
  }

  videoLoading.value = true
  try {
    const url = await getPlayUrl(section.mediaId)
    playUrl.value = url?.url ?? ''
    if (!playUrl.value) videoFailed.value = true
  } catch {
    videoFailed.value = true
  } finally {
    videoLoading.value = false
  }

  // 断点续播：等 DOM 更新（src 换过了）再把时间拨回去
  await nextTick()
  const video = videoRef.value
  const saved = recordMap.value.get(section.id)
  if (video && saved && saved.moment > 0) {
    video.currentTime = saved.moment
  }
}

/**
 * 上报当前动作的进度。
 *
 * ★ `moment` 取播放器当前时间；如果视频还没加载出来就退回用记录里的断点，
 *   避免把 0 写进去（那等于"把断点清零"）。
 */
async function reportNow(opts: { silent?: boolean; asFinished?: boolean } = {}) {
  const section = currentSection.value
  const currentLesson = lesson.value
  if (!section || !currentLesson) return

  const video = videoRef.value
  const fallback = recordMap.value.get(section.id)?.moment ?? 0
  const moment = opts.asFinished
    ? (section.mediaDuration ?? Math.floor(video?.duration ?? 0) ?? fallback)
    : Math.floor(video?.currentTime ?? fallback)
  const duration = section.mediaDuration ?? Math.floor(video?.duration ?? 0)

  if (moment <= 0 && fallback <= 0) return // 确实没看过，不必写记录

  reporting.value = true
  try {
    await reportLearningRecord({
      lessonId: currentLesson.id,
      sectionId: section.id,
      moment,
      duration: duration || moment,
    })
    await loadProgress()
    if (!opts.silent) {
      ElMessage.success(opts.asFinished ? '已标记为完成' : '进度已保存')
    }
  } catch {
    // 上报失败不打断学习（下次暂停/切换还会再试），但也不要假装成功
    if (!opts.silent) ElMessage.warning('进度保存失败，稍后会再试')
  } finally {
    reporting.value = false
  }
}

/** 点「标记完成」：用 moment = duration 明确表达"练完了" */
async function handleMarkDone() {
  const section = currentSection.value
  if (!section) return
  if (isFinished(section.id)) {
    ElMessage.info('这个动作已经完成了')
    return
  }
  await reportNow({ asFinished: true })
}

async function goPrev() {
  const i = currentIndex.value
  if (i > 0) await selectSection(flatSections.value[i - 1].section)
}

async function goNext() {
  const i = currentIndex.value
  if (i >= 0 && i < flatSections.value.length - 1) {
    await selectSection(flatSections.value[i + 1].section)
  } else {
    ElMessage.success('已经是最后一个动作了')
  }
}

// ============================================================
// 动作小测（M7-6）
// ============================================================

/**
 * ★★ 三条实现要点（都是"读接口定义看不出来、必须实测"的）：
 *
 * 1. **拉题时后端不下发答案与解析**（`SubjectSimpleVO` 里根本没有 answer / analysis，
 *    后端注释写明是"故意的"）→ 判分**只能**交给后端，前端不要试图本地判分。
 *    这也是为什么下面"结果区"用的是**提交返回的** `rightAnswer` / `analysis`。
 *
 * 2. **多选题的答案格式是逗号分隔的选项序号**（如 `"1,3"`），而且后端按**字符串**比对 ——
 *    所以提交前必须**排序归一化**：用户勾选顺序是 `[3,1]`，直接 join 成 `"3,1"` 会被判错。
 *    这也是这里统一用 `string[]` 存作答、提交时才 join 的原因。
 *
 * 3. **判断题的选项由前端补**：后端对判断题（type=4）不下发 `options`
 *    （答案 1-正确 2-错误），所以要自己用 `JUDGE_OPTIONS` 兜底，否则判断题会渲染成空白。
 */
const quizVisible = ref(false)
const quizLoading = ref(false)
const quizSubmitting = ref(false)
const quizQuestions = ref<PracticeQuestion[]>([])
const quizResult = ref<PracticeResult | null>(null)
/** 作答：统一用数组存（单选/判断只会有一个元素），提交时再按题型归一化 */
const quizAnswers = ref<Record<string, string[]>>({})
/** 提交时的答案快照 —— 结果区要显示"我提交的是什么"，而作答区此时已经不可改了 */
const quizSubmitted = ref<Record<string, string>>({})

/** 题型 → 这道题可选的选项（判断题补上"正确/错误"） */
function optionsOf(q: PracticeQuestion): string[] {
  if (Number(q.subjectType) === 4) return JUDGE_OPTIONS
  return q.options ?? []
}

/** 这道题是不是多选类（2-多选 / 3-不定向）→ 用 checkbox，其余用 radio */
function isMulti(q: PracticeQuestion): boolean {
  const t = Number(q.subjectType)
  return t === 2 || t === 3
}

/** 提交前把 `string[]` 归一化成接口要的字符串 */
function normalizeAnswer(q: PracticeQuestion, picked: string[] | undefined): string {
  const arr = picked ?? []
  if (!arr.length) return ''
  if (!isMulti(q)) return arr[0]
  // ★ 排序：后端按字符串比对，"3,1" 与 "1,3" 是两回事
  return [...arr].sort((a, b) => Number(a) - Number(b)).join(',')
}

const quizAllAnswered = computed(() =>
  quizQuestions.value.every((q) => normalizeAnswer(q, quizAnswers.value[q.id]) !== ''),
)

async function openQuiz() {
  if (!currentSection.value) return
  quizVisible.value = true
  quizResult.value = null
  quizQuestions.value = []
  quizAnswers.value = {}
  quizSubmitted.value = {}
  quizLoading.value = true
  try {
    quizQuestions.value = (await getPractice(currentSection.value.id)) ?? []
    // ★ 给每道题预置空数组：el-checkbox-group 的 v-model 必须是数组，
    //   传 undefined 会得到一个"绑不上"的受控组件（勾选无效、而且只在某些题型上出现）
    quizAnswers.value = Object.fromEntries(quizQuestions.value.map((q) => [q.id, []]))
  } catch {
    /* request.ts 已提示 */
  } finally {
    quizLoading.value = false
  }
}

async function handleSubmitQuiz() {
  if (!currentSection.value) return
  if (!quizAllAnswered.value) {
    ElMessage.warning('还有题目没有作答')
    return
  }
  const answers = quizQuestions.value.map((q) => ({
    subjectId: q.id,
    answer: normalizeAnswer(q, quizAnswers.value[q.id]),
  }))
  quizSubmitted.value = Object.fromEntries(answers.map((a) => [a.subjectId, a.answer]))
  quizSubmitting.value = true
  try {
    quizResult.value = await submitPractice(currentSection.value.id, answers)
    ElMessage.success('已提交，结果见下方')
  } catch {
    /* 已提示 */
  } finally {
    quizSubmitting.value = false
  }
}

/** 提交时把单选/判断的作答写成单元素数组 */
function setSingleAnswer(id: string, v: unknown) {
  quizAnswers.value[id] = v === undefined || v === null || v === '' ? [] : [String(v)]
}

/** 把答案序号翻成选项原文（结果区可读性）—— 只用于展示，不参与判分 */
function answerText(subjectId: string, ans: string | null | undefined): string {
  if (!ans) return '—'
  const q = questionOf(subjectId)
  const opts = q ? optionsOf(q) : []
  return ans
    .split(',')
    .map((n) => {
      const idx = Number(n) - 1
      return `${n}. ${opts[idx] ?? `选项 ${n}`}`
    })
    .join('　')
}

/** 结果区按 subjectId 找题干/选项（判分结果里只有 subjectId，没有 options） */
function questionOf(subjectId: string): PracticeQuestion | undefined {
  return quizQuestions.value.find((q) => String(q.id) === String(subjectId))
}

/** 重做（清空作答与结果，题目不重新拉） */
function resetQuiz() {
  quizResult.value = null
  quizSubmitted.value = {}
  quizAnswers.value = {}
  for (const q of quizQuestions.value) quizAnswers.value[q.id] = []
  for (const k of Object.keys(quizAnswers.value)) quizAnswers.value[k] = []
}
</script>

<template>
  <div v-loading="loading" class="learn-page">
    <div class="page-head">
      <el-button text :icon="ArrowLeft" @click="router.push('/my-courses')">我的课程</el-button>
      <span class="head-title">{{ lesson?.courseName ?? '学习' }}</span>
      <el-tag v-if="lesson" size="small" type="info" data-testid="learn-finished-count">
        已完成 {{ finishedCount }} / {{ totalCount }}
      </el-tag>
    </div>

    <el-result
      v-if="!loading && !lesson"
      icon="warning"
      title="你还没有报名这门课程"
      sub-title="免费课可以直接报名，付费课需要先购买"
    >
      <template #extra>
        <el-button type="primary" @click="router.push(`/course/${courseId}`)">
          去课程详情
        </el-button>
      </template>
    </el-result>

    <div v-else-if="lesson" class="learn-body">
      <!-- 左：训练目录 -->
      <el-card shadow="never" class="catalog-card">
        <template #header>
          <div class="catalog-header">
            <span>训练目录</span>
            <span class="catalog-tip">✓ 表示已有学习记录</span>
          </div>
        </template>

        <el-empty v-if="!flatSections.length" description="这门课还没有训练动作" :image-size="60" />

        <el-collapse v-else :model-value="catalogues.map((d) => d.id)" data-testid="learn-catalog">
          <el-collapse-item v-for="day in catalogues" :key="day.id" :name="day.id">
            <template #title>
              <span class="day-title">
                Day {{ day.index }} · {{ day.name }}
                <span class="day-count">
                  {{ (day.sections ?? []).filter((s) => isFinished(s.id)).length }}/{{ (day.sections ?? []).length }}
                </span>
              </span>
            </template>

            <div
              v-for="sec in day.sections ?? []"
              :key="sec.id"
              class="section-item"
              :class="{ 'is-current': isCurrent(sec.id), 'is-done': isFinished(sec.id) }"
              data-testid="learn-section"
              @click="selectSection(sec)"
            >
              <span class="section-status">
                <el-icon v-if="isFinished(sec.id)" class="done-icon"><Check /></el-icon>
                <span v-else class="section-dot">{{ sec.index }}</span>
              </span>
              <span class="section-name">{{ sec.name }}</span>
              <!-- ★ 有题目的动作在目录里就能看见（不用点进去才知道） -->
              <el-tag v-if="sec.subjectNum" class="quiz-badge" size="small" type="warning" effect="plain">
                {{ sec.subjectNum }} 题
              </el-tag>
              <span class="section-duration">{{ formatDuration(sec.mediaDuration) }}</span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <!-- 右：播放器 + 动作参数 -->
      <div class="player-col">
        <el-card shadow="never" class="player-card">
          <div v-if="currentSection" class="section-head">
            <div>
              <div class="current-name" data-testid="learn-current-name">
                {{ currentSection.name }}
              </div>
              <div class="current-sub">
                {{ formatDuration(currentSection.mediaDuration) }}
                <template v-if="currentSection.targetMuscles">
                  · 目标肌群：{{ currentSection.targetMuscles }}
                </template>
              </div>
            </div>
            <el-tag v-if="isFinished(currentSection.id)" type="success" size="small">
              已完成
            </el-tag>
          </div>

          <div class="video-wrap">
            <el-skeleton v-if="videoLoading" :rows="4" animated />
            <video
              v-else-if="playUrl"
              ref="videoRef"
              class="video"
              :src="playUrl"
              controls
              playsinline
              data-testid="learn-video"
              @pause="reportNow({ silent: true })"
            />
            <el-empty
              v-else
              :description="videoFailed ? '这个动作还没有绑定视频' : '正在准备视频…'"
              :image-size="60"
            />
          </div>

          <div class="player-actions">
            <el-button :icon="ArrowLeft" :disabled="currentIndex <= 0" @click="goPrev">
              上一个
            </el-button>
            <el-button
              type="primary"
              :icon="Check"
              :loading="reporting"
              data-testid="learn-mark-done"
              @click="handleMarkDone"
            >
              标记完成
            </el-button>
            <el-button
              :disabled="currentIndex < 0 || currentIndex >= flatSections.length - 1"
              @click="goNext"
            >
              下一个<el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
          </div>

          <el-alert class="timing-tip" type="info" :closable="false" show-icon>
            <template #title>
              进度在<b>暂停、切换动作、离开页面</b>时上报，点「标记完成」才会记为已完成。
              <br />（后端按"是否上报过"判定完成，所以不能边播边报 —— 否则点开几秒就算练完了。）
            </template>
          </el-alert>
        </el-card>

        <!-- 动作参数 -->
        <el-card v-if="currentSection" shadow="never" class="params-card">
          <template #header><span>动作参数</span></template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="组数">
              {{ currentSection.sets ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="每组次数">
              {{ currentSection.reps ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="组间休息">
              {{ currentSection.restSeconds ? `${currentSection.restSeconds} 秒` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="建议负重">
              {{ currentSection.weight ? `${currentSection.weight} kg` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="器械">
              {{ currentSection.equipment || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="小测">
              <template v-if="currentSection.subjectNum">
                <el-button link type="primary" size="small" data-testid="learn-open-quiz" @click="openQuiz">
                  {{ currentSection.subjectNum }} 道题，去作答 ›
                </el-button>
              </template>
              <template v-else>暂无</template>
            </el-descriptions-item>
          </el-descriptions>
          <el-alert class="timing-tip" type="warning" :closable="false">
            <template #title>
              <el-icon><VideoPlay /></el-icon>
              视频需要先绑定媒资；若这里显示"没有绑定视频"，是目录数据的问题，不是播放器的问题。
            </template>
          </el-alert>
        </el-card>
      </div>
    </div>

    <!-- ==================== 动作小测抽屉（M7-6） ==================== -->
    <!-- ★ 用抽屉而不是新页面：小测是"看视频时顺手做一下"的动作，
         跳走会丢掉播放位置与"当前动作"的上下文 -->
    <el-drawer
      v-model="quizVisible"
      :title="`动作小测 · ${currentSection?.name ?? ''}`"
      size="640px"
      data-testid="quiz-drawer"
    >
      <div v-loading="quizLoading" class="quiz-body">
        <el-empty
          v-if="!quizLoading && !quizQuestions.length"
          description="这个动作还没有配小测题"
          data-testid="quiz-empty"
        />

        <template v-else-if="quizQuestions.length">
          <!-- ---------- 作答区 ---------- -->
          <template v-if="!quizResult">
            <div v-for="(q, qi) in quizQuestions" :key="q.id" class="quiz-q" data-testid="quiz-question">
              <div class="q-head">
                <span class="q-index">第 {{ qi + 1 }} 题</span>
                <el-tag size="small" effect="plain">{{ SUBJECT_TYPES[Number(q.subjectType)] ?? '题目' }}</el-tag>
                <span class="q-score">{{ q.score ?? 0 }} 分</span>
              </div>
              <p class="q-name" data-testid="quiz-question-name">{{ q.name }}</p>

              <!-- ★ 这里只有选项、没有"正确答案" —— 后端作答前不下发答案，判分也在后端 -->
              <el-radio-group
                v-if="!isMulti(q)"
                class="q-options"
                :model-value="quizAnswers[q.id]?.[0] ?? ''"
                @update:model-value="(v: unknown) => setSingleAnswer(q.id, v)"
              >
                <el-radio v-for="(opt, oi) in optionsOf(q)" :key="oi" :value="String(oi + 1)" class="q-option">
                  {{ oi + 1 }}. {{ opt }}
                </el-radio>
              </el-radio-group>

              <el-checkbox-group v-else v-model="quizAnswers[q.id]" class="q-options">
                <el-checkbox v-for="(opt, oi) in optionsOf(q)" :key="oi" :value="String(oi + 1)" class="q-option">
                  {{ oi + 1 }}. {{ opt }}
                </el-checkbox>
              </el-checkbox-group>
            </div>

            <div class="quiz-foot">
              <span class="quiz-hint">多选题要选全才算对；答案由服务端判分</span>
              <el-button
                type="primary"
                :loading="quizSubmitting"
                :disabled="!quizAllAnswered"
                data-testid="quiz-submit"
                @click="handleSubmitQuiz"
              >
                提交作答
              </el-button>
            </div>
          </template>

          <!-- ---------- 结果区（提交后才有答案与解析） ---------- -->
          <template v-else>
            <el-result
              class="quiz-result-head"
              icon="success"
              :title="`得分 ${quizResult.score ?? 0} / ${quizResult.totalScore ?? 0}`"
            >
              <template #sub-title>
                <span data-testid="quiz-result-summary">
                  答对 {{ quizResult.correctCount ?? 0 }} / {{ quizResult.totalCount ?? 0 }} 题
                </span>
                <div class="quiz-points-tip">答对的题已计入积分（同一道题重复答对不会重复计分）</div>
              </template>
            </el-result>

            <div
              v-for="(d, i) in quizResult.details ?? []"
              :key="d.subjectId"
              class="quiz-q"
              data-testid="quiz-result-item"
            >
              <div class="q-head">
                <span class="q-index">第 {{ i + 1 }} 题</span>
                <el-tag :type="d.correct ? 'success' : 'danger'" size="small" data-testid="quiz-result-correct">
                  {{ d.correct ? '答对' : '答错' }}
                </el-tag>
              </div>
              <p class="q-name">{{ d.name }}</p>
              <p class="q-answer" data-testid="quiz-result-yours">
                你的答案：{{ answerText(d.subjectId, d.yourAnswer) }}
              </p>
              <p class="q-answer is-right" data-testid="quiz-result-right">
                正确答案：{{ answerText(d.subjectId, d.rightAnswer) }}
              </p>
              <p v-if="d.analysis" class="q-analysis" data-testid="quiz-result-analysis">
                解析：{{ d.analysis }}
              </p>
            </div>

            <div class="quiz-foot">
              <el-button data-testid="quiz-retry" @click="resetQuiz">再答一次</el-button>
              <el-button type="primary" @click="quizVisible = false">完成</el-button>
            </div>
          </template>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* ---- 目录里的"有题"角标 ---- */
.quiz-badge {
  flex: 0 0 auto;
  margin-right: 8px;
  transform: scale(0.9);
}

/* ---- 小测抽屉 ---- */
.quiz-body {
  min-height: 200px;
}

.quiz-q {
  margin-bottom: 18px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.q-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.q-index {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.q-score {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.q-name {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.6;
}

.q-options {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.q-option {
  height: auto;
  padding: 4px 0;
  white-space: normal;
  line-height: 1.6;
}

.q-answer {
  margin: 0 0 4px;
  font-size: 13px;
  line-height: 1.7;
}

.q-answer.is-right {
  color: var(--el-color-success);
}

.q-analysis {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.8;
}

.quiz-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 8px;
}

.quiz-hint {
  margin-right: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.quiz-result-head {
  padding: 0 0 8px;
}

.quiz-points-tip {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.learn-page {
  max-width: 1180px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.head-title {
  font-size: 18px;
  font-weight: 700;
}

.learn-body {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
  align-items: start;
}

.catalog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-weight: 600;
}

.catalog-tip {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.day-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 8px;
  font-size: 13px;
  font-weight: 600;
}

.day-count {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.section-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 6px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.section-item:hover {
  background: var(--el-fill-color-light);
}

.section-item.is-current {
  background: var(--el-color-primary-light-9);
  font-weight: 600;
}

.section-item.is-done .section-name {
  color: var(--el-text-color-secondary);
}

.section-status {
  width: 18px;
  display: inline-flex;
  justify-content: center;
  flex-shrink: 0;
}

.done-icon {
  color: var(--el-color-success);
}

.section-dot {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.section-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-duration {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.player-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.current-name {
  font-size: 16px;
  font-weight: 700;
}

.current-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.video-wrap {
  min-height: 260px;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.video {
  width: 100%;
  max-height: 460px;
  display: block;
}

.video-wrap :deep(.el-empty) {
  --el-empty-fill-color-0: #1f1f1f;
}

.player-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.timing-tip {
  margin-top: 14px;
}

@media (max-width: 1000px) {
  .learn-body {
    grid-template-columns: 1fr;
  }
}
</style>
