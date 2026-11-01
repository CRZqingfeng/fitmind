<script setup lang="ts">
import { computed } from 'vue'

import { toJin, toKg } from '@/utils/format'

/**
 * 一组（执行页）
 *
 * ★ 重量单位：**接口和数据库都是 kg，界面显示"斤"**。
 *   换算只发生在 `utils/format.ts` 的 `toJin / toKg` 一对函数里，
 *   本组件只负责展示与回传，不自己乘 2 —— 否则总有一天会冒出一处乘了两次的地方。
 *
 * ★ 计时型动作（平板支撑 / 跳绳）没有"次数"，显示秒数即可。
 */
const props = defineProps<{
  setNo: number
  /** 重量，单位 **kg**（来自接口） */
  weightKg: number | null
  reps: number
  /** 计时型单组秒数；>0 时这一行显示"X 秒"而不是次数 */
  durationSec: number
  done: boolean
  disabled?: boolean
  /**
   * 打勾框是否单独禁用。
   *
   * ★ 为什么要和 `disabled` 分开：已打勾的组，重量/次数输入框该锁住（数值已经落库了），
   *   但**勾选框必须还能点** —— 点一下就是"撤销这一组"。
   *   用一个 `disabled` 同时管住两者，结果就是"勾上去就再也点不动"。
   */
  toggleDisabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:weightKg', value: number | null): void
  (e: 'update:reps', value: number): void
  (e: 'toggle'): void
}>()

/** 斤 = kg × 2（换算只在 utils/format.ts 一处，这里只取数） */
const weightJin = computed(() => toJin(props.weightKg))

function onWeightChange(value: number | undefined) {
  // 空值 → 传 null（后端会用计划里的建议重量兜底）
  emit('update:weightKg', value === undefined || value === null ? null : toKg(value))
}

function onRepsChange(value: number | undefined) {
  emit('update:reps', value ?? 0)
}
</script>

<template>
  <div class="set-row" :class="{ done }">
    <span class="no">第 {{ setNo }} 组</span>

    <template v-if="durationSec > 0">
      <span class="timed">{{ durationSec }} 秒</span>
    </template>

    <template v-else>
      <el-input-number
        :model-value="weightJin ?? undefined"
        :min="0"
        :max="500"
        :step="2.5"
        :precision="1"
        size="small"
        controls-position="right"
        class="num"
        :disabled="disabled"
        @update:model-value="onWeightChange"
      />
      <span class="unit">斤</span>

      <el-input-number
        :model-value="reps"
        :min="0"
        :max="99"
        size="small"
        controls-position="right"
        class="num"
        :disabled="disabled"
        @update:model-value="onRepsChange"
      />
      <span class="unit">次</span>
    </template>

    <el-checkbox :model-value="done" :disabled="toggleDisabled ?? disabled" @change="emit('toggle')" />
  </div>
</template>

<style scoped>
.set-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.set-row.done {
  background: var(--el-color-success-light-9);
}

.no {
  font-size: 13px;
  color: var(--el-text-color-regular);
  width: 64px;
  flex-shrink: 0;
}

.num {
  width: 96px;
}

.unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.timed {
  font-size: 13px;
  color: var(--el-text-color-primary);
  flex: 1;
}
</style>
