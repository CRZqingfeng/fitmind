<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { pageMyLessons, removeLesson } from '@/api/learning'
import type { LearningLesson } from '@/types/learning'
import { LessonStatus } from '@/types/learning'
import { formatDateTime, pageTotal } from '@/utils/format'

/**
 * 我的课程（M7-4）
 *
 * 课表从哪来：① 付费课**支付成功后**由 fm-trade 发 MQ 写入（不用前端做任何事）；
 * ② 免费课由详情页调 `POST /ls/lessons/{courseId}` 直接报名。
 * 两条路最后落到同一张表 —— 所以这个页面不需要区分课程是怎么来的。
 *
 * ★ 状态 tab 的取值抄自后端 `LearningLessonVO.status`：
 *   0-未学习 / 1-学习中 / 2-已学完 / 3-已过期
 */
const router = useRouter()

const TAB_ALL = -1
const tabs = [
  { label: '全部', value: TAB_ALL },
  { label: '未学习', value: LessonStatus.NOT_STARTED },
  { label: '学习中', value: LessonStatus.LEARNING },
  { label: '已学完', value: LessonStatus.FINISHED },
  { label: '已过期', value: LessonStatus.EXPIRED },
]

const activeTab = ref<number>(TAB_ALL)
const list = ref<LearningLesson[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(6)
const loading = ref(false)

/** 封面加载失败兜底（与 CourseCard 同一套做法） */
const coverFailed = reactive<Record<string, boolean>>({})

const isEmpty = computed(() => !loading.value && list.value.length === 0)

async function load() {
  loading.value = true
  try {
    const page = await pageMyLessons({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      status: activeTab.value === TAB_ALL ? undefined : activeTab.value,
    })
    list.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

function handleTabChange() {
  pageNo.value = 1
  load()
}

/** 进度百分比（★ 只用后端给的 learnedSections / sections 算，不在前端另存一份进度） */
function percentOf(lesson: LearningLesson): number {
  const totalSections = lesson.sections ?? 0
  if (totalSections <= 0) return 0
  const learned = lesson.learnedSections ?? 0
  return Math.min(100, Math.round((learned / totalSections) * 100))
}

function statusTagType(status: number): 'success' | 'info' | 'warning' | 'primary' {
  if (status === LessonStatus.FINISHED) return 'success'
  if (status === LessonStatus.LEARNING) return 'primary'
  if (status === LessonStatus.EXPIRED) return 'warning'
  return 'info'
}

function primaryActionLabel(lesson: LearningLesson): string {
  if (lesson.status === LessonStatus.NOT_STARTED) return '开始训练'
  if (lesson.status === LessonStatus.LEARNING) return '继续训练'
  return '再看一遍'
}

async function handleRemove(lesson: LearningLesson) {
  try {
    await ElMessageBox.confirm(
      `确定要把《${lesson.courseName}》从我的课程里移除吗？\n（只移除学习入口，订单与已付金额不受影响）`,
      '提示',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }
  await removeLesson(lesson.courseId)
  ElMessage.success('已移除')
  await load()
}
</script>

<template>
  <div class="my-courses-page">
    <div class="page-head">
      <h2>我的课程</h2>
      <span class="head-count" data-testid="mycourse-total">共 {{ total }} 门</span>
    </div>

    <el-tabs v-model="activeTab" class="status-tabs" @tab-change="handleTabChange">
      <el-tab-pane v-for="tab in tabs" :key="tab.label" :label="tab.label" :name="tab.value" />
    </el-tabs>

    <el-empty v-if="isEmpty" description="还没有报名任何课程" data-testid="mycourse-empty">
      <el-button type="primary" @click="router.push('/courses')">去挑课程</el-button>
    </el-empty>

    <div v-loading="loading" class="course-grid">
      <el-card
        v-for="lesson in list"
        :key="lesson.id"
        shadow="never"
        class="course-card"
        data-testid="mycourse-card"
      >
        <div class="card-cover" @click="router.push(`/learn/${lesson.courseId}`)">
          <img
            v-if="lesson.coverUrl && !coverFailed[lesson.courseId]"
            :src="lesson.coverUrl"
            :alt="lesson.courseName"
            @error="coverFailed[lesson.courseId] = true"
          />
          <div v-else class="cover-fallback">{{ lesson.courseName.slice(0, 1) }}</div>
          <el-tag class="cover-tag" :type="statusTagType(lesson.status)" size="small" effect="dark">
            {{ lesson.statusDesc }}
          </el-tag>
        </div>

        <div class="card-body">
          <div class="card-name" @click="router.push(`/learn/${lesson.courseId}`)">
            {{ lesson.courseName }}
          </div>

          <div class="progress-row">
            <el-progress
              :percentage="percentOf(lesson)"
              :stroke-width="8"
              :show-text="false"
              class="progress-bar"
            />
            <span class="progress-text" data-testid="mycourse-progress">
              {{ lesson.learnedSections ?? 0 }} / {{ lesson.sections ?? 0 }}
            </span>
          </div>

          <div class="card-meta">
            <span v-if="lesson.planStatus === 1" class="plan-badge">
              计划中 · 每周 {{ lesson.weekFreq ?? '—' }} 次
            </span>
            <span v-else class="plan-none">未加入训练计划</span>
          </div>

          <div class="card-meta">
            <span v-if="lesson.latestLearnTime">
              最近学习 {{ formatDateTime(lesson.latestLearnTime) }}
            </span>
            <span v-else>还没开始</span>
          </div>

          <div class="card-actions">
            <el-button
              type="primary"
              size="small"
              data-testid="mycourse-continue"
              @click="router.push(`/learn/${lesson.courseId}`)"
            >
              {{ primaryActionLabel(lesson) }}
            </el-button>
            <el-button size="small" @click="router.push(`/course/${lesson.courseId}`)">
              课程详情
            </el-button>
            <el-button size="small" type="danger" link @click="handleRemove(lesson)">
              移除
            </el-button>
          </div>
        </div>
      </el-card>
    </div>

    <el-pagination
      v-if="total > pageSize"
      v-model:current-page="pageNo"
      class="pager"
      layout="prev, pager, next"
      :page-size="pageSize"
      :total="total"
      @current-change="load"
    />
  </div>
</template>

<style scoped>
.my-courses-page {
  max-width: 1080px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 8px;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.head-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.status-tabs {
  margin-bottom: 8px;
}

.course-grid {
  min-height: 140px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.course-card {
  overflow: hidden;
}

.course-card :deep(.el-card__body) {
  padding: 0;
}

.card-cover {
  position: relative;
  height: 132px;
  cursor: pointer;
  background: var(--el-fill-color-light);
}

.card-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 42px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #ff8a5b, #e5533d);
}

.cover-tag {
  position: absolute;
  top: 10px;
  right: 10px;
}

.card-body {
  padding: 14px 16px 16px;
}

.card-name {
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-name:hover {
  color: var(--el-color-primary);
}

.progress-row {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.progress-bar {
  flex: 1;
}

.progress-text {
  font-size: 13px;
  color: var(--el-text-color-regular);
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

.card-meta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.plan-badge {
  color: var(--el-color-primary);
}

.plan-none {
  color: var(--el-text-color-placeholder);
}

.card-actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.pager {
  margin-top: 24px;
  justify-content: center;
}
</style>
