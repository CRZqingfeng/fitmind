<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { finishWorkout, getWorkoutSession, logWorkoutSet, removeWorkoutSet } from '@/api/plan'
import SetRow from '@/components/plan/SetRow.vue'
import type { WorkoutItem, WorkoutSession } from '@/types/plan'
import { SessionStatus } from '@/types/plan'
import { formatClock } from '@/utils/format'

/**
 * 训练执行页（M11-2 / P0）
 *
 * ★★ 页面存在的理由只有一句话：**每一组打完勾就提交一次**。
 *   等"全部做完再提交"的方案，用户中途接个电话、切后台被系统杀、手滑刷新，
 *   进度就全丢了 —— 而他真的练了 20 分钟。后端也因此按
 *   `session + item + setNo` 覆盖写入，重复提交不会多出一组。
 *
 * ★★ 刷新恢复：进页面先 `GET /pls/workouts/{id}`，把 `setLogs` 填回每一行。
 *   这是"每组都落库"的另一半 —— 只提交不恢复，刷新后还是白板。
 *
 * ★ 组间休息倒计时的秒数用**后端给的 `restSec`**，不要在前端写死 60：
 *   计划里拉伸组是 0 秒、复合动作是 90 秒，写死 60 会让用户以为程序算错了。
 *
 * ★ 打勾可撤销（M11-2 P0 补齐）：再点一次已勾的组 → `DELETE .../sets/{setNo}`，
 *   后端把同一动作项后面的组号前移，前端**重新拉一次执行页**而不是自己减一 ——
 *   组号重排只有一份实现（后端），两边各写一遍迟早出现"界面第 2 组、库里第 3 组"。
 */
const route = useRoute()
const router = useRouter()

const sessionId = computed(() => String(route.params.sessionId ?? ''))

const loading = ref(true)
const errorText = ref('')
const session = ref<WorkoutSession | null>(null)

/** 每一组的前端状态（按 itemId 分组，index 即 setNo-1） */
interface SetState {
  setNo: number
  weightKg: number | null
  reps: number
  durationSec: number
  done: boolean
}

const setsByItem = ref<Record<string, SetState[]>>({})
const submittingItem = ref<string>('')

/** 当前动作下标：第一个"还没做完"的动作；全部做完则停在最后一个 */
const currentIndex = ref(0)

/** 休息倒计时 */
const restRemain = ref(0)
const restTotal = ref(0)
let restTimer: ReturnType<typeof setInterval> | null = null

/** 已训练时长（秒），每秒刷一次 */
const elapsed = ref(0)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

const items = computed<WorkoutItem[]>(() => session.value?.items ?? [])
const currentItem = computed<WorkoutItem | null>(() => items.value[currentIndex.value] ?? null)
const isFinished = computed(() => session.value?.status === SessionStatus.FINISHED)

const doneSets = computed(() =>
  Object.values(setsByItem.value).reduce((acc, arr) => acc + arr.filter((s) => s.done).length, 0),
)
const totalSets = computed(() => items.value.reduce((acc, it) => acc + it.sets, 0))
const percent = computed(() =>
  totalSets.value > 0 ? Math.round((doneSets.value / totalSets.value) * 100) : 0,
)

/** 从接口数据初始化每行状态：★ 已完成的组必须从 setLogs 恢复 */
function buildSets(from: WorkoutSession) {
  const map: Record<string, SetState[]> = {}
  for (const item of from.items) {
    const logs = item.setLogs ?? []
    map[item.itemId] = Array.from({ length: item.sets }, (_, i) => {
      const setNo = i + 1
      const log = logs.find((l) => l.setNo === setNo)
      return {
        setNo,
        // 没打过勾的组用计划建议值预填，用户改完直接打勾就行
        weightKg: log?.weightKg ?? item.weightKg ?? null,
        reps: log?.reps ?? item.reps ?? 0,
        durationSec: log?.durationSec ?? item.durationSec ?? 0,
        done: !!log && log.done === 1,
      }
    })
  }
  setsByItem.value = map
}

function pickCurrentIndex(from: WorkoutSession) {
  const idx = from.items.findIndex((it) => {
    const sets = setsByItem.value[it.itemId] ?? []
    return sets.some((s) => !s.done)
  })
  currentIndex.value = idx >= 0 ? idx : Math.max(from.items.length - 1, 0)
}

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    const data = await getWorkoutSession(sessionId.value)
    session.value = data
    buildSets(data)
    pickCurrentIndex(data)
    startElapsed()
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '训练数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

onUnmounted(() => {
  stopRest()
  if (elapsedTimer) clearInterval(elapsedTimer)
})

function startElapsed() {
  if (elapsedTimer) clearInterval(elapsedTimer)
  // ★ 已完成的训练显示**库里存的 duration_sec**，不要继续按 now-startTime 累加：
  //   否则昨天练完的这次训练，今天打开会显示"练了 20 小时"。
  if (isFinished.value) {
    elapsed.value = session.value?.durationSec ?? 0
    return
  }
  const start = parseTime(session.value?.startTime)
  elapsed.value = start ? Math.max(0, Math.floor((Date.now() - start) / 1000)) : 0
  elapsedTimer = setInterval(() => {
    if (isFinished.value) return
    elapsed.value = start ? Math.max(0, Math.floor((Date.now() - start) / 1000)) : elapsed.value + 1
  }, 1000)
}

