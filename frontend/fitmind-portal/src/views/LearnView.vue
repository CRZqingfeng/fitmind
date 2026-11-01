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
import NoteComposer from '@/components/learn/NoteComposer.vue'
import NotePanel from '@/components/learn/NotePanel.vue'
import QaPanel from '@/components/learn/QaPanel.vue'
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
 * 后端对"是否练完"的判据是 **`moment × 2 >= duration`**（过半即完成），
 * 阈值收在 `LearningConstants.FINISH_RATIO_DENOMINATOR`。
 *
 * ★ 订正（2026-10-04）：本页原先写的是"后端上报即完成、不判断百分比"，
 *   那是**误读**。当时用的验证用例是上报 `moment=60 / duration=120`，
 *   而它恰好满足 `60 × 2 = 120 >= 120` —— **正压在半数阈值上**（`>=` 是闭区间，
 *   一半本来就是"完成"的起点）。换个点测立刻就能看出后端在按百分比判断：
 *   `moment=59 → 118 < 120 → finished=false`。
 *
 *   这个误读让本页一度**放弃了播放中的定时上报**，只保留 4 个边界时机 ——
 *   而后端整套延迟合并机制本来就是按"播放中每 15 秒上报一次"推导的
 *   （进度缓存 TTL 30s = 上报间隔 15s + 延迟落库 5s），
 *   **没有高频上报，就没有"合并"可谈**。
 *
 * 所以本页现在分两层上报：
 *
 * 【A. 播放中】每 15 秒一次 —— `@play` 起定时器，`@pause` / `@ended` / 切动作 / 卸载时清掉。
 *   这才是"进度合并"的输入源。带上 `skipReload` 避免产生多余的读请求。
 *
 * 【B. 边界时机】这些是**必须准确**的：
 *   ① 视频**暂停**（自然停顿，是"我看到这"的真实信号）
 *   ② **切换动作**前（先把上一个的断点存下来）
 *   ③ **离开页面**前（路由切换时补一次）
 *   ④ 用户点**「标记完成」**（明确表达"练完了"，这时才用 moment = duration）
 *
 * ★ 为什么"频繁上报"是安全的：完成与否只取决于 `moment` 本身，**与上报频率无关** ——
 *   用户把进度条拖到 60% 再暂停，同样会被判完成。那是**规则**（过半即完成），不是 bug。
 *   反过来，定时上报还把原先的代价修掉了：以前"看到一半直接关标签页"会丢整段断点，
 *   现在最多丢 15 秒。
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

// ============================================================
// M10-1 第 1 期：笔记（边看边记）
// ============================================================

/**
 * 当前播放秒数 —— 笔记时间戳的来源。
 *
 * ★ 只在**整秒变化**时才写这个 ref。`timeupdate` 每秒触发约 4 次，
 *   每次都赋值会让本组件（含目录树、笔记列表）在播放时反复重渲染，肉眼可见地卡。
 */
const currentMoment = ref(0)

/** 右侧栏页签 */
const sideTab = ref<'catalog' | 'note'>('catalog')

/** 记笔记成功后 +1 → 笔记面板 watch 它刷新（比拿 ref 调子组件方法松耦合） */
const noteRefreshKey = ref(0)

/**
 * 当前动作所属的训练日 id。
 *
 * ★ `NoteFormDTO.chapterId` 是 `@NotNull`，而目录里 chapter 是 section 的**父级**
 *   （`Catalogues` 是 Day → sections 两层），所以这里要反查。
 *   查不到就返回空串 → 提交按钮禁用（宁可不让点，也不要带着空 chapterId 去换一个 400）。
 */
const currentChapterId = computed(() => {
  const sid = currentSection.value?.id
  if (!sid) return ''
  for (const day of catalogues.value) {
    if (day.type !== CataType.DAY) continue
    if ((day.sections ?? []).some((s) => s.id === sid)) return String(day.id)
  }
  return ''
})

/** 没选中动作、或反查不到训练日 → 不能记笔记 */
const noteDisabled = computed(() => !currentSection.value?.id || !currentChapterId.value)

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
 * 离开页面前：停掉定时上报，再把当前断点补一次。
 *
 * ★ 用 `onBeforeUnmount`（SPA 路由切换会正常触发）而不是 `pagehide`：
 *   `pagehide` 时页面正在卸载，异步请求经常发不出去，反而给人一种"上报了"的错觉。
 *   `onBeforeUnmount` 是同步拦得住的，所以这里能发出去。
 *
 *   剩下"直接关标签页"这一种情况确实发不出去 —— 但现在**播放中的定时上报每 15 秒
 *   就会把断点写进后端缓存**，所以最多丢 15 秒，而不是整段。
 */
