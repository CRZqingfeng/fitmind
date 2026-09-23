<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { DataLine, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { getAssessDetail, getAssessTrend, getLatestAssess, pageAssess, submitBodyAssess } from '@/api/assessment'
import type { BodyAssessReport, BodyTrend } from '@/types/assessment'
import { BODY_GOAL_TYPES, BMI_LEVELS } from '@/types/assessment'
import { formatDateTime, pageTotal } from '@/utils/format'

/**
 * 体测评估（M7-6）
 *
 * ★★ 本页最重要的一条原则：**所有指标都只显示后端算好的值**。
 *   BMI、分级、BMR、TDEE、去脂体重、脂肪重量、腰臀比、综合评分、结论文案、训练建议 ——
 *   全部由 fm-exam 计算后返回（`BodyAssessVO`）。
 *   前端**一个公式都不重算**，理由不是"偷懒"：
 *     · 这些公式带性别差异与分级阈值（中国标准 vs WHO），前端再实现一份，
 *       迟早和后端出现"同一份数据两个数"——而这是最难解释的一类 bug；
 *     · 重算也拿不到"结论文案"和"训练建议"，那两段是跟着目标类型走的规则文本。
 *   所以本页只做三件事：**渲染、解释（把分级翻译成人话）、引导（缺数据时引导去补）**。
 *
 * ⚠️ 两个空态（都实测过，不是猜的）：
 *   · 一次都没测过 → `GET /es/assessments/latest` 返回 `data: null`（不是报错）
 *   · 只有一次     → `trend` 会返回对象，但 `first`/`latest` 一样、各种 `*Change` 都是 null
 *   所以"能不能看趋势"要看 `times >= 2`，而不是"trend 是不是 null"。
 */

const loading = ref(true)
const latest = ref<BodyAssessReport | null>(null)
const trend = ref<BodyTrend | null>(null)

/** 历史分页 */
const history = ref<BodyAssessReport[]>([])
const historyTotal = ref(0)
const pageNo = ref(1)
const pageSize = ref(5)

/** 录入弹窗 */
const formVisible = ref(false)
const submitting = ref(false)
const form = reactive({
  height: undefined as number | undefined,
  weight: undefined as number | undefined,
  targetWeight: undefined as number | undefined,
  bodyFatRate: undefined as number | undefined,
  waist: undefined as number | undefined,
  chest: undefined as number | undefined,
  arm: undefined as number | undefined,
  hip: undefined as number | undefined,
  goalType: 1 as number | undefined,
  assessTime: '' as string,
})

/** 有没有体测记录（决定首屏是"报告"还是"引导录入"） */
const hasRecord = computed(() => Boolean(latest.value))
/** ★ 趋势要至少两次（后端只给"首次 vs 最新"，一次时各种变化都是 null） */
const canShowTrend = computed(() => Number(trend.value?.times ?? 0) >= 2)

async function loadAll() {
  loading.value = true
  try {
    const [l, t] = await Promise.all([getLatestAssess(), getAssessTrend()])
    latest.value = l ?? null
    trend.value = t ?? null
    await loadHistory()
  } catch {
    /* request.ts 已提示 */
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  const page = await pageAssess({ pageNo: pageNo.value, pageSize: pageSize.value })
  history.value = page?.list ?? []
  historyTotal.value = pageTotal(page)
}

function openForm() {
  Object.assign(form, {
    height: undefined,
    weight: undefined,
    targetWeight: undefined,
    bodyFatRate: undefined,
    waist: undefined,
    chest: undefined,
    arm: undefined,
    hip: undefined,
    goalType: 1,
    assessTime: '',
  })
  formVisible.value = true
}

async function handleSubmit() {
  // 只有身高体重是后端必填，其余选填 —— 前端的前置校验与后端保持一致即可
  if (!form.height || !form.weight) {
    ElMessage.warning('身高和体重是必填的')
    return
  }
  /**
   * ★★ 跨字段规则：**腰围与臀围必须"成对"提交**。
   *
   * 后端要算腰臀比（腰围 ÷ 臀围），只给一个算不出来，直接抛业务错误：
   *   「腰围和臀围要一起填（腰臀比 = 腰围 ÷ 臀围），只填一个算不出来」
   * 这条规则**前端必须镜像一份**，否则用户填了一半点提交，拿到的是一个服务端错误 ——
   * 虽然提示语很清楚，但那属于"用户不该犯的错"（表单自己就能拦）。
   */
  if ((form.waist && !form.hip) || (!form.waist && form.hip)) {
    ElMessage.warning('腰围和臀围要一起填（要算腰臀比），只填一个算不出来')
    return
  }
  submitting.value = true
  try {
    const body = {
      height: form.height,
      weight: form.weight,
      // ★ 选填项不填就**不传**（而不是传 null）：后端按"非空"判断有没有这一项
      targetWeight: form.targetWeight,
      bodyFatRate: form.bodyFatRate,
      waist: form.waist,
      chest: form.chest,
      arm: form.arm,
      hip: form.hip,
      goalType: form.goalType,
      // el-date-picker 给的是 Date；后端收 LocalDateTime，转成 'YYYY-MM-DDTHH:mm:ss'
      assessTime: form.assessTime ? toLocalIso(form.assessTime) : undefined,
    }
    await submitBodyAssess(body)
    formVisible.value = false
    ElMessage.success('体测已记录，报告已生成')
    pageNo.value = 1
    await loadAll()
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}

/** Date → 'YYYY-MM-DDTHH:mm:ss'（★ 不用 toISOString：那是 UTC，会差 8 小时） */
function toLocalIso(v: string | Date): string {
  const d = typeof v === 'string' ? new Date(v) : v
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// ==================== 展示辅助 ====================

function bmiTagType(level: number | null | undefined) {
  return BMI_LEVELS[Number(level)]?.type ?? 'info'
}

function goalLabel(t: number | null | undefined): string {
  return BODY_GOAL_TYPES[Number(t)] ?? '未设置'
}

function genderLabel(g: number | null | undefined): string {
  if (g === 0) return '男'
  if (g === 1) return '女'
  return '未知'
}

/** 带符号的变化量（0 也显示 +0，避免"空着看不出变化"） */
function signed(v: number | null | undefined, unit = ''): string {
  if (v === null || v === undefined) return '—'
  const n = Number(v)
  return `${n > 0 ? '+' : ''}${n}${unit}`
}

/**
 * 变化的"方向解读"
 *
 * ★ 为什么不能简单地"下降=绿、上升=红"：
 *   体重 -1.5kg 对**减脂**是好事，对**增肌**是坏事 —— 同一个数字、两种结论。
 *   所以这里按**最新一次记录的目标类型**给一句中性的说明，
 *   而不是替用户下"好坏"的结论（那需要更多上下文，后端也没给）。
 */
function directionNote(v: number | null | undefined, goalType: number | null | undefined): string {
  if (v === null || v === undefined) return ''
  const n = Number(v)
  if (n === 0) return '（与上次持平）'
  const g = Number(goalType)
  if (g === 1) return n > 0 ? '（增肌目标下，体重上升是预期方向）' : '（增肌目标下，体重下降要留意热量是否够）'
  if (g === 2) return n < 0 ? '（减脂目标下，体重下降是预期方向）' : '（减脂目标下，体重上升要留意热量缺口）'
  return ''
}

async function showDetail(row: BodyAssessReport) {
  try {
    const d = await getAssessDetail(row.id)
    // 历史详情就复用"最新报告"那块展示：把返回塞进一个临时对象即可
    historyDetail.value = d
    detailVisible.value = true
  } catch {
    /* 已提示 */
  }
}
const detailVisible = ref(false)
const historyDetail = ref<BodyAssessReport | null>(null)

onMounted(loadAll)
</script>

<template>
  <div class="assessment-page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">
          <el-icon><DataLine /></el-icon>
          体测评估
        </h2>
        <p class="page-desc">
          记录身体数据，生成 BMI / 体脂 / 基础代谢与训练建议 —— 指标全部由服务端计算
        </p>
      </div>
      <el-button type="primary" :icon="Plus" data-testid="assess-open-form" @click="openForm">
        记录体测
      </el-button>
    </div>

    <!-- ==================== 首测空态 ==================== -->
    <el-empty
      v-if="!loading && !hasRecord"
      description="还没有体测记录"
      data-testid="assess-empty"
    >
      <p class="empty-tip">
        第一次录入只要填<b>身高</b>和<b>体重</b>，其余（体脂率、围度、目标）都是选填 ——
        填得越多，报告里的建议越具体。
      </p>
      <el-button type="primary" :icon="Plus" @click="openForm">开始第一次体测</el-button>
    </el-empty>

    <template v-else-if="hasRecord && latest">
      <!-- ==================== 最新报告 ==================== -->
      <el-card class="fm-card report-card" shadow="never">
        <div class="report-head">
          <div class="score-box">
            <div class="score-num" data-testid="assess-score">{{ latest.score ?? '—' }}</div>
            <div class="score-label">综合评分</div>
          </div>
          <div class="report-main">
            <div class="report-line">
              <el-tag :type="bmiTagType(latest.bmiLevel)" effect="dark" size="large" data-testid="assess-bmi-level">
                BMI {{ latest.bmi ?? '—' }} · {{ latest.bmiLevelDesc ?? '—' }}
              </el-tag>
              <span class="meta" data-testid="assess-latest-time">
                {{ formatDateTime(latest.assessTime) }}
              </span>
            </div>
            <p class="summary" data-testid="assess-summary">{{ latest.summary || '—' }}</p>
            <div class="meta-row">
              <span>性别：{{ genderLabel(latest.gender) }}</span>
              <span>目标：{{ goalLabel(latest.goalType) }}</span>
              <span>体脂分级：{{ latest.fatLevelDesc ?? '—' }}</span>
              <span>腰臀比：{{ latest.whr ?? '—' }}（{{ latest.whrLevelDesc ?? '—' }}）</span>
            </div>
            <!-- ★ 性别缺失会影响 BMR/体脂分级（公式分性别）→ 明确提示，而不是给一个看起来正常的错数 -->
            <el-alert
              v-if="latest.gender !== 0 && latest.gender !== 1"
              class="gender-tip"
              type="warning"
              :closable="false"
              title="你的档案里还没有性别"
              description="基础代谢与体脂分级都按性别分档，补上性别后报告会更准（个人中心 → 资料）。"
            />
          </div>
        </div>

        <el-divider />

        <div class="metric-grid">
          <div class="metric">
            <span class="m-label">身高</span>
            <span class="m-value">{{ latest.height ?? '—' }} <i>cm</i></span>
          </div>
          <div class="metric">
            <span class="m-label">体重</span>
            <span class="m-value">{{ latest.weight ?? '—' }} <i>kg</i></span>
          </div>
          <div class="metric">
            <span class="m-label">目标体重</span>
            <span class="m-value">{{ latest.targetWeight ?? '—' }} <i>kg</i></span>
          </div>
          <div class="metric">
            <span class="m-label">体脂率</span>
            <span class="m-value">{{ latest.bodyFatRate ?? '—' }} <i>%</i></span>
          </div>
          <div class="metric">
            <span class="m-label">基础代谢 BMR</span>
            <span class="m-value" data-testid="assess-bmr">{{ latest.bmr ?? '—' }} <i>kcal</i></span>
          </div>
          <div class="metric">
            <span class="m-label">每日总消耗 TDEE</span>
            <span class="m-value" data-testid="assess-tdee">{{ latest.tdee ?? '—' }} <i>kcal</i></span>
          </div>
          <div class="metric">
            <span class="m-label">去脂体重</span>
            <span class="m-value">{{ latest.leanMass ?? '—' }} <i>kg</i></span>
          </div>
          <div class="metric">
            <span class="m-label">脂肪重量</span>
            <span class="m-value">{{ latest.fatMass ?? '—' }} <i>kg</i></span>
          </div>
          <div class="metric">
            <span class="m-label">腰围</span>
            <span class="m-value">{{ latest.waist ?? '—' }} <i>cm</i></span>
          </div>
          <div class="metric">
            <span class="m-label">胸围 / 臂围 / 臀围</span>
            <span class="m-value">
              {{ latest.chest ?? '—' }} / {{ latest.arm ?? '—' }} / {{ latest.hip ?? '—' }} <i>cm</i>
            </span>
          </div>
        </div>

        <el-divider />

        <div class="suggestion">
          <div class="block-title">训练与饮食建议</div>
          <p class="suggestion-text" data-testid="assess-suggestion">{{ latest.suggestion || '—' }}</p>
        </div>
      </el-card>

      <!-- ==================== 趋势 ==================== -->
      <el-card class="fm-card" shadow="never">
        <div class="block-title">变化趋势（首次 → 最新）</div>
        <template v-if="canShowTrend">
          <div class="trend-grid">
            <div class="trend-item">
              <span class="t-label">体测次数</span>
              <span class="t-value" data-testid="assess-trend-times">{{ trend?.times }} 次</span>
            </div>
            <div class="trend-item">
              <span class="t-label">间隔天数</span>
              <span class="t-value" data-testid="assess-trend-days">{{ trend?.days ?? '—' }} 天</span>
            </div>
            <div class="trend-item">
              <span class="t-label">体重变化</span>
              <span class="t-value" data-testid="assess-trend-weight">{{ signed(trend?.weightChange, ' kg') }}</span>
              <span class="t-note">{{ directionNote(trend?.weightChange, latest.goalType) }}</span>
            </div>
            <div class="trend-item">
              <span class="t-label">体脂率变化</span>
              <span class="t-value" data-testid="assess-trend-fat">{{ signed(trend?.fatRateChange, ' %') }}</span>
            </div>
            <div class="trend-item">
              <span class="t-label">腰围变化</span>
              <span class="t-value" data-testid="assess-trend-waist">{{ signed(trend?.waistChange, ' cm') }}</span>
            </div>
            <div class="trend-item">
              <span class="t-label">评分变化</span>
              <span class="t-value" data-testid="assess-trend-score">{{ signed(trend?.scoreChange) }}</span>
              <span class="t-note">（正数=变好）</span>
            </div>
          </div>
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          data-testid="assess-trend-insufficient"
          title="趋势需要至少两次体测"
          description="现在只有一次记录，暂时没有「变化」可比。过一段时间再测一次就能看到趋势了。"
        />
      </el-card>

      <!-- ==================== 历史 ==================== -->
      <el-card class="fm-card" shadow="never">
        <div class="block-title">
          历史记录
          <span class="head-count" data-testid="assess-history-total">共 {{ historyTotal }} 次</span>
        </div>
        <el-table :data="history" size="small" data-testid="assess-history">
          <el-table-column label="体测时间" min-width="150">
            <template #default="{ row }">{{ formatDateTime(row.assessTime) }}</template>
          </el-table-column>
          <el-table-column label="体重(kg)" width="100" prop="weight" />
          <el-table-column label="BMI" width="110">
            <template #default="{ row }">
              {{ row.bmi }} <span class="sub">{{ row.bmiLevelDesc }}</span>
            </template>
          </el-table-column>
          <el-table-column label="体脂率(%)" width="100" prop="bodyFatRate" />
          <el-table-column label="评分" width="90" prop="score" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" data-testid="assess-detail-btn" @click="showDetail(row as BodyAssessReport)">
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="historyTotal > pageSize"
          v-model:current-page="pageNo"
          class="pager"
          layout="prev, pager, next"
          :page-size="pageSize"
          :total="historyTotal"
          @current-change="loadHistory"
        />
      </el-card>
    </template>

    <!-- ==================== 录入弹窗 ==================== -->
    <el-dialog v-model="formVisible" title="记录一次体测" width="620px">
      <el-form label-position="top">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="身高（cm）" required>
              <el-input-number
                v-model="form.height"
                data-testid="assess-height"
                :min="80"
                :max="250"
                :precision="1"
                :controls="false"
                placeholder="必填"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="体重（kg）" required>
              <el-input-number
                v-model="form.weight"
                data-testid="assess-weight"
                :min="20"
                :max="300"
                :precision="1"
                :controls="false"
                placeholder="必填"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标体重（kg）">
              <el-input-number
                v-model="form.targetWeight"
                data-testid="assess-target-weight"
                :min="20"
                :max="300"
                :precision="1"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="训练目标">
              <el-select v-model="form.goalType" data-testid="assess-goal" style="width: 100%">
                <el-option v-for="(label, val) in BODY_GOAL_TYPES" :key="val" :label="label" :value="Number(val)" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="体脂率（%）">
              <el-input-number
                v-model="form.bodyFatRate"
                data-testid="assess-fat-rate"
                :min="1"
                :max="70"
                :precision="1"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="腰围（cm）">
              <!-- ★ 腰围/臀围必须成对填（后端要算腰臀比），标签上先把这条规则说清楚 -->
              <el-input-number v-model="form.waist" data-testid="assess-waist" :min="30" :max="200" :precision="1" :controls="false" style="width: 100%" />
              <span class="field-hint">腰围与臀围要一起填（用于腰臀比）</span>
            </el-form-item>
          </el-col>          <el-col :span="8">
            <el-form-item label="胸围（cm）">
              <el-input-number v-model="form.chest" :min="30" :max="200" :precision="1" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="臂围（cm）">
              <el-input-number v-model="form.arm" :min="10" :max="100" :precision="1" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="臀围（cm）">
              <el-input-number v-model="form.hip" data-testid="assess-hip" :min="30" :max="200" :precision="1" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="体测时间（留空=现在）">
              <el-date-picker
                v-model="form.assessTime"
                type="datetime"
                placeholder="可以补录以前的数据"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="assess-submit"
          @click="handleSubmit"
        >
          提交并生成报告
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 历史详情 ==================== -->
    <el-dialog v-model="detailVisible" title="体测详情" width="560px">
      <template v-if="historyDetail">
        <p class="detail-time">{{ formatDateTime(historyDetail.assessTime) }} · 评分 {{ historyDetail.score }}</p>
        <p class="detail-summary">{{ historyDetail.summary }}</p>
        <div class="metric-grid">
          <div class="metric"><span class="m-label">身高/体重</span><span class="m-value">{{ historyDetail.height }} / {{ historyDetail.weight }}</span></div>
          <div class="metric"><span class="m-label">BMI</span><span class="m-value">{{ historyDetail.bmi }} <i>{{ historyDetail.bmiLevelDesc }}</i></span></div>
          <div class="metric"><span class="m-label">体脂率</span><span class="m-value">{{ historyDetail.bodyFatRate ?? '—' }} <i>%</i></span></div>
          <div class="metric"><span class="m-label">腰臀比</span><span class="m-value">{{ historyDetail.whr ?? '—' }} <i>{{ historyDetail.whrLevelDesc ?? '' }}</i></span></div>
          <div class="metric"><span class="m-label">BMR / TDEE</span><span class="m-value">{{ historyDetail.bmr }} / {{ historyDetail.tdee }}</span></div>
          <div class="metric"><span class="m-label">去脂体重/脂肪</span><span class="m-value">{{ historyDetail.leanMass }} / {{ historyDetail.fatMass }}</span></div>
        </div>
        <p class="detail-suggestion">{{ historyDetail.suggestion }}</p>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.field-hint {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.assessment-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0 32px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 6px;
  font-size: 20px;
}

.page-desc {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.empty-tip {
  max-width: 520px;
  margin: 0 auto 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
  text-align: center;
}

/* ---- 报告 ---- */
.report-head {
  display: flex;
  gap: 24px;
}

.score-box {
  flex: 0 0 108px;
  padding: 12px 0;
  border-radius: 12px;
  background: linear-gradient(160deg, #ff6b35 0%, #ff8f5e 100%);
  color: #fff;
  text-align: center;
}

.score-num {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
}

.score-label {
  margin-top: 2px;
  font-size: 12px;
  opacity: 0.9;
}

.report-main {
  flex: 1;
  min-width: 0;
}

.report-line {
  display: flex;
  align-items: center;
  gap: 12px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.summary {
  margin: 10px 0;
  font-size: 14px;
  line-height: 1.7;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.gender-tip {
  margin-top: 10px;
}

/* ---- 指标网格 ---- */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}

.metric {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.m-label {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.m-value {
  display: block;
  margin-top: 2px;
  font-size: 16px;
  font-weight: 600;
}

.m-value i {
  font-size: 12px;
  font-style: normal;
  font-weight: 400;
  opacity: 0.7;
}

/* ---- 趋势 ---- */
.trend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.trend-item {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.t-label {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.t-value {
  display: block;
  margin-top: 2px;
  font-size: 18px;
  font-weight: 600;
}

.t-note {
  display: block;
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.5;
}

/* ---- 建议 / 详情 ---- */
.block-title {
  font-size: 15px;
  font-weight: 600;
}

.suggestion-text,
.detail-suggestion {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.8;
}

.detail-time {
  margin: 0 0 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.detail-summary {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
}

.head-count {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
