<script setup lang="ts">
import type { PlanDay, PlanDayItem } from '@/types/plan'

import ExerciseRow from './ExerciseRow.vue'

/**
 * 训练日卡片（详情页 / 编排页共用）
 *
 * ★ `summary`（"7个动作·预计43分钟"）与 `estMinutes` 都来自后端。
 *   前端自己算一份的下场：演示时两个数字对不上，而用户会以为是你算错了。
 *
 * ★ 空训练日的文案是「还没有动作」（后端给的），
 *   前端不要拼"0个动作·预计0分钟"——那句话看着像算错了。
 */
defineProps<{
  day: PlanDay
  /** 编排页：显示"添加动作 / 删除动作" */
  editable?: boolean
}>()

const emit = defineEmits<{
  (e: 'add-items', day: PlanDay): void
  (e: 'remove-item', day: PlanDay, item: PlanDayItem): void
}>()
</script>

<template>
  <div class="day-card" data-testid="day-card">
    <div class="head">
      <div class="name-row">
        <span class="weekday">{{ day.weekdayLabel }}</span>
        <span class="name">{{ day.name }}</span>
      </div>
      <div class="head-right">
        <span class="summary">{{ day.summary }}</span>
        <el-button v-if="editable" size="small" type="primary" plain @click="emit('add-items', day)">
          添加动作
        </el-button>
      </div>
    </div>

    <el-empty v-if="day.items.length === 0" description="这一天还没有动作" :image-size="60" />

    <div v-else class="items">
      <ExerciseRow
        v-for="item in day.items"
        :key="item.id"
        :item="item"
        :removable="editable"
        @remove="emit('remove-item', day, item)"
      />
    </div>
  </div>
</template>

<style scoped>
.day-card {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 12px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.name-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.weekday {
  font-size: 12px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  padding: 1px 8px;
  border-radius: 10px;
}

.name {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.items {
  display: flex;
  flex-direction: column;
}
</style>
