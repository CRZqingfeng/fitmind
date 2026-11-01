<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { useRoute, useRouter } from 'vue-router'

import { getDietPlanDetail } from '@/api/plan'
import type { DietPlanDetail } from '@/types/plan'

/**
 * 饮食计划详情（P1）
 *
 * ★★ 三层热量里"谁是真相"（口径 6）：食物项那一层。这里显示的数字是后端
 *   **按 food 现价 × amount 实时算出来的**，不是 `diet_plan_meal.kcal` 那个冗余列。
 *   所以运营改了食物库热量之后，详情页立刻正确 —— 这也是为什么响应里没有暴露
 *   冗余列（避免前端拿到两个不一致的数，还得自己判断信谁）。
 *
 * ★ 第 N 天用横向 tab 切换（数据一次全返回，切 tab 不再发请求）。
 */
const route = useRoute()
const router = useRouter()

const planId = computed(() => String(route.params.id ?? ''))
const loading = ref(true)
const errorText = ref('')
const detail = ref<DietPlanDetail | null>(null)
const activeDay = ref(1)

const currentDay = computed(() =>
  (detail.value?.days ?? []).find((d) => d.dayIndex === activeDay.value) ?? detail.value?.days[0] ?? null,
)

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    detail.value = await getDietPlanDetail(planId.value)
    activeDay.value = detail.value.days[0]?.dayIndex ?? 1
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '饮食计划加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function back() {
  router.push({ path: '/plan', query: { tab: 'diet' } })
}

function fmt(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}
</script>

<template>
  <div class="diet-plan-detail">
    <el-button text @click="back">‹ 返回饮食首页</el-button>

    <el-skeleton v-if="loading" :rows="6" animated />

    <el-alert v-else-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
      <template #default>
        <el-button size="small" @click="load">重试</el-button>
      </template>
    </el-alert>

    <template v-else-if="detail">
      <div class="cover">
        <div class="cover-ph">🥗</div>
        <div class="cover-mask">
          <div class="title">{{ detail.title }}</div>
          <div class="summary">{{ detail.summary || '暂无描述' }}</div>
        </div>
      </div>

      <div class="metrics">
        <div class="metric">
          <div class="m-num">{{ detail.cycleText }}</div>
          <div class="m-label">周期</div>
        </div>
        <div class="metric">
          <div class="m-num">{{ detail.kcalPerDay }}<span class="unit">千卡</span></div>
          <div class="m-label">每日热量</div>
        </div>
        <div class="metric">
          <div class="m-num">{{ detail.goal }}</div>
          <div class="m-label">目标</div>
        </div>
      </div>

      <div class="content-head">
        <span class="content-title">计划内容</span>
        <span class="content-sub">
          {{ detail.days.length }} 天 · 每天 {{ currentDay?.meals.length ?? 0 }} 餐
        </span>
      </div>

      <!-- 第 1 天 ~ 第 N 天 -->
      <el-radio-group v-model="activeDay" size="small" class="day-tabs">
        <el-radio-button v-for="d in detail.days" :key="d.dayIndex" :value="d.dayIndex">
          第{{ d.dayIndex }}天
        </el-radio-button>
      </el-radio-group>

      <template v-if="currentDay">
        <div class="day-summary">
          当天合计 {{ currentDay.kcalTotal }} 千卡
          <span class="macro">蛋白 {{ fmt(currentDay.proteinG) }}g · 碳水 {{ fmt(currentDay.carbG) }}g · 脂肪 {{ fmt(currentDay.fatG) }}g</span>
        </div>

        <div v-for="meal in currentDay.meals" :key="`${meal.mealType}-${meal.mealIndex}`" class="meal">
          <div class="meal-head">
            <span class="meal-label">{{ meal.label }}</span>
            <span v-if="meal.mealTime" class="meal-time">{{ meal.mealTime }}</span>
            <span class="meal-kcal">{{ meal.kcal }} 千卡</span>
          </div>
          <!-- 横滑食物卡 -->
          <div class="food-cards">
            <div v-for="item in meal.items" :key="`${meal.mealType}-${item.foodId}-${item.amountText}`" class="food-card">
              <div class="food-emoji">{{ item.emoji || '🍽' }}</div>
              <div class="food-name">{{ item.foodName }}</div>
              <div class="food-amount">{{ item.amountText }}</div>
              <div class="food-kcal">{{ item.kcal }} 千卡</div>
            </div>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.diet-plan-detail {
  max-width: 880px;
  margin: 0 auto;
}

.cover {
  position: relative;
  height: 180px;
  border-radius: 14px;
  overflow: hidden;
  background: linear-gradient(135deg, #22b07d 0%, #6ed3a8 100%);
  margin-top: 8px;
}

.cover-ph {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 52px;
}

.cover-mask {
  position: absolute;
  inset: auto 0 0 0;
  padding: 14px 18px;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.5), transparent);
  color: #fff;
}

.title {
  font-size: 19px;
  font-weight: 700;
}

.summary {
  font-size: 12px;
  opacity: 0.92;
  margin-top: 4px;
  line-height: 1.5;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin: 14px 0;
}

.metric {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 12px;
  text-align: center;
}

.m-num {
  font-size: 16px;
  font-weight: 700;
}

.m-num .unit {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 2px;
}

.m-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.content-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 6px 0 10px;
}

.content-title {
  font-size: 16px;
  font-weight: 600;
}

.content-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.day-tabs {
  margin-bottom: 10px;
}

.day-summary {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 12px;
}

.macro {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 8px;
}

.meal {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
}

.meal-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}

.meal-label {
  font-size: 15px;
  font-weight: 600;
}

.meal-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.meal-kcal {
  margin-left: auto;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.food-cards {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.food-card {
  width: 104px;
  flex-shrink: 0;
  background: var(--el-fill-color-lighter);
  border-radius: 10px;
  padding: 10px 8px;
  text-align: center;
}

.food-emoji {
  font-size: 24px;
}

.food-name {
  font-size: 12px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.food-amount {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.food-kcal {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}
</style>
