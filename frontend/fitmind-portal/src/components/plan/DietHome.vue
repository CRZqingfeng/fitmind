<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import {
  getDietRecommendPlans,
  getDietToday,
  getLatestBodyMetric,
  recordBodyMetric,
  removeDietLog,
} from '@/api/plan'
import MacroBar from '@/components/plan/MacroBar.vue'
import MealCard from '@/components/plan/MealCard.vue'
import type { BodyMetric, DietLogItem, DietMeal, DietPlanCard, DietToday } from '@/types/plan'
import { toJin, toKg } from '@/utils/format'

/**
 * 饮食 tab（M11-3 / P1）
 *
 * 抽成子组件的理由：PlanHomeView 已经有训练 + 我的计划两块，
 * 再把饮食粘进去会变成一个 800 行的文件。而"外壳"（日期、两个 tab）只有一份。
 *
 * ★★ 本组件自己拉自己的数据，**不并进父组件的 Promise.all**：
 *   训练接口挂了不该让饮食 tab 一起白屏，反之亦然。
 *   两个域刷新频率不同（记一条饮食只该刷新饮食），这也是后端拆两个 today 接口的原因。
 *
 * ★★ 三个数字（摄入 / 预算 / 还可以吃）全部来自 `getDietToday()`：
 *   前端一旦"用预算减摄入"自己算一遍，改天后端把四舍五入改一位，页面就对不上了。
 */
const props = defineProps<{
  /** 选中日期（来自路由 query），不传 = 今天 */
  date?: string
}>()

const emit = defineEmits<{
  (e: 'need-profile'): void
}>()

const router = useRouter()

const loading = ref(true)
const errorText = ref('')
const today = ref<DietToday | null>(null)
const recommends = ref<DietPlanCard[]>([])
const metric = ref<BodyMetric | null>(null)

/** 记录体重弹层 */
const weightDialog = ref(false)
const weightSaving = ref(false)
const weightForm = reactive({ weightJin: 0, heightCm: 0, targetWeightJin: 0 })

const dateText = computed(() => {
  const d = today.value?.date
  if (!d) return ''
  const [, m, day] = d.split('-')
  return `${Number(m)}月${Number(day)}日`
})

/** 还可以吃：null 表示没有预算（还没记录体重） */
const remain = computed(() => today.value?.remainKcal ?? null)
const overspent = computed(() => remain.value !== null && remain.value < 0)
const budget = computed(() => today.value?.budgetKcal ?? 0)

/**
 * 环的百分比 = **还可以吃的比例**（不是已吃比例）。
 *
 * ★★ 这一条是踩出来的：第一版画的是 `摄入 / 预算`，于是刚记了 338 千卡，
 *   环只画了 18%，旁边却写着"还可以吃 1482"——
 *   环和文字说的是两件事，用户看到的是"我快吃满了"+ "我还能吃很多"。
 *   参考 App 的语义是：一口没吃时**满环**（还可以吃 = 预算全额），越吃越少。
 *
 * 吃超了（remain < 0）时返回 0（空环）+ 红字"已超出 X"，语义仍然一致。
 */
const ringPercent = computed(() => {
  if (!budget.value || remain.value === null) return 0
  return Math.max(0, Math.min(100, Math.round((remain.value / budget.value) * 100)))
})