/** 后端 LocalDateTime 没有时区，按本地时间解析（与 utils/format.ts 的口径一致） */
function parseTime(value: string | null | undefined): number | null {
  if (!value) return null
  const m = String(value).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/)
  if (!m) return null
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime()
}

// ==================== 组操作 ====================

function setsOf(item: WorkoutItem): SetState[] {
  return setsByItem.value[item.itemId] ?? []
}

/**
 * 打勾：**先提交，成功了才改界面**。
 *
 * 反过来（先改界面再提交）的后果：网络失败时页面显示"已完成"，
 * 但库里没有 —— 用户刷新后发现白练了，而且没有任何线索。
 */
async function toggleSet(item: WorkoutItem | null, set: SetState) {
  if (!item || isFinished.value) return
  // 已勾 → 撤销（删这一组）；没勾 → 记录一次
  if (set.done) {
    await uncheckSet(item, set)
    return
  }
  submittingItem.value = item.itemId
  try {
    await logWorkoutSet(sessionId.value, {
      itemId: item.itemId,
      setNo: set.setNo,
      weightKg: set.weightKg ?? undefined,
      reps: set.reps,
      durationSec: set.durationSec > 0 ? set.durationSec : undefined,
    })
    set.done = true
    setsByItem.value = { ...setsByItem.value }

    // 这一项还有没做的组 → 起倒计时；做完了就自动跳到下一项
    const remain = setsOf(item).filter((s) => !s.done)
    if (remain.length > 0) {
      startRest(item.restSec)
    } else {
      stopRest()
      const next = items.value.findIndex((it) => (setsByItem.value[it.itemId] ?? []).some((s) => !s.done))
      if (next >= 0) currentIndex.value = next
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '记录失败，请再点一次')
  } finally {
    submittingItem.value = ''
  }
}

/**
 * 撤销打勾（删一组）。
 *
 * ★ 删完**重新拉执行页**：组号是后端重排的，前端如果自己把后面的组号减一，
 *   就出现了第二份"重排"实现 —— 两份逻辑一定会有一天对不上。
 * ★ 不弹二次确认：用户点错了会立刻再点一下，弹窗反而更烦；
 *   而且这一组的数据真的只是"这一组"，重打一次成本很低。
 */
async function uncheckSet(item: WorkoutItem, set: SetState) {
  submittingItem.value = item.itemId
  try {
    await removeWorkoutSet(sessionId.value, item.itemId, set.setNo)
    stopRest()
    const data = await getWorkoutSession(sessionId.value)
    session.value = data
    buildSets(data)
    // 留在当前动作上，别把用户甩回第一个动作
    const idx = data.items.findIndex((it) => it.itemId === item.itemId)
    currentIndex.value = idx >= 0 ? idx : 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消打勾失败，刷新页面看看')
  } finally {
    submittingItem.value = ''
  }
}

function startRest(seconds: number) {
  stopRest()
  if (!seconds || seconds <= 0) return
  restTotal.value = seconds
  restRemain.value = seconds
  restTimer = setInterval(() => {
    restRemain.value -= 1
    if (restRemain.value <= 0) stopRest()
  }, 1000)
}

function stopRest() {
  if (restTimer) clearInterval(restTimer)
  restTimer = null
  restRemain.value = 0
}

// ==================== 完成训练 ====================

async function handleFinish() {
  const unfinished = totalSets.value - doneSets.value
  try {
    await ElMessageBox.confirm(
      unfinished > 0
        ? `还有 ${unfinished} 组没打勾，确定现在就结束这次训练吗？`
        : '这次训练全部完成，结束并保存？',
      '完成训练',
      { confirmButtonText: '完成训练', cancelButtonText: '继续练', type: unfinished > 0 ? 'warning' : 'success' },
    )
  } catch {
    return
  }
  try {
    await finishWorkout(sessionId.value)
    ElMessage.success('训练完成，已记录到「今日训练」')
    router.push('/plan')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '完成训练失败')
  }
}

function goBack() {
  router.push('/plan')
}
</script>

