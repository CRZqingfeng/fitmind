<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'

import { getMyPlans, pageMyLessons, savePlan } from '@/api/learning'
import type { LearningLesson, LearningPlan, LearningPlanPage } from '@/types/learning'
import { PlanStatus } from '@/types/learning'

/**
 * 训练计划（M7-4）
 *
 * 一个"计划"就是"给某门课定一个每周训练频率"（`freq`），后端据此算
 * `weekTotalPlan`（本周该练几次）与 `weekFinished`（实际练了几个动作）。
 *
 * ⚠️ 后端**只有"创建/更新计划"这一个写接口**（`POST /ls/lessons/plans`），
 *   **没有"退出计划"**。所以这个页面不提供"移出计划"按钮 ——
 *   与其放一个点了没反应的按钮，不如把这件事说清楚（见页面底部的说明）。
 *
 * ⚠️ `freq` 范围 **1~7**，后端会校验并返回 `code: 400`
 *   + "每周训练频率需在 1~7 之间"。前端用 `el-input-number` 卡住，
 *   但不把它当唯一防线。
 */
const router = useRouter()

const loading = ref(true)
const page = ref<LearningPlanPage | null>(null)
/** 我的全部课程（用来找出"还没加入计划"的） */
const allLessons = ref<LearningLesson[]>([])

/** 每门课编辑中的频次（按 courseId 存，避免改动互相串） */
const editing = reactive<Record<string, number>>({})
const saving = reactive<Record<string, boolean>>({})

const plans = computed<LearningPlan[]>(() => page.value?.list ?? [])

/** 还没加入计划的课（有课表，但 planStatus != 1） */
const withoutPlan = computed(() =>
  allLessons.value.filter((l) => l.planStatus !== PlanStatus.ONGOING),
)

/** 本周进度百分比 */
const weekPercent = computed(() => {
  const total = page.value?.weekTotalPlan ?? 0
  if (total <= 0) return 0
  const done = page.value?.weekFinished ?? 0
  return Math.min(100, Math.round((done / total) * 100))
})

const weekOver = computed(() => {
  const total = page.value?.weekTotalPlan ?? 0
  return total > 0 && (page.value?.weekFinished ?? 0) >= total
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [p, lessons] = await Promise.all([
      getMyPlans(),
      pageMyLessons({ pageNo: 1, pageSize: 100 }),
    ])
    page.value = p
    allLessons.value = lessons?.list ?? []
    // 初始化每门课的可编辑频次
    for (const item of p?.list ?? []) {
      editing[item.courseId] = item.weekFreq ?? 3
    }
    for (const l of allLessons.value) {
      if (editing[l.courseId] === undefined) editing[l.courseId] = l.weekFreq ?? 3
    }
  } catch {
    page.value = null
    allLessons.value = []
  } finally {
    loading.value = false
  }
}

/** 进度百分比（复用"我的课程"的口径：learnedSections / sections） */
function percentOf(item: LearningPlan | LearningLesson): number {
  const total = item.sections ?? 0
  if (total <= 0) return 0
  return Math.min(100, Math.round(((item.learnedSections ?? 0) / total) * 100))
}

async function handleSave(courseId: string, freq: number) {
  if (freq === undefined || freq === null || Number.isNaN(freq)) {
    ElMessage.warning('请先选择每周训练次数')
    return
  }
  saving[courseId] = true
  try {
    await savePlan(courseId, freq)
    ElMessage.success('计划已保存')
    // ★ 重新拉接口而不是本地改数字：weekTotalPlan 是后端把各课 freq 加起来算的，
    //   本地改一个数字会让"本周计划总次数"和列表对不上
    await load()
  } catch {
    /* 后端已给出原因（如"每周训练频率需在 1~7 之间"） */
  } finally {
    saving[courseId] = false
  }
}
</script>

