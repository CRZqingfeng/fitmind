<script setup lang="ts">
import { computed } from 'vue'

import { useRouter } from 'vue-router'

import CourseCover from '@/components/CourseCover.vue'
import type { CourseSearchVO } from '@/types/course'
import { GOAL_TYPES, TRAINING_LEVELS, labelOf } from '@/types/course'
import { formatCount, formatDuration, formatPrice, safeHighlight } from '@/utils/format'

/**
 * 课程卡片（首页推荐 / 列表 / 详情页推荐位通用）
 *
 * 两个刻意的设计：
 *  ① **封面必须有降级** —— 见 `CourseCover.vue`（已抽成公共组件：
 *    AI 教练的卡片也要用同一套兜底色，否则同一门课在两处会显示成不同颜色）。
 *  ② **高亮名走 safeHighlight**：`highlightName` 里有 ES 塞的 `<em>`，
 *    但它的原料是课程名（可输入内容）。直接 v-html 就是 XSS —— 见 utils/format.ts 的说明。
 */
const props = defineProps<{ course: CourseSearchVO }>()

const router = useRouter()

const hasHighlight = computed(() => !!props.course.highlightName)
const highlightHtml = computed(() => safeHighlight(props.course.highlightName))

const priceText = computed(() => formatPrice(props.course.price, props.course.free))
const goalLabel = computed(() => labelOf(GOAL_TYPES, props.course.goalType))
const levelLabel = computed(() => labelOf(TRAINING_LEVELS, props.course.trainingLevel))

const teacherText = computed(() => (props.course.teacherNames || []).join('、'))

const durationText = computed(() => formatDuration(props.course.mediaDuration))
const soldText = computed(() => formatCount(props.course.sold))

function goDetail() {
  router.push({ name: 'course-detail', params: { id: props.course.id } })
}
</script>

<template>
  <div
    class="course-card"
    data-testid="course-card"
    role="button"
    tabindex="0"
    @click="goDetail"
    @keydown.enter="goDetail"
  >
      <CourseCover :cover-url="course.coverUrl" :name="course.name">
        <span v-if="course.free" class="badge-free">免费</span>
      </CourseCover>

    <div class="card-body">
      <h3 class="card-title" data-testid="course-name">
        <span v-if="hasHighlight" v-html="highlightHtml" />
        <template v-else>{{ course.name }}</template>
      </h3>

      <div class="card-path" data-testid="course-path">{{ course.categoryPath || '未分类' }}</div>

      <div class="card-tags">
        <el-tag v-if="goalLabel" size="small" type="warning" effect="plain">{{ goalLabel }}</el-tag>
        <el-tag v-if="levelLabel" size="small" effect="plain">{{ levelLabel }}</el-tag>
        <el-tag v-if="course.sections" size="small" type="info" effect="plain">
          {{ course.sections }} 个动作
        </el-tag>
      </div>

      <div class="card-meta">
        <span class="card-price" data-testid="course-price">{{ priceText }}</span>
        <span class="card-stats">
          <span v-if="durationText !== '—'">{{ durationText }}</span>
          <span v-if="soldText !== '—' && course.sold">{{ soldText }} 人报名</span>
        </span>
      </div>

      <div v-if="teacherText" class="card-teacher">教练：{{ teacherText }}</div>
    </div>
  </div>
</template>

<style scoped>
.course-card {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--fm-radius);
  overflow: hidden;
  cursor: pointer;
  outline: none;
  transition: box-shadow 0.18s ease, transform 0.18s ease;
}

.course-card:hover,
.course-card:focus-visible {
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.badge-free {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  color: #fff;
  background: var(--el-color-success);
}

.card-body {
  padding: 12px 14px 14px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
}

.card-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 42px;
}

/* 搜索命中词的样式（后端返回的 <em>） */
.card-title :deep(em) {
  font-style: normal;
  color: var(--el-color-primary);
  font-weight: 700;
}

.card-path {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  min-height: 24px;
}

.card-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
}

.card-price {
  font-size: 17px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.card-stats {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.card-teacher {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
