<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'

import { planSkeleton } from '@/api/ai'
import { aiApply, aiPreview, aiTarget } from '@/api/plan'
import type { AiPreview, AiTarget, PlanSkeleton } from '@/types/plan'
import { toJin } from '@/utils/format'

/**
 * AI 定制结果页（M11-4 / P2）。
 *
 * ★★ 这个页面最容易被写错的地方：**自己算数字**。
 *   达成日期、BMI、热量占比、预计分钟、每餐热量 —— 全部由服务端算好。
 *   前端只要"照着画"，否则页面上的数和库里的数迟早对不上（P1 的 C35 就是两个值取自不同时刻）。
 *
 * ★ 三个状态必须分开：加载中（骨架屏 + 分步进度）/ 缺体重（引导去记录）/ 出错（能重试）。
 *   尤其**加载中不能落进"缺体重"**：那会先闪一句"先记录体重"，再跳成完整计划。
 */
const router = useRouter()

const loading = ref(true)
/** 1 = 正在生成建议（AI，可能 5~20 秒）；2 = 正在排课配餐 */
const step = ref(1)
const errorText = ref('')
const needProfile = ref(false)
const preview = ref<AiPreview | null>(null)
const target = ref<AiTarget | null>(null)
const skeleton = ref<PlanSkeleton | null>(null)
const accepting = ref(false)
/** 饮食预览当前看第几天（1 起） */
const dietDay = ref(1)

const target0 = computed(() => preview.value?.target ?? target.value ?? null)

const currentJin = computed(() => jin(target0.value?.currentWeightKg))
const targetJin = computed(() => jin(target0.value?.targetWeightKg))

const goalText = computed(() => {
  const t = target0.value
  if (!t) return ''
  if (t.currentWeightKg === null) return ''
  if (t.targetWeightKg === null) return '还没有填目标体重'
  if (t.daysToGoal === 0) return '已经达成目标体重'
  if (t.daysToGoal === null) return ''
  return `预计 ${t.targetDate} 达成（还有 ${t.daysToGoal} 天）`
})

/** 7 天折线：高度按 min~max 归一化（只影响"看起来的走势"，数值仍然是后端的） */
const forecastBars = computed(() => {
  const pts = target0.value?.forecast ?? []
  if (pts.length === 0) return []
  const vals = pts.map((p) => Number(p.weightKg))
  const min = Math.min(...vals) - 0.2
  const max = Math.max(...vals) + 0.2
  const span = max - min || 1
  return pts.map((p) => ({
    label: String(p.date).slice(5),
    value: p.weightKg,
    height: Math.round(((Number(p.weightKg) - min) / span) * 56) + 10,
  }))
})

const currentDay = computed(() => {
  const days = preview.value?.dietDays ?? []
  return days.find((d) => d.dayIndex === dietDay.value) ?? days[0] ?? null
})

const intensityLabel = computed(() => {
  const map: Record<string, string> = { LOW: '低强度', MEDIUM: '中强度', HIGH: '中高强度' }
  return map[skeleton.value?.intensity ?? ''] ?? '中强度'
})

const distributionRows = computed(() => {
  const d = preview.value?.distribution
  if (!d) return []
  return [
    { label: '力量', value: d.strength },
    { label: '有氧', value: d.cardio },
    { label: '柔韧', value: d.flexibility },
  ]
})

function jin(kg: number | null | undefined) {
  const v = toJin(kg)
  return v === null ? '—' : String(v)
}

function pct(v: number) {
  return `${Math.round(v * 100)}%`
}

