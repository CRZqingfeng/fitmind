<script setup lang="ts">
import type { DietLogItem } from '@/types/plan'

/**
 * 食物行（餐次卡里的一条记录）。
 *
 * ★ 份量与热量都直接渲染后端给的值（`amount + unit` 与 `kcal`）。
 *   前端不重算：`gram` 也在返回值里，但那是给"这碗到底多少克"用的，
 *   不是用来反算热量的 —— 反算就会和 `NutritionCalculator` 的口径分叉。
 */
defineProps<{
  item: DietLogItem
  /** 只读（例如看历史某天）时不显示删除 */
  readonly?: boolean
}>()

const emit = defineEmits<{
  (e: 'remove', item: DietLogItem): void
}>()

function fmtAmount(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}
</script>

<template>
  <div class="food-row">
    <span class="emoji">{{ item.emoji || '🍽' }}</span>
    <div class="info">
      <div class="name">{{ item.foodName }}</div>
      <div class="amount">{{ fmtAmount(item.amount) }}{{ item.unit }}</div>
    </div>
    <span class="kcal">{{ item.kcal }} 千卡</span>
    <el-button
      v-if="!readonly"
      text
      size="small"
      type="danger"
      @click="emit('remove', item)"
    >
      删
    </el-button>
  </div>
</template>

<style scoped>
.food-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.food-row:last-child {
  border-bottom: none;
}

.emoji {
  font-size: 20px;
  width: 26px;
  text-align: center;
  flex-shrink: 0;
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 14px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.amount {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 1px;
}

.kcal {
  font-size: 13px;
  color: var(--el-text-color-regular);
  font-variant-numeric: tabular-nums;
}
</style>