<template>
  <div class="workout">
    <div class="head">
      <el-button text @click="goBack">‹ 返回</el-button>
      <div class="head-mid">
        <div class="plan-name">{{ session?.planTitle || '' }}</div>
        <div class="day-name">{{ session?.planDayName || '' }}</div>
      </div>
      <div class="head-right">
        <span class="elapsed">{{ formatClock(elapsed) }}</span>
        <el-tag v-if="isFinished" type="success">已完成</el-tag>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />

    <el-alert v-else-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
      <template #default>
        <el-button size="small" @click="load">重试</el-button>
      </template>
    </el-alert>

    <template v-else-if="session">
      <div class="progress-bar">
        <el-progress :percentage="percent" :stroke-width="6" :show-text="false" />
        <span class="progress-text">{{ doneSets }} / {{ totalSets }} 组</span>
      </div>

      <!-- 当前动作 -->
      <div v-if="currentItem" class="current">
        <div class="cur-head">
          <el-image :src="currentItem.imageUrl || ''" fit="cover" class="cur-img">
            <template #error>
              <div class="cur-ph">{{ currentItem.exerciseName.slice(0, 1) }}</div>
            </template>
          </el-image>
          <div class="cur-info">
            <div class="cur-name">
              {{ currentItem.exerciseName }}
              <el-tag
                v-if="currentItem.groupType !== 0"
                size="small"
                type="warning"
                effect="plain"
              >
                {{ currentItem.groupTypeLabel }}
              </el-tag>
            </div>
            <div class="cur-prescription">{{ currentItem.prescription }}</div>
          </div>
          <div class="cur-nav">
            <el-button size="small" :disabled="currentIndex === 0" @click="currentIndex--">上一个</el-button>
            <el-button
              size="small"
              :disabled="currentIndex >= items.length - 1"
              @click="currentIndex++"
            >
              下一个
            </el-button>
          </div>
        </div>

        <!-- 每一组 -->
        <div class="sets">
          <SetRow
            v-for="s in setsOf(currentItem)"
            :key="s.setNo"
            v-model:weight-kg="s.weightKg"
            v-model:reps="s.reps"
            :set-no="s.setNo"
            :duration-sec="s.durationSec"
            :done="s.done"
            :disabled="s.done || isFinished || submittingItem === currentItem.itemId"
            :toggle-disabled="isFinished || submittingItem === currentItem.itemId"
            @toggle="toggleSet(currentItem, s)"
          />
        </div>
      </div>

      <!-- 组间休息倒计时 -->
      <transition name="fade">
        <div v-if="restRemain > 0" class="rest-panel">
          <div class="rest-title">组间休息</div>
          <div class="rest-num">{{ restRemain }}<span class="rest-unit">s</span></div>
          <div class="rest-total">共 {{ restTotal }} 秒</div>
          <el-button size="small" @click="stopRest">跳过休息</el-button>
        </div>
      </transition>

      <!-- 全部动作总览：勾过的能一眼看到 -->
      <div class="all-items">
        <div class="all-title">本日动作</div>
        <div
          v-for="(it, idx) in items"
          :key="it.itemId"
          class="mini-row"
          :class="{ active: idx === currentIndex }"
          @click="currentIndex = idx"
        >
          <span class="mini-index">{{ idx + 1 }}</span>
          <span class="mini-name">{{ it.exerciseName }}</span>
          <span class="mini-sets">
            {{ setsOf(it).filter((s) => s.done).length }} / {{ it.sets }} 组
          </span>
        </div>
      </div>

      <div class="bottom-bar">
        <span class="tip">已勾选的组会立即保存，刷新页面不会丢</span>
        <el-button v-if="isFinished" type="primary" size="large" @click="goBack">返回计划首页</el-button>
        <el-button v-else type="primary" size="large" @click="handleFinish">完成训练</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.workout {
  max-width: 760px;
  margin: 0 auto;
  padding-bottom: 84px;
}

.head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.head-mid {
  flex: 1;
  min-width: 0;
}

.plan-name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.day-name {
  font-size: 17px;
  font-weight: 600;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.elapsed {
  font-variant-numeric: tabular-nums;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-color-primary);
}

.progress-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.progress-bar :deep(.el-progress) {
  flex: 1;
}

.progress-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  width: 72px;
  text-align: right;
}

.current {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  padding: 14px;
}

.cur-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.cur-img {
  width: 64px;
  height: 64px;
  border-radius: 10px;
  flex-shrink: 0;
  background: var(--el-fill-color-light);
}

.cur-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: var(--el-text-color-secondary);
}

.cur-info {
  flex: 1;
  min-width: 0;
}

.cur-name {
  font-size: 17px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.cur-prescription {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.cur-nav {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.sets {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rest-panel {
  position: sticky;
  bottom: 76px;
  margin: 12px auto 0;
  width: 200px;
  text-align: center;
  padding: 12px;
  border-radius: 14px;
  background: var(--el-color-primary);
  color: #fff;
  box-shadow: 0 8px 24px rgba(64, 128, 255, 0.3);
}

.rest-title {
  font-size: 12px;
  opacity: 0.9;
}

.rest-num {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.rest-unit {
  font-size: 14px;
  margin-left: 2px;
}

.rest-total {
  font-size: 11px;
  opacity: 0.8;
  margin-bottom: 8px;
}

.all-items {
  margin-top: 16px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 10px 14px;
}

.all-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.mini-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.mini-row.active {
  background: var(--el-color-primary-light-9);
}

.mini-index {
  width: 18px;
  color: var(--el-text-color-secondary);
}

.mini-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-sets {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 24px;
  background: #fff;
  border-top: 1px solid var(--el-border-color-light);
  z-index: 20;
}

.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