async function load() {
  loading.value = true
  errorText.value = ''
  needProfile.value = false
  preview.value = null
  step.value = 1
  dietDay.value = 1

  // ① 目标推算：失败不阻断（结果页的 ① 会显示"—"，计划照样能出）
  try {
    target.value = await aiTarget()
  } catch {
    target.value = null
  }

  // ② 骨架（AI）。★ 这一步失败**不阻断**：直接调 preview 会走规则版
  let sk: PlanSkeleton | null = null
  try {
    sk = await planSkeleton({
      weightKg: target.value?.currentWeightKg ?? undefined,
      targetWeightKg: target.value?.targetWeightKg ?? undefined,
    })
    skeleton.value = sk
  } catch {
    skeleton.value = null
  }

  // 连体重都没有：不编计划，引导去记录
  if (sk?.needProfile && !target.value?.currentWeightKg) {
    needProfile.value = true
    loading.value = false
    return
  }

  step.value = 2
  const body =
    sk && !sk.needProfile
      ? {
          skeletonId: sk.skeletonId,
          division: sk.division,
          weeklyFrequency: sk.weeklyFrequency,
          intensity: sk.intensity,
          mealSplit: sk.mealSplit,
          dietAdvice: sk.dietAdvice,
          trainingAdvice: sk.trainingAdvice,
          targetTip: sk.targetTip,
          degraded: sk.degraded,
        }
      : {}

  try {
    preview.value = await aiPreview(body)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '生成失败'
    // "先记录一次体重"是业务状态，不是错误（HTTP 200 + code 400）
    if (msg.includes('体重')) {
      needProfile.value = true
    } else {
      errorText.value = msg
    }
  } finally {
    loading.value = false
  }
}

async function accept() {
  if (!preview.value) return
  accepting.value = true
  try {
    await aiApply(preview.value.previewId)
    ElMessage.success('已加入我的计划，今天就能开练')
    router.push('/plan')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '接受失败'
    // 预览过期（草稿被新的一次生成覆盖了）→ 给一条能走通的路，而不是干瞪着
    errorText.value = msg.includes('过期') ? `${msg}（点「重新生成」再来一次）` : msg
    if (msg.includes('过期')) {
      preview.value = null
    }
  } finally {
    accepting.value = false
  }
}

function goRecordWeight() {
  router.push({ path: '/plan', query: { tab: 'diet' } })
}

onMounted(load)
</script>