<template>
  <div v-loading="loading" class="plans-page">
    <div class="page-head">
      <h2>训练计划</h2>
      <span class="head-count">给课程定一个每周频率，系统按它算本周进度</span>
    </div>

    <!-- 本周概览 -->
    <el-card shadow="never" class="week-card">
      <div class="week-box">
        <div class="week-left">
          <div class="week-label">本周进度</div>
          <div class="week-value">
            <b data-testid="plan-week-finished">{{ page?.weekFinished ?? 0 }}</b>
            <span class="week-slash">/</span>
            <span data-testid="plan-week-total">{{ page?.weekTotalPlan ?? 0 }}</span>
            <span class="week-unit">次</span>
          </div>
          <div class="week-hint">
            <template v-if="weekOver">本周目标已完成，保持住 💪</template>
            <template v-else-if="(page?.weekTotalPlan ?? 0) > 0">
              还差 {{ (page?.weekTotalPlan ?? 0) - (page?.weekFinished ?? 0) }} 次
            </template>
            <template v-else>还没有进行中的计划，从下面挑一门课开始吧</template>
          </div>
        </div>
        <el-progress
          type="dashboard"
          :percentage="weekPercent"
          :width="120"
          :color="weekOver ? '#67c23a' : '#ff6b35'"
        />
      </div>
    </el-card>

    <!-- 进行中的计划 -->
    <el-card shadow="never" class="block">
      <template #header>
        <span class="block-title">进行中的计划（{{ plans.length }}）</span>
      </template>

      <el-empty
        v-if="!plans.length"
        description="还没有训练计划"
        :image-size="70"
        data-testid="plan-empty"
      />

      <div
        v-for="item in plans"
        :key="item.id"
        class="plan-row"
        data-testid="plan-card"
      >
        <div class="plan-main">
          <div class="plan-name" @click="router.push(`/learn/${item.courseId}`)">
            {{ item.courseName }}
          </div>
          <div class="plan-sub">
            {{ item.statusDesc }} · 已完成 {{ item.learnedSections ?? 0 }}/{{ item.sections ?? 0 }} 个动作
          </div>
          <el-progress
            :percentage="percentOf(item)"
            :stroke-width="6"
            :show-text="false"
            class="plan-progress"
          />
        </div>

        <div class="plan-freq">
          <span class="freq-label">每周</span>
          <el-input-number
            v-model="editing[item.courseId]"
            :min="1"
            :max="7"
            size="small"
            controls-position="right"
            style="width: 100px"
          />
          <span class="freq-label">次</span>
        </div>

        <div class="plan-actions">
          <el-button
            type="primary"
            size="small"
            :loading="saving[item.courseId]"
            data-testid="plan-save"
            @click="handleSave(item.courseId, editing[item.courseId])"
          >
            保存
          </el-button>
          <el-button size="small" @click="router.push(`/learn/${item.courseId}`)">
            去训练
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 可加入计划的课程 -->
    <el-card v-if="withoutPlan.length" shadow="never" class="block">
      <template #header>
        <span class="block-title">未加入计划的课程（{{ withoutPlan.length }}）</span>
      </template>

      <div
        v-for="lesson in withoutPlan"
        :key="lesson.id"
        class="plan-row"
        data-testid="plan-available"
      >
        <div class="plan-main">
          <div class="plan-name">{{ lesson.courseName }}</div>
          <div class="plan-sub">{{ lesson.statusDesc }} · 还没定训练频率</div>
        </div>

        <div class="plan-freq">
          <span class="freq-label">每周</span>
          <el-input-number
            v-model="editing[lesson.courseId]"
            :min="1"
            :max="7"
            size="small"
            controls-position="right"
            style="width: 100px"
          />
          <span class="freq-label">次</span>
        </div>

        <div class="plan-actions">
          <el-button
            size="small"
            :loading="saving[lesson.courseId]"
            @click="handleSave(lesson.courseId, editing[lesson.courseId])"
          >
            加入计划
          </el-button>
        </div>
      </div>
    </el-card>

    <el-alert class="note" type="info" :closable="false" show-icon>
      <template #title>
        目前后端只提供"创建 / 更新计划"接口，<b>没有"退出计划"</b> ——
        所以这里没有放一个点了没反应的"移出"按钮。若要停掉某门课的计划，可以先把频率改成 1 次/周。
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
.plans-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.head-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.week-card {
  margin-bottom: 16px;
}

.week-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.week-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.week-value {
  margin-top: 4px;
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.week-value b {
  font-size: 34px;
  color: var(--el-color-primary);
}

.week-slash {
  font-size: 20px;
  color: var(--el-text-color-placeholder);
}

.week-value span:not(.week-slash):not(.week-unit) {
  font-size: 24px;
  color: var(--el-text-color-regular);
}

.week-unit {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.week-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.block {
  margin-bottom: 16px;
}

.block-title {
  font-weight: 600;
}

.plan-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.plan-row:last-child {
  border-bottom: none;
}

.plan-main {
  flex: 1;
  min-width: 0;
}

.plan-name {
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-name:hover {
  color: var(--el-color-primary);
}

.plan-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.plan-progress {
  margin-top: 8px;
}

.plan-freq {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.freq-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.plan-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.note {
  margin-top: 4px;
}
</style>