/** 当前体重：库里是 kg，界面显示斤（换算只在 format.ts 一处） */
const weightJin = computed(() => {
  const kg = today.value?.weightKg
  return kg === null || kg === undefined ? null : toJin(kg)
})

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    const [t, r] = await Promise.all([getDietToday(props.date), getDietRecommendPlans()])
    today.value = t
    recommends.value = r ?? []
    // 体重弹层的默认值：用后端给的最新体征（含档案兜底）
    if (t.needProfile) {
      emit('need-profile')
    }
  } catch (e) {
    // ★ 不吞成空数据：接口挂了要说"挂了"，不能显示成"今天还没记录"
    errorText.value = e instanceof Error ? e.message : '饮食数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 日期变了（含从饮食库带回来的 ?date=）→ 重新拉
watch(() => props.date, load)

function goMeal(meal: DietMeal) {
  router.push({
    path: '/plan/food-lib',
    query: { mealType: String(meal.mealType), date: props.date || today.value?.date || '' },
  })
}

function goSport() {
  router.push({ path: '/plan/sport-lib', query: { date: props.date || today.value?.date || '' } })
}

async function handleRemove(item: DietLogItem) {
  try {
    await ElMessageBox.confirm(`删除「${item.foodName} ${item.amount}${item.unit}」？`, '删除记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await removeDietLog(item.id)
    // ★ 删完重新拉，不在本地减数字：三个数（摄入/还可以吃/进度条）必须同时正确
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

/** 打开记录体重弹层：默认带出当前体重与身高（斤与 cm） */
async function openWeightDialog() {
  try {
    metric.value = await getLatestBodyMetric()
  } catch {
    metric.value = null
  }
  weightForm.weightJin = toJin(metric.value?.weightKg ?? null) ?? 0
  weightForm.heightCm = metric.value?.heightCm ?? 0
  weightForm.targetWeightJin = toJin(metric.value?.targetWeightKg ?? null) ?? 0
  weightDialog.value = true
}

async function saveWeight() {
  if (!weightForm.weightJin || weightForm.weightJin <= 0) {
    ElMessage.warning('体重填一下')
    return
  }
  const weightKg = toKg(weightForm.weightJin)
  if (weightKg === null) {
    ElMessage.warning('体重不合法')
    return
  }
  weightSaving.value = true
  try {
    await recordBodyMetric({
      // 第一次必须带身高（后端会校验并给"第一次记录体重需要填身高"）
      heightCm: weightForm.heightCm > 0 ? weightForm.heightCm : undefined,
      weightKg,
      targetWeightKg: weightForm.targetWeightJin > 0 ? (toKg(weightForm.targetWeightJin) ?? undefined) : undefined,
    })
    weightDialog.value = false
    ElMessage.success('已记录，预算与目标已更新')
    // ★ 预算和 macro 目标可能一起变，必须重新拉；不许只改本地那个数字
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '记录失败')
  } finally {
    weightSaving.value = false
  }
}

function openDietPlan(id: string) {
  router.push(`/plan/diet-plan/${id}`)
}
</script>

<template>
  <div class="diet-home">
    <el-skeleton v-if="loading" :rows="5" animated />

    <el-alert v-else-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
      <template #default>
        <el-button size="small" @click="load">重试</el-button>
      </template>
    </el-alert>

    <template v-else-if="today">
      <!-- 今日卡 -->
      <div class="today">
        <div class="circle-wrap">
          <el-progress
            type="dashboard"
            :percentage="ringPercent"
            :width="150"
            :stroke-width="10"
            :color="overspent ? '#f56c6c' : '#4f7cff'"
          >
            <template #default>
              <div class="ring-text">
                <template v-if="remain !== null">
                  <div class="ring-label">{{ overspent ? '已超出' : '还可以吃' }}</div>
                  <div class="ring-num">{{ Math.abs(remain) }}</div>
                  <div class="ring-sub">预算 {{ budget }} 千卡</div>
                </template>
                <template v-else>
                  <div class="ring-label">还没有预算</div>
                  <div class="ring-sub">先记录一次体重</div>
                </template>
              </div>
            </template>
          </el-progress>
        </div>

        <div class="today-info">
          <div class="date-row">
            <span class="date">{{ dateText }}</span>
            <el-button size="small" plain @click="openWeightDialog">记录体重</el-button>
          </div>
          <div class="intake">
            今日摄入 <b>{{ today.intakeKcal }}</b> 千卡
          </div>
          <div class="weight">
            当前体重
            <b>{{ weightJin === null ? '—' : weightJin }}</b> 斤
            <span v-if="today.bmi" class="bmi">BMI {{ today.bmi }}</span>
          </div>
          <!-- 运动消耗：不抵扣预算，但必须显示，否则用户以为记运动没用 -->
          <div class="sport-note">{{ today.sportNote }}</div>
        </div>
      </div>

      <!-- 三大营养素 -->
      <div class="macros">
        <MacroBar
          v-for="m in today.macros"
          :key="m.key"
          :label="m.label"
          :current="Number(m.current)"
          :target="Number(m.target)"
          :unit="m.unit"
        />
      </div>

      <!-- 快捷入口 5 个 -->
      <div class="quick">
        <el-button
          v-for="m in today.meals"
          :key="m.mealType"
          class="quick-btn"
          @click="goMeal(m)"
        >
          记{{ m.label }}
        </el-button>
        <el-button class="quick-btn" @click="goSport">记运动</el-button>
      </div>

      <!-- 四餐 -->
      <MealCard
        v-for="m in today.meals"
        :key="m.mealType"
        :meal="m"
        @add="goMeal"
        @remove="handleRemove"
      />

      <!-- 推荐饮食计划 -->
      <div class="section">
        <div class="section-title">推荐饮食计划</div>
        <el-empty v-if="recommends.length === 0" description="还没有推荐饮食计划" :image-size="70" />
        <div v-else class="plan-grid">
          <div
            v-for="p in recommends"
            :key="p.id"
            class="plan-card"
            @click="openDietPlan(p.id)"
          >
            <div class="plan-cover">🥗</div>
            <div class="plan-body">
              <div class="plan-title">{{ p.title }}</div>
              <div class="plan-sub">{{ p.subtitle }}</div>
              <div class="plan-more">查看详情 ›</div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 记录体重 -->
    <el-dialog v-model="weightDialog" title="记录体重" width="380px">
      <el-form label-width="72px">
        <el-form-item label="体重">
          <el-input-number v-model="weightForm.weightJin" :min="0" :max="600" :step="0.5" :precision="1" />
          <span class="unit">斤</span>
        </el-form-item>
        <el-form-item label="身高">
          <el-input-number v-model="weightForm.heightCm" :min="0" :max="250" :step="1" />
          <span class="unit">cm</span>
        </el-form-item>
        <el-form-item label="目标体重">
          <el-input-number v-model="weightForm.targetWeightJin" :min="0" :max="600" :step="0.5" :precision="1" />
          <span class="unit">斤</span>
        </el-form-item>
      </el-form>
      <div class="dialog-tip">
        体重填「斤」，提交时会换算成公斤（库里只存 kg）。第一次需要填身高，之后可以留空。
      </div>
      <template #footer>
        <el-button @click="weightDialog = false">取消</el-button>
        <el-button type="primary" :loading="weightSaving" @click="saveWeight">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.diet-home {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.today {
  display: flex;
  align-items: center;
  gap: 18px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  padding: 14px 18px;
}

.circle-wrap {
  flex-shrink: 0;
}

.ring-text {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.ring-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ring-num {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
}

.ring-sub {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.today-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.date-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.date {
  font-size: 16px;
  font-weight: 600;
}

.intake {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.intake b {
  font-size: 18px;
  color: var(--el-color-primary);
}

.weight {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.bmi {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}

.sport-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.macros {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 14px;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.quick-btn {
  flex: 1;
  min-width: 96px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 10px;
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.plan-card {
  display: flex;
  gap: 12px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 12px;
  cursor: pointer;
}

.plan-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.plan-cover {
  width: 56px;
  height: 56px;
  border-radius: 10px;
  background: linear-gradient(135deg, #22b07d 0%, #6ed3a8 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
}

.plan-title {
  font-size: 14px;
  font-weight: 600;
}

.plan-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.plan-more {
  font-size: 12px;
  color: var(--el-color-primary);
  margin-top: 4px;
}

.unit {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dialog-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
