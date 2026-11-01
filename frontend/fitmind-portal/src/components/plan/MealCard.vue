<script setup lang="ts">
import type { DietLogItem, DietMeal } from '@/types/plan'

import FoodRow from './FoodRow.vue'

/**
 * 餐次卡（早/午/晚/加餐）。
 *
 * ★ 四张卡**永远都在**（后端固定返回 4 条，没记的也是空壳）：
 *   只渲染"有记录的那几餐"的话，用户早上打开会看到一片空白，
 *   第一反应是"页面坏了"，而不是"我还没记"。
 *
 * ★ 空态的文案是"还没记，点右边添加"，不是一个空 div。
 */
defineProps<{
  meal: DietMeal
  readonly?: boolean
}>()

const emit = defineEmits<{
  (e: 'add', meal: DietMeal): void
  (e: 'remove', item: DietLogItem): void
}>()
</script>

<template>
  <div class="meal-card">
    <div class="head">
      <div class="left">
        <span class="label">{{ meal.label }}</span>
        <span class="kcal">{{ meal.kcal }} 千卡</span>
      </div>
      <el-button v-if="!readonly" text type="primary" size="small" @click="emit('add', meal)">
        + 添加食物
      </el-button>
    </div>

    <div v-if="meal.items.length === 0" class="empty">还没记，点右边「添加食物」</div>
    <div v-else class="items">
      <FoodRow
        v-for="item in meal.items"
        :key="item.id"
        :item="item"
        :readonly="readonly"
        @remove="emit('remove', $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.meal-card {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 10px 14px;
  margin-bottom: 10px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.label {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.kcal {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  padding: 8px 0 4px;
}

.items {
  margin-top: 4px;
}
</style>