<template>
  <div class="ai-plan">
    <!-- 结果页是一次性页面，刷新会重新生成；给一个明确的回头路 -->
    <div class="top-bar">
      <el-button text @click="router.push('/plan')">‹ 返回计划</el-button>
      <span class="top-title">AI 定制计划</span>
    </div>

    <!-- ============ 加载中：分步进度（要能解释"为什么慢"） ============ -->
    <div v-if="loading" class="panel loading-panel">
      <el-steps :active="step" simple>
        <el-step title="生成建议" />
        <el-step title="排课与配餐" />
      </el-steps>
      <div class="loading-tip">
        {{ step === 1 ? '正在读你的身体数据、挑一套适合的练法…（这一步要问一次 AI，可能 5~20 秒）' : '正在从动作库/食物库里挑动作、配餐…' }}
      </div>
      <el-skeleton :rows="6" animated />
    </div>

    <!-- ============ 出错 ============ -->
    <el-alert v-else-if="errorText" type="error" :title="errorText" :closable="false" show-icon class="panel">
      <el-button size="small" @click="load">重新生成</el-button>
    </el-alert>

    <!-- ============ 缺体重：引导，不显示 0 ============ -->
    <div v-else-if="needProfile" class="panel empty-panel">
      <div class="empty-emoji">⚖️</div>
      <div class="empty-title">还差一次体重</div>
      <div class="empty-tip">热量预算和达成时间都要用体重来算。先记录一次体重，再回来定制。</div>
      <el-button type="primary" @click="goRecordWeight">去记录体重</el-button>
    </div>

    <!-- ============ 结果页 ============ -->
    <template v-else-if="preview">
      <!-- ① 目标卡 -->
      <section class="panel block-target">
        <div class="block-title">你的个人计划已准备就绪</div>
        <div class="target-main">
          <span class="from">{{ currentJin }} 斤</span>
          <span class="arrow">→</span>
          <span class="to">{{ targetJin }} 斤</span>
        </div>
        <div class="target-sub">
          {{ goalText }}
          <span v-if="preview.target.weeklyDeltaKg" class="weekly">
            · 每周约 {{ preview.target.weeklyDeltaKg }} kg
          </span>
        </div>
        <div class="forecast">
          <div v-for="p in forecastBars" :key="p.label" class="forecast-col">
            <span class="fc-value">{{ p.value }}</span>
            <div class="fc-bar" :style="{ height: p.height + 'px' }"></div>
            <span class="fc-label">{{ p.label }}</span>
          </div>
        </div>
        <div class="forecast-tip">未来 7 天预计体重（kg）</div>
      </section>

      <!-- ② 评测卡 -->
      <section class="panel">
        <div class="block-title">你的评测结果</div>
        <div class="eval-grid">
          <div class="eval-item"><span class="k">身高</span><span class="v">{{ preview.evaluation.heightCm ?? '—' }} cm</span></div>
          <div class="eval-item"><span class="k">体重</span><span class="v">{{ preview.evaluation.weightKg ?? '—' }} kg</span></div>
          <div class="eval-item"><span class="k">目标</span><span class="v">{{ preview.evaluation.targetWeightKg ?? '—' }} kg</span></div>
          <div class="eval-item">
            <span class="k">BMI</span>
            <span class="v">{{ preview.evaluation.bmi ?? '—' }} <em>{{ preview.evaluation.bmiLabel }}</em></span>
          </div>
        </div>
        <!-- ★ 四段**按真实 BMI 区间宽度**排（15~18.5 / 18.5~24 / 24~28 / 28~35），
             不是四等分：等分的话 20.1 的游标会正好压在"偏瘦/正常"交界上，
             看起来像"我是不是偏瘦"——而它明明是正常。 -->
        <div class="bmi-bar">
          <div class="bmi-seg seg-thin" style="flex: 3.5">偏瘦</div>
          <div class="bmi-seg seg-normal" style="flex: 5.5">正常</div>
          <div class="bmi-seg seg-fat" style="flex: 4">偏胖</div>
          <div class="bmi-seg seg-obese" style="flex: 7">肥胖</div>
          <div
            v-if="preview.evaluation.bmiPercent !== null"
            class="bmi-marker"
            :style="{ left: pct(preview.evaluation.bmiPercent) }"
          ></div>
        </div>
      </section>

      <!-- ③ 饮食建议 + 饮食计划预览 -->
      <section class="panel">
        <div class="block-title">饮食建议</div>
        <div class="macros">
          <div v-for="m in preview.macros.items" :key="m.key" class="macro-item">
            <div class="m-label">{{ m.label }}</div>
            <div class="m-gram">{{ m.gram }}g</div>
            <div class="m-sub">{{ m.kcal }} 千卡 · {{ m.percent }}%</div>
          </div>
        </div>
        <div class="budget">每天 {{ preview.macros.kcalBudget }} 千卡</div>
        <p class="advice-text">{{ preview.advice.dietAdvice }}</p>

        <div class="diet-title">为你准备了「{{ preview.dietPlanTitle }}」</div>
        <el-radio-group v-model="dietDay" size="small" class="day-tabs">
          <el-radio-button v-for="d in preview.dietDays" :key="d.dayIndex" :value="d.dayIndex">
            第{{ d.dayIndex }}天
          </el-radio-button>
        </el-radio-group>

        <div v-if="currentDay" class="meals">
          <div class="meal-day-total">
            当天合计 {{ currentDay.kcalTotal }} 千卡 ·
            蛋白 {{ currentDay.proteinG }}g / 碳水 {{ currentDay.carbG }}g / 脂肪 {{ currentDay.fatG }}g
          </div>
          <div v-for="m in currentDay.meals" :key="`${m.mealType}-${m.mealIndex}`" class="meal-row">
            <div class="meal-head">
              <span class="meal-label">{{ m.label }}</span>
              <span class="meal-time">{{ m.mealTime }}</span>
              <span class="meal-kcal">{{ m.kcal }} 千卡</span>
            </div>
            <div class="meal-items">
              <span v-for="it in m.items" :key="`${it.foodId}-${it.amountText}`" class="food-chip">
                {{ it.emoji }} {{ it.foodName }} {{ it.amountText }}
              </span>
            </div>
          </div>
        </div>
        <div class="hint-small">详细内容接受后在「饮食计划详情」里看</div>
      </section>

      <!-- ④ 运动建议 + 训练计划预览 -->
      <section class="panel">
        <div class="block-title">运动建议</div>
        <div class="intensity">
          <span class="i-label">强度</span>
          <span
            v-for="lv in ['LOW', 'MEDIUM', 'HIGH']"
            :key="lv"
            class="i-pill"
            :class="{ active: (skeleton?.intensity ?? 'MEDIUM') === lv }"
          >{{ lv === 'LOW' ? '低' : lv === 'MEDIUM' ? '中' : '中高' }}</span>
          <span class="i-value">{{ intensityLabel }}</span>
        </div>

        <div class="dist">
          <div v-for="r in distributionRows" :key="r.label" class="dist-row">
            <span class="d-label">{{ r.label }}</span>
            <div class="d-track"><div class="d-fill" :style="{ width: pct(r.value) }"></div></div>
            <span class="d-value">{{ pct(r.value) }}</span>
          </div>
        </div>
        <div class="dist-tip">按这次排进来的动作类别统计，不含热身</div>

        <div class="plan-meta">
          <span>一周 {{ preview.trainDays.length }} 练</span>
          <span>难度 {{ preview.difficultyLabel }}</span>
          <span>共 {{ preview.trainDays.length }} 个训练日</span>
        </div>

        <div class="train-days">
          <div v-for="d in preview.trainDays" :key="d.id" class="train-day">
            <span class="td-name">{{ d.name }}</span>
            <span class="td-meta">{{ d.itemCount }} 个动作 · 预计 {{ d.estMinutes }} 分钟</span>
          </div>
        </div>
        <p class="advice-text">{{ preview.advice.trainingAdvice }}</p>

        <el-alert
          v-if="preview.advice.degraded"
          type="warning"
          :closable="false"
          show-icon
          title="AI 建议这次没生成出来，先按通用方案给你排了一份，稍后可以点「重新生成」"
          class="degraded-alert"
        />
        <div v-if="preview.advice.notes?.length" class="notes">
          <div v-for="(n, i) in preview.advice.notes" :key="i" class="note-line">· {{ n }}</div>
        </div>
      </section>

      <!-- 底部固定条 -->
      <div class="bottom-bar">
        <el-button :loading="loading" @click="load">重新生成</el-button>
        <el-button type="primary" :loading="accepting" @click="accept">接受并加入我的计划</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ai-plan {
  max-width: 880px;
  margin: 0 auto;
  padding-bottom: 84px;
}

