<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { useRoute, useRouter } from 'vue-router'

import { getExercise } from '@/api/plan'
import type { ExerciseDetail } from '@/types/plan'

/**
 * 动作详情（M11-2 / P0）
 *
 * ★★ 三个字段后端会返回 `null`，因为它们**在开源数据集里根本不存在**：
 *   `heat`（热量）、`lumbarPressure`（腰椎压力）、`gifUrl`（动图）。
 *   界面必须显示"—"，**绝不能兜成 0**：
 *   "腰椎压力 0"会被用户读成"这个动作不伤腰"——这是安全问题，不是显示问题。
 *
 * ★ 肌群是数据集里的**原始英文值**（chest / lats …），没有官方中文译名。
 *   这里按原样显示并在标题上标注，不自己翻译 —— 翻译过一遍就再也没法跟数据集对账。
 */
const route = useRoute()
const router = useRouter()

const id = computed(() => String(route.params.id ?? ''))
const loading = ref(true)
const errorText = ref('')
const detail = ref<ExerciseDetail | null>(null)

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    detail.value = await getExercise(id.value)
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '动作加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function dash(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}
</script>

<template>
  <div class="exercise-detail">
    <el-button text @click="router.back()">‹ 返回</el-button>

    <el-skeleton v-if="loading" :rows="6" animated />

    <el-alert v-else-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
      <template #default>
        <el-button size="small" @click="load">重试</el-button>
      </template>
    </el-alert>

    <template v-else-if="detail">
      <div class="hero">
        <el-image :src="detail.imageUrl || ''" fit="cover" class="hero-img">
          <template #error>
            <div class="hero-ph">{{ detail.name.slice(0, 1) }}</div>
          </template>
        </el-image>
        <div class="hero-info">
          <div class="name">{{ detail.name }}</div>
          <div class="en-name">{{ detail.enName || '' }}</div>
          <div class="tags">
            <el-tag size="small">{{ detail.category }}</el-tag>
            <el-tag size="small" type="success" effect="plain">{{ detail.bodyPart }}</el-tag>
            <el-tag size="small" type="info" effect="plain">{{ detail.equipment }}</el-tag>
            <el-tag size="small" type="warning" effect="plain">难度 {{ detail.difficulty }}/5</el-tag>
          </div>
        </div>
      </div>

      <div class="metrics">
        <div class="metric">
          <div class="m-num">{{ dash(detail.heat) }}</div>
          <div class="m-label">热量参考</div>
        </div>
        <div class="metric">
          <div class="m-num">{{ dash(detail.lumbarPressure) }}</div>
          <div class="m-label">腰椎压力</div>
        </div>
        <div class="metric">
          <div class="m-num">—</div>
          <div class="m-label">动作动图</div>
        </div>
      </div>

      <div class="notice">
        热量 / 腰椎压力 / 动图在开源动作数据集里没有对应字段，这里如实显示"—"，
        不做估算，也不填 0。
      </div>

      <div class="section">
        <div class="section-title">主要肌群（数据集原始值）</div>
        <div class="muscles">
          <el-tag v-for="m in detail.primaryMuscles" :key="m" size="small" effect="plain">
            {{ m }}
          </el-tag>
          <span v-if="detail.primaryMuscles.length === 0" class="empty-text">数据集未标注</span>
        </div>
      </div>

      <div class="section">
        <div class="section-title">协同肌群</div>
        <div class="muscles">
          <el-tag
            v-for="m in detail.secondaryMuscles"
            :key="m"
            size="small"
            type="info"
            effect="plain"
          >
            {{ m }}
          </el-tag>
          <span v-if="detail.secondaryMuscles.length === 0" class="empty-text">无</span>
        </div>
      </div>

      <div class="section">
        <div class="section-title">动作步骤</div>
        <ol class="steps">
          <li v-for="(s, i) in detail.steps" :key="i">{{ s }}</li>
        </ol>
        <div v-if="detail.steps.length === 0" class="empty-text">数据集未提供步骤</div>
      </div>

      <div v-if="detail.tips" class="section">
        <div class="section-title">提示</div>
        <div class="tips">{{ detail.tips }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.exercise-detail {
  max-width: 800px;
  margin: 0 auto;
}

.hero {
  display: flex;
  gap: 16px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  padding: 14px;
  margin: 8px 0 12px;
}

.hero-img {
  width: 180px;
  height: 140px;
  border-radius: 10px;
  flex-shrink: 0;
  background: var(--el-fill-color-light);
}

.hero-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  color: var(--el-text-color-secondary);
}

.hero-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  justify-content: center;
}

.name {
  font-size: 20px;
  font-weight: 700;
}

.en-name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.metric {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 12px;
  text-align: center;
}

.m-num {
  font-size: 18px;
  font-weight: 700;
}

.m-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.notice {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin: 10px 2px 0;
}

.section {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 14px;
  margin-top: 12px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

.muscles {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.steps {
  margin: 0;
  padding-left: 20px;
  font-size: 14px;
  line-height: 1.9;
  color: var(--el-text-color-regular);
}

.tips {
  font-size: 14px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

.empty-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