onBeforeUnmount(async () => {
  stopProgressTimer()
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
  // 换动作会换 <video> 的 src，旧定时器必须先停 —— 否则它会继续拿旧动作的播放姿态上报
  stopProgressTimer()

  if (!opts.skipReport && currentSection.value && currentSection.value.id !== section.id) {
    await reportNow()
  }

  currentSection.value = section
  playUrl.value = ''
  videoFailed.value = false
  // ★ 换了动作 = 换了一条时间轴，旧的秒数必须清掉 ——
  //   否则在新动作刚加载出来的那一瞬间记笔记，会把**上一个动作的秒数**写进去。
  currentMoment.value = 0

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
 *
 * ★ `skipReload` 专给**播放中的定时上报**用：不回拉进度。
 *   播放中用户不需要盯着打勾状态，而暂停 / 切动作 / 标记完成这三个时机都会回拉，
 *   那里才是"看结果"的地方 —— 这样定时上报只产生 1 个 POST，不夹带多余的 GET。
 */
async function reportNow(
  opts: { silent?: boolean; asFinished?: boolean; skipReload?: boolean } = {},
) {
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
    if (!opts.skipReload) {
      await loadProgress()
    }
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

// ============================================================
// 播放中的定时上报（每 15 秒一次）
// ------------------------------------------------------------
// 这才是后端"延迟合并落库"的输入源：一段 40 分钟的视频 = 2400 ÷ 15 = 160 次上报，
// 后端把它们合并成"停播后的 1 次写库"。
// ============================================================

/**
 * 定时上报间隔（毫秒）。
 *
 * ★ 这个值不是随手拍的：后端的 `delay-seconds` 必须**大于**本间隔，合并才成立 ——
 *   否则任务会在下一次上报之前就到期被消费，`ZADD` 变成"新建"而非"覆盖"，
 *   每次上报都落库一次（实测：5s 时 8 次上报 → 8 次落库）。
 *   当前 `delay-seconds: 20 > 15s` ✅，要改这里请同步看
 *   `fm-learning/src/main/resources/application.yml`。
 */
const REPORT_INTERVAL_MS = 15_000

/** 定时上报句柄（暂停 / 播完 / 切动作 / 卸载都要清掉，否则定时器会泄漏） */
let progressTimer: ReturnType<typeof setInterval> | undefined

/** 开始播放 → 起定时上报 */
function startProgressTimer() {
  stopProgressTimer() // 先清再起：重复的 play 事件不能叠出多个定时器
  progressTimer = setInterval(() => {
    // 上一拍还没回来就跳过这一拍。定时上报是"可丢的"（下一次 15 秒后还有），
    // 绝不能让它和暂停上报叠在一起打同一个接口
    if (reporting.value) return
    void reportNow({ silent: true, skipReload: true })
  }, REPORT_INTERVAL_MS)
}

/** 停止定时上报（幂等） */
function stopProgressTimer() {
  if (progressTimer !== undefined) {
    clearInterval(progressTimer)
    progressTimer = undefined
  }
}

/** 暂停：停表 + 立刻补一次 —— 暂停是"我看到这"的真实信号，必须准确 */
async function onVideoPause() {
  stopProgressTimer()
  await reportNow({ silent: true })
}

/** 播完：`ended` **不会**触发 `pause`，所以这里得自己停表，否则会一直空转上报 */
async function onVideoEnded() {
  stopProgressTimer()
  await reportNow({ silent: true })
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
// 笔记：时间戳同步 与 跳转（M10-1 第 1 期）
// ============================================================

/**
 * 播放进度 → `currentMoment`。
 *
 * ★ 只在整秒变化时赋值：`timeupdate` 每秒约 4 次，无脑赋值会让整个播放页
 *   （目录树 + 笔记列表）跟着重渲染 —— 表现是"播视频时页面一直在抖"。
 */
function onTimeUpdate() {
  const video = videoRef.value
  if (!video) return
  const sec = Math.floor(video.currentTime)
  if (sec !== currentMoment.value) currentMoment.value = sec
}

/**
 * 点笔记的时间戳 → 跳到那一秒并继续播。
 *
 * ★ 这是整套笔记的价值所在：没有它，`note_moment` 只是一串没人看的数字。
 *   （同一套"读秒 + 跳转"的写法，断点续播那里已经在用了 —— 见 `selectSection`）
 */
function seekTo(moment: number) {
  const video = videoRef.value
  if (!video) return
  video.currentTime = moment
  // 浏览器可能因自动播放策略拒绝 play()，静默忽略即可 ——
  // 画面已经跳到目标位置了，那才是用户要的
  void video.play().catch(() => undefined)
}

/** 记完笔记：刷新列表 + 切到笔记页签（让用户立刻看到刚写的那条） */
function onNoteSaved() {
  noteRefreshKey.value += 1
  sideTab.value = 'note'
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
      <!-- ★ M10-1：播放器回到左侧（原布局是"目录在左、播放器被挤在右"） -->
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
              @play="startProgressTimer"
              @timeupdate="onTimeUpdate"
              @pause="onVideoPause"
              @ended="onVideoEnded"
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

          <!-- ★ M10-1：快捷笔记条 —— 边看边记，不用离开播放器 -->
          <NoteComposer
            v-if="currentSection"
            class="player-note"
            :course-id="courseId"
            :chapter-id="currentChapterId"
            :section-id="currentSection.id"
            :moment="currentMoment"
            :disabled="noteDisabled"
            @saved="onNoteSaved"
          />

          <el-alert class="timing-tip" type="info" :closable="false" show-icon>
            <template #title>
              进度在<b>播放中每 15 秒</b>自动上报，暂停 / 切换动作 / 离开页面时也会补一次。
              完成与否由后端按<b>播放过半</b>判定（与上报频率无关）；点「标记完成」则直接记为已完成。
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

        <!-- ★ M10-1 第 2 期：问答讨论区（B 站式评论区） -->
        <!--
          ⚠️ 位置是**在"动作参数"之后**，不是紧跟播放器。
          方案 §三 的线框图原先把评论区画在播放器正下方，实做时改到了这里，原因：
            ① "动作参数"（组数/次数/负重）是**看视频时要随时参考**的，把它挤到评论区
               后面就得滚动才能看，反而更别扭；
            ② 参数卡片里挂着**小测入口**（`learn-open-quiz`），而 m7-6 的验收脚本要
               点它 —— 一旦把这张卡片折叠或挪到很靠下的位置，那条断言就有变红的风险。
          评论区要"看完这一段、想说点什么"时才用，放在参数之后正合动线。
        -->
        <el-card v-if="currentSection" shadow="never" class="qa-card">
          <template #header>
            <span>问答讨论区</span>
          </template>
          <QaPanel
            :course-id="courseId"
            :chapter-id="currentChapterId"
            :section-id="currentSection.id"
          />
        </el-card>
      </div>

      <!-- ★ M10-1：右侧栏 —— 目录 / 笔记 两个页签（原来整个目录卡片占着左侧） -->
      <el-card shadow="never" class="side-card">
        <el-tabs v-model="sideTab" class="side-tabs" data-testid="learn-side-tabs">
          <el-tab-pane label="目录" name="catalog">
            <p class="catalog-tip-line">✓ 表示已有学习记录</p>

            <el-empty
              v-if="!flatSections.length"
              description="这门课还没有训练动作"
              :image-size="60"
            />

            <el-collapse
              v-else
              :model-value="catalogues.map((d) => d.id)"
              data-testid="learn-catalog"
            >
              <el-collapse-item v-for="day in catalogues" :key="day.id" :name="day.id">
                <template #title>
                  <span class="day-title">
                    Day {{ day.index }} · {{ day.name }}
                    <span class="day-count">
                      {{ (day.sections ?? []).filter((s) => isFinished(s.id)).length }}/{{
                        (day.sections ?? []).length
                      }}
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
                  <el-tag
                    v-if="sec.subjectNum"
                    class="quiz-badge"
                    size="small"
                    type="warning"
                    effect="plain"
                  >
                    {{ sec.subjectNum }} 题
                  </el-tag>
                  <span class="section-duration">{{ formatDuration(sec.mediaDuration) }}</span>
                </div>
              </el-collapse-item>
            </el-collapse>
          </el-tab-pane>

          <el-tab-pane label="笔记" name="note">
            <NotePanel
              :course-id="courseId"
              :section-id="currentSection?.id"
              :refresh-key="noteRefreshKey"
              @seek="seekTo"
            />
          </el-tab-pane>
        </el-tabs>
      </el-card>
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
  /* ★ M10-1：1180 → 1400。要让"播放器 + 380px 侧栏"都放得下，
     再按 1180 会把视频压回原来的宽度，白改一场 */
  max-width: 1400px;
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
  /* ★ M10-1：原来是 `320px 1fr`（目录在左、播放器被挤在右）。
     现在视频是主角，回到左侧；侧栏固定 380px 装"目录 / 笔记"。
     `minmax(0, 1fr)` 而不是 `1fr`：不加 `minmax(0, …)` 时，
     子元素里长内容（视频/目录里的长动作名）会把整列撑破。 */
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 16px;
  align-items: start;
}

/* 右侧栏：卡片内边距收一点，页签本身有留白 */
.side-card :deep(.el-card__body) {
  padding: 8px 14px 14px;
}

.side-tabs :deep(.el-tabs__header) {
  margin-bottom: 10px;
}

.catalog-tip-line {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 快捷笔记条与"上一个 / 标记完成 / 下一个"之间留一口气 */
.player-note {
  margin-top: 14px;
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
