<script setup lang="ts">
import { computed } from 'vue'

/**
 * 三大营养素进度条（碳水 / 蛋白 / 脂肪）。
 *
 * ★ current 与 target 都由后端给，这里**只做百分比换算用于画进度条**：
 *   百分比是"视觉参数"，不是业务数字 —— 首页要显示的"0/324"直接渲染两个数，
 *   不经过这里算。前端真正不能做的是"算还可以吃"，那个必须读 remainKcal。
 *
 * ★ target 为 0（还没记体重）时不能除零：显示"—/—"而不是 0%。
 */
const props = defineProps<{
  label: string
  current: number
  target: number
  unit: string
}>()

/** 进度条百分比：封顶 100（吃超了也用满格表示，真实超出量看文字） */
const percent = computed(() => {
  if (!props.target || props.target <= 0) return 0
  return Math.min(100, Math.round((props.current / props.target) * 100))
})

/** 数值展示：整数就不带小数点，"120.0" 看着像精度很高其实没必要 */
function fmt(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}

const hasTarget = computed(() => !!props.target && props.target > 0)
const overflow = computed(() => hasTarget.value && props.current > props.target)
</script>

<template>
  <div class="macro-bar" :class="{ overflow }">
    <div class="head">
      <span class="label">{{ label }}</span>
      <span class="value">
        <template v-if="hasTarget">{{ fmt(current) }} / {{ fmt(target) }}{{ unit }}</template>
        <template v-else>{{ fmt(current) }}{{ unit }} / —</template>
      </span>
    </div>
    <el-progress
      :percentage="percent"
      :stroke-width="6"
      :show-text="false"
      :status="overflow ? 'warning' : undefined"
    />
  </div>
</template>

<style scoped>
.macro-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.value {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.macro-bar.overflow .value {
  color: var(--el-color-warning);
}
</style>
