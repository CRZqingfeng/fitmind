<script setup lang="ts">
import type { PlanCard } from '@/types/plan'

/**
 * 计划卡片（推荐列表 / 我的计划共用）
 *
 * ★ 卡片上的所有文字都来自后端：
 *   `subtitle`（"一周5练 · 增肌 · 进阶"）是后端拼好的，
 *   前端再拼一次的话，改一次文案要动两个仓库。
 *
 * ★ 封面可能为空（P0 的种子计划没有封面图），也可能加载失败
 *   （图片走 ghfast 代理，代理挂掉就 404）。
 *   所以必须有兜底：**渐变底 + 计划名首字**，而不是一个破图标。
 */
const props = defineProps<{
  plan: PlanCard
}>()

const emit = defineEmits<{
  (e: 'open'): void
}>()

/** 首页大卡用第一张的强调色，避免整页一个色调 */
function coverGradient(seed: string): string {
  const palettes = [
    'linear-gradient(135deg, #4f7cff 0%, #7aa2ff 100%)',
    'linear-gradient(135deg, #ff8a4c 0%, #ffb47a 100%)',
    'linear-gradient(135deg, #22b07d 0%, #6ed3a8 100%)',
    'linear-gradient(135deg, #8b5cf6 0%, #b794f6 100%)',
  ]
  const n = [...seed].reduce((acc, c) => acc + c.charCodeAt(0), 0)
  return palettes[n % palettes.length]
}

function onOpen() {
  emit('open')
}

/** 千卡为 0 表示"暂无估算"（数据集缺 heat），显示"—"而不是 0 */
function kcalText(): string {
  return props.plan.kcalEstimate > 0 ? `${props.plan.kcalEstimate} 千卡` : '热量待估'
}
</script>

<template>
  <div class="plan-card" data-testid="plan-card" @click="onOpen">
    <div class="cover" :style="{ background: coverGradient(plan.title) }">
      <el-image v-if="plan.coverUrl" :src="plan.coverUrl" fit="cover" class="cover-img">
        <template #error>
          <div class="cover-ph">{{ plan.title.slice(0, 2) }}</div>
        </template>
      </el-image>
      <div v-else class="cover-ph">{{ plan.title.slice(0, 2) }}</div>
      <span class="source-tag">{{ plan.source === 0 ? '官方' : plan.source === 2 ? 'AI' : '自建' }}</span>
    </div>

    <div class="body">
      <div class="title">{{ plan.title }}</div>
      <div class="subtitle">{{ plan.subtitle }}</div>
      <div class="meta">
        <el-tag size="small" effect="plain">{{ plan.goal }}</el-tag>
        <span class="kcal">{{ kcalText() }}</span>
      </div>
      <div v-if="plan.equipment.length" class="equipment">
        <el-tag
          v-for="item in plan.equipment"
          :key="item"
          size="small"
          type="info"
          effect="plain"
        >
          {{ item }}
        </el-tag>
      </div>
    </div>
  </div>
</template>

<style scoped>
.plan-card {
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}

.plan-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.08);
}

.cover {
  position: relative;
  height: 112px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cover-img {
  width: 100%;
  height: 100%;
  display: block;
}

.cover-ph {
  font-size: 30px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.92);
  letter-spacing: 2px;
}

.source-tag {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.32);
}

.body {
  padding: 10px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subtitle {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kcal {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.equipment {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
