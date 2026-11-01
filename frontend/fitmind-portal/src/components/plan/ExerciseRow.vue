<script setup lang="ts">
import type { PlanDayItem } from '@/types/plan'
import { GroupType } from '@/types/plan'

/**
 * 动作行（详情页 / 编排页 / 执行页共用的"缩略图 + 名称 + 标签 + 组数次数"）
 *
 * ★ 展示串 `prescription`（"共4组·每组8次·组休60秒"）由**后端**拼。
 *   前端拼的话，计时型动作（平板支撑）会显示成"每组0次"——
 *   而这个 if 判断散在三个页面就是三份 bug。
 */
const props = defineProps<{
  item: PlanDayItem
  /** 编排页：显示删除按钮 */
  removable?: boolean
}>()

const emit = defineEmits<{
  (e: 'remove'): void
}>()

/** 热身组 / 拉伸组要显式标出来（正式组不标，否则整列都是标签反而看不见重点） */
function groupTypeTag(): 'warning' | 'info' | null {
  if (props.item.groupType === GroupType.WARMUP) return 'warning'
  if (props.item.groupType === GroupType.STRETCH) return 'info'
  return null
}
</script>

<template>
  <div class="exercise-row">
    <el-image :src="item.imageUrl || ''" fit="cover" class="thumb">
      <template #error>
        <div class="thumb-ph">{{ item.exerciseName.slice(0, 1) }}</div>
      </template>
    </el-image>

    <div class="info">
      <div class="name-line">
        <span class="name">{{ item.exerciseName }}</span>
        <el-tag v-if="groupTypeTag()" size="small" :type="groupTypeTag()!" effect="plain">
          {{ item.groupTypeLabel }}
        </el-tag>
      </div>
      <div class="prescription">{{ item.prescription }}</div>
    </div>

    <el-button
      v-if="removable"
      text
      type="danger"
      size="small"
      @click.stop="emit('remove')"
    >
      移除
    </el-button>
  </div>
</template>

<style scoped>
.exercise-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.exercise-row:last-child {
  border-bottom: none;
}

.thumb {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  flex-shrink: 0;
  background: var(--el-fill-color-light);
}

.thumb-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
}

.info {
  flex: 1;
  min-width: 0;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.name {
  font-size: 14px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prescription {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
</style>