.panel {
  background: #fff;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.block-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
}

.loading-panel .loading-tip {
  margin: 12px 0;
  color: #909399;
  font-size: 13px;
}

.empty-panel {
  text-align: center;
  padding: 48px 18px;
}

.empty-emoji {
  font-size: 40px;
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  margin: 8px 0;
}

.empty-tip {
  color: #909399;
  margin-bottom: 16px;
}

.top-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 2px 10px;
}

.top-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

/* ① 目标卡 */
.target-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.target-main .from,
.target-main .to {
  font-size: 26px;
  font-weight: 700;
}

.target-main .to {
  color: #4f7cff;
}

.target-main .arrow {
  color: #c0c4cc;
}

.target-sub {
  color: #606266;
  font-size: 13px;
  margin-top: 4px;
}

.forecast {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin-top: 14px;
  height: 96px;
}

.forecast-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
}

.fc-value {
  font-size: 11px;
  color: #909399;
}

.fc-bar {
  width: 100%;
  max-width: 34px;
  background: linear-gradient(180deg, #8fb0ff, #4f7cff);
  border-radius: 4px 4px 0 0;
}

.fc-label {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 4px;
}

.forecast-tip {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 4px;
}

/* ② 评测卡 */
.eval-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.eval-item {
  background: #f7f8fa;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.eval-item .k {
  font-size: 12px;
  color: #909399;
}

.eval-item .v {
  font-size: 16px;
  font-weight: 600;
}

.eval-item .v em {
  font-size: 12px;
  font-style: normal;
  color: #67c23a;
  margin-left: 4px;
}

.bmi-bar {
  position: relative;
  display: flex;
  height: 22px;
  border-radius: 11px;
  overflow: hidden;
  margin-top: 12px;
}

.bmi-seg {
  font-size: 11px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.seg-thin {
  background: #79bbff;
}

.seg-normal {
  background: #67c23a;
}

.seg-fat {
  background: #e6a23c;
}

.seg-obese {
  background: #f56c6c;
}

.bmi-marker {
  position: absolute;
  top: -3px;
  width: 4px;
  height: 28px;
  background: #303133;
  border-radius: 2px;
  transform: translateX(-2px);
}

/* ③ 饮食 */
.macros {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.macro-item {
  background: #f7f8fa;
  border-radius: 8px;
  padding: 10px;
  text-align: center;
}

.m-label {
  font-size: 12px;
  color: #909399;
}

.m-gram {
  font-size: 20px;
  font-weight: 700;
}

.m-sub {
  font-size: 11px;
  color: #c0c4cc;
}

.budget {
  margin-top: 10px;
  font-size: 13px;
  color: #606266;
}

.advice-text {
  font-size: 13px;
  color: #606266;
  line-height: 1.7;
  margin: 10px 0 0;
}

.diet-title {
  margin: 14px 0 8px;
  font-weight: 600;
}

.day-tabs {
  margin-bottom: 10px;
}

.meal-day-total {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.meal-row {
  border-top: 1px dashed #ebeef5;
  padding: 8px 0;
}

.meal-head {
  display: flex;
  gap: 10px;
  align-items: baseline;
  font-size: 13px;
}

.meal-label {
  font-weight: 600;
}

.meal-time {
  color: #c0c4cc;
  font-size: 12px;
}

.meal-kcal {
  color: #e6a23c;
  font-size: 12px;
}

.meal-items {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.food-chip {
  background: #f2f3f5;
  border-radius: 12px;
  padding: 2px 10px;
  font-size: 12px;
  color: #606266;
}

.hint-small {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 8px;
}

/* ④ 运动 */
.intensity {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.i-label {
  color: #909399;
}

.i-pill {
  padding: 2px 12px;
  border-radius: 12px;
  background: #f2f3f5;
  color: #909399;
  font-size: 12px;
}

.i-pill.active {
  background: #4f7cff;
  color: #fff;
}

.i-value {
  margin-left: auto;
  font-weight: 600;
}

.dist {
  margin-top: 12px;
}

.dist-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 6px;
}

.d-label {
  width: 32px;
  color: #606266;
}

.d-track {
  flex: 1;
  height: 8px;
  background: #f2f3f5;
  border-radius: 4px;
  overflow: hidden;
}

.d-fill {
  height: 100%;
  background: #4f7cff;
}

.d-value {
  width: 40px;
  text-align: right;
  color: #909399;
}

.dist-tip {
  font-size: 11px;
  color: #c0c4cc;
}

.plan-meta {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #606266;
  margin: 12px 0 8px;
}

.train-days {
  border-top: 1px dashed #ebeef5;
}

.train-day {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
}

.td-name {
  font-weight: 600;
}

.td-meta {
  color: #909399;
  font-size: 12px;
}

.degraded-alert {
  margin-top: 12px;
}

.notes {
  margin-top: 10px;
  font-size: 12px;
  color: #e6a23c;
}

/* 底部固定条 */
.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: #fff;
  border-top: 1px solid #ebeef5;
  padding: 12px 18px;
  display: flex;
  justify-content: center;
  gap: 12px;
  z-index: 10;
}
</style>
