<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { executePlan, getPlanDetail } from '@/api/plan'
import DayCard from '@/components/plan/DayCard.vue'
import type { PlanDetail } from '@/types/plan'

/**
 * 计划详情（M11-2 / P0）
 *
 * 官方计划与自建计划**共用这一个页面**：差别只有两处
 *   ① 底部按钮：官方的只有「执行本计划」，自建的还有「编辑」
 *   ② 数据来源相同（`GET /pls/plans/{id}` 对两种来源都返回 `editable`）
 *
 * ★★ 三个指标卡的数字全部来自后端：
 *   `trainDaysPerWeek` / `kcalEstimate` / `difficultyLabel`。
 *   "7个动作·预计43分钟"这种串也在 `day.summary` 里拼好了 ——
 *   前端再算一遍的话，两处口径一旦不同，演示现场就会对不上。
 *
 * ★ `kcalEstimate = 0` 表示**暂无估算**（动作数据集没有热量字段），
 *   必须显示"待估"，**不能显示 0** —— "0 千卡"看起来像这个计划不消耗热量。
 */
const route = useRoute()
const router = useRouter()

const planId = computed(() => String(route.params.id ?? ''))
const loading = ref(true)
const errorText = ref('')
const detail = ref<PlanDetail | null>(null)
const submitting = ref(false)

const totalItemCount = computed(() =>
  (detail.value?.days ?? []).reduce((acc, d) => acc + d.itemCount, 0),
)

const kcalText = computed(() => {
  const v = detail.value?.kcalEstimate ?? 0
  return v > 0 ? `${v}` : '待估'
})

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    detail.value = await getPlanDetail(planId.value)
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '计划加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

/**
 * 执行本计划（= 加入我的计划，参考 App 没有独立的"加入"按钮）。
 *
 * ★ 后端幂等：连点三次也只有一条 user_plan，开始日期被重置为今天。
 *   所以这里不需要"按钮置灰 + 防抖"那套 —— 但置灰是为了**体验**：
 *   重复点击会让用户以为没生效，从而点第四次。
 */
async function handleExecute() {
  if (submitting.value) return
  submitting.value = true
  try {
    await executePlan(planId.value)
    ElMessage.success('已加入我的计划，首页「今日训练」会显示今天的安排')
    await load()
  } catch (e) {
    // 业务校验失败是 HTTP 200 + code 400，message 就是后端给的文案
    // （"「周一练胸」还没有动作，先给它加一个动作再执行"），直接展示，不要改写
    ElMessage.error(e instanceof Error ? e.message : '执行失败')
  } finally {
    submitting.value = false
  }
}

function goEdit() {
  router.push(`/plan/edit/${planId.value}`)
}
</script>

<template>
  <div class="plan-detail">
    <el-skeleton v-if="loading" :rows="6" animated />

    <el-alert v-else-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
      <template #default>
        <el-button size="small" @click="load">重试</el-button>
      </template>
    </el-alert>

    <template v-else-if="detail">
      <!-- 头部封面 -->
      <div class="cover">
        <el-image v-if="detail.coverUrl" :src="detail.coverUrl" fit="cover" class="cover-img">
          <template #error>
            <div class="cover-ph">{{ detail.title.slice(0, 2) }}</div>
          </template>
        </el-image>
        <div v-else class="cover-ph">{{ detail.title.slice(0, 2) }}</div>
        <div class="cover-mask">
          <div class="title">{{ detail.title }}</div>
          <div class="summary">{{ detail.summary || '暂无描述' }}</div>
        </div>
      </div>

      <!-- 三个指标卡 -->
      <div class="metrics">
        <div class="metric">
          <div class="m-num">一周 {{ detail.trainDaysPerWeek }} 练</div>
          <div class="m-label">训练频率</div>
        </div>
        <div class="metric">
          <div class="m-num">{{ kcalText }}<span v-if="kcalText !== '待估'" class="unit">千卡</span></div>
          <div class="m-label">预估消耗</div>
        </div>
        <div class="metric">
          <div class="m-num">{{ detail.difficultyLabel }}</div>
          <div class="m-label">难度</div>
        </div>
      </div>

      <!-- 器械横滑 -->
      <div v-if="detail.equipment.length" class="equipment-bar">
        <span class="bar-label">器械</span>
        <div class="chips">
          <el-tag v-for="e in detail.equipment" :key="e" size="small" effect="plain" type="info">
            {{ e }}
          </el-tag>
        </div>
      </div>

      <!-- 计划内容 -->
      <div class="content-head">
        <span class="content-title">计划内容</span>
        <span class="content-sub">
          共 {{ detail.days.length }} 个训练日 · {{ totalItemCount }} 个动作
        </span>
      </div>

      <el-empty
        v-if="detail.days.length === 0"
        description="这个计划还没有训练日"
        :image-size="70"
      >
        <el-button v-if="detail.editable" type="primary" @click="goEdit">去编排</el-button>
      </el-empty>

      <DayCard
        v-for="day in detail.days"
        :key="day.id"
        :day="day"
        :editable="false"
      />

      <!-- 底部固定双按钮 -->
      <div class="bottom-bar">
        <el-button v-if="detail.editable" size="large" @click="goEdit">编辑</el-button>
        <el-button
          type="primary"
          size="large"
          class="execute-btn"
          :loading="submitting"
          @click="handleExecute"
        >
          {{ detail.userPlanStatus === null ? '执行本计划' : '重新执行本计划' }}
        </el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.plan-detail {
  max-width: 880px;
  margin: 0 auto;
  padding-bottom: 84px;
}

.cover {
  position: relative;
  height: 200px;
  border-radius: 14px;
  overflow: hidden;
  background: linear-gradient(135deg, #4f7cff 0%, #7aa2ff 100%);
}

.cover-img {
  width: 100%;
  height: 100%;
}

.cover-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 44px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.9);
  letter-spacing: 4px;
}

.cover-mask {
  position: absolute;
  inset: auto 0 0 0;
  padding: 14px 18px;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.55), transparent);
  color: #fff;
}

.title {
  font-size: 20px;
  font-weight: 700;
}

.summary {
  font-size: 13px;
  opacity: 0.9;
  margin-top: 4px;
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
  color: var(--el-text-color-primary);
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
  margin-top: 4px;
}

.equipment-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  margin-bottom: 14px;
  overflow: hidden;
}

.bar-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.chips {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
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

.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: 12px;
  justify-content: center;
  padding: 12px 24px;
  background: #fff;
  border-top: 1px solid var(--el-border-color-light);
  z-index: 20;
}

.execute-btn {
  min-width: 200px;
}
</style>
