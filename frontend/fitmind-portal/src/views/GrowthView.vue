<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage } from 'element-plus'

import {
  getPointsBoard,
  getPointsSeasons,
  getSignRecord,
  getTodayPoints,
  signIn,
} from '@/api/learning'
import type {
  PointsBoard,
  PointsBoardSeason,
  PointsStatistics,
  SignRecord,
} from '@/types/learning'

/**
 * 成长中心（M7-4）：签到 + 今日积分 + 积分榜
 *
 * 三块内容放在一页的理由：它们是同一个闭环 ——
 * 训练 → 得积分 → 上榜，用户想知道的是"我今天拿了多少、排到第几"。
 * 拆成三个页面反而要在导航里放三个入口。
 */
const loading = ref(true)
const signing = ref(false)

const signRecord = ref<SignRecord | null>(null)
const todayPoints = ref<PointsStatistics[]>([])
const board = ref<PointsBoard | null>(null)
const seasons = ref<PointsBoardSeason[]>([])
/** 当前选的赛季：0 = 当前赛季（后端约定，不传或传 0 都表示当前） */
const activeSeason = ref<number>(0)

const boardPageNo = ref(1)
const boardPageSize = ref(10)
/** ★ 积分榜接口不返回 total，只能用"这一页没满"推断是否还有下一页 */
const boardHasMore = computed(() => (board.value?.boardList ?? []).length >= boardPageSize.value)

const today = new Date()
const todayDate = today.getDate()

/** 今天是否已签到：用后端给的"已签日期数组"判断，★ 不要在前端自己算日期 */
const signedToday = computed(() => (signRecord.value?.days ?? []).includes(todayDate))

/** 本月天数与 1 号是周几（周日=0） */
const daysInMonth = computed(() => new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate())
const firstWeekday = computed(() => new Date(today.getFullYear(), today.getMonth(), 1).getDay())
/** 日历前面的占位空格 */
const leadingBlanks = computed(() => Array.from({ length: firstWeekday.value }, (_, i) => i))
const monthDays = computed(() => Array.from({ length: daysInMonth.value }, (_, i) => i + 1))
const monthLabel = computed(() => `${today.getFullYear()} 年 ${today.getMonth() + 1} 月`)

onMounted(load)

async function load() {
  loading.value = true
  await Promise.all([loadSign(), loadTodayPoints(), loadBoard(), loadSeasons()])
  loading.value = false
}

async function loadSign() {
  try {
    signRecord.value = await getSignRecord()
  } catch {
    signRecord.value = null
  }
}

async function loadTodayPoints() {
  try {
    todayPoints.value = (await getTodayPoints()) ?? []
  } catch {
    todayPoints.value = []
  }
}

async function loadBoard() {
  try {
    board.value = await getPointsBoard({
      pageNo: boardPageNo.value,
      pageSize: boardPageSize.value,
      season: activeSeason.value,
    })
  } catch {
    board.value = null
  }
}

async function loadSeasons() {
  try {
    seasons.value = (await getPointsSeasons()) ?? []
  } catch {
    seasons.value = []
  }
}

async function handleSeasonChange(value: number) {
  activeSeason.value = value
  boardPageNo.value = 1
  await loadBoard()
}

async function handleBoardPage(pageNo: number) {
  boardPageNo.value = pageNo
  await loadBoard()
}

/**
 * 签到。
 *
 * ★ 返回的 `SignResult` 里带了本次得分明细（基础分 + 奖励），
 *   必须把它展示出来 —— 用户点"签到"最想知道的就是"我得了多少分"。
 *   只弹一句"签到成功"，用户还得自己去看积分有没有涨。
 */
async function handleSign() {
  signing.value = true
  try {
    const result = await signIn()
    ElMessage.success(
      `签到成功：连续 ${result.signDays} 天，本次 +${result.totalPoints} 分` +
        (result.rewardPoints > 0 ? `（含连签奖励 ${result.rewardPoints} 分）` : ''),
    )
    // 重新拉数据和积分榜（签到会加分，榜会变）
    await Promise.all([loadSign(), loadTodayPoints(), loadBoard()])
  } catch {
    // 重复签到等业务异常：后端文案已经通过拦截器弹出来了，这里只需把状态刷新回真实值
    await loadSign()
  } finally {
    signing.value = false
  }
}

/** 今日积分进度：maxPoints = 0 表示该类型不设上限 */
function percentToday(item: PointsStatistics): number {
  if (!item.maxPoints || item.maxPoints <= 0) return 0
  return Math.min(100, Math.round((item.points / item.maxPoints) * 100))
}

function unlimited(item: PointsStatistics): boolean {
  return !item.maxPoints || item.maxPoints <= 0
}
</script>

<template>
  <div v-loading="loading" class="growth-page">
    <div class="page-head">
      <h2>成长中心</h2>
      <span class="head-count">签到、积分、排行榜</span>
    </div>

    <div class="growth-body">
      <!-- 左列：签到 + 今日积分 -->
      <div class="left-col">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="block-header">
              <span class="block-title">{{ monthLabel }}签到</span>
              <el-tag v-if="signedToday" type="success" size="small">今天已签到</el-tag>
            </div>
          </template>

          <div class="weekday-row">
            <span v-for="w in ['日', '一', '二', '三', '四', '五', '六']" :key="w">{{ w }}</span>
          </div>

          <div class="calendar">
            <span v-for="i in leadingBlanks" :key="`b${i}`" class="day-cell is-blank" />
            <span
              v-for="d in monthDays"
              :key="d"
              class="day-cell"
              :class="{
                'is-signed': (signRecord?.days ?? []).includes(d),
                'is-today': d === todayDate,
              }"
            >
              {{ d }}
            </span>
          </div>

          <div class="sign-row">
            <div class="sign-info">
              <span class="sign-days" data-testid="growth-sign-days">
                连续签到 <b>{{ signRecord?.continuousDays ?? 0 }}</b> 天
              </span>
              <span class="sign-sub">本月已签 {{ (signRecord?.days ?? []).length }} 天</span>
            </div>
            <el-button
              type="primary"
              :disabled="signedToday"
              :loading="signing"
              data-testid="growth-sign-btn"
              @click="handleSign"
            >
              {{ signedToday ? '今天已签到' : '立即签到' }}
            </el-button>
          </div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>
            <span class="block-title">今日积分</span>
          </template>

          <el-empty v-if="!todayPoints.length" description="暂无积分记录" :image-size="60" />

          <div
            v-for="item in todayPoints"
            :key="item.type"
            class="points-row"
            data-testid="growth-today-points"
          >
            <div class="points-head">
              <span class="points-name">{{ item.typeDesc }}</span>
              <span class="points-value">
                +{{ item.points }}
                <span class="points-max">
                  {{ unlimited(item) ? '（不限）' : `/ ${item.maxPoints}` }}
                </span>
              </span>
            </div>
            <el-progress
              v-if="!unlimited(item)"
              :percentage="percentToday(item)"
              :stroke-width="6"
              :show-text="false"
              :color="percentToday(item) >= 100 ? '#67c23a' : '#ff6b35'"
            />
          </div>
        </el-card>
      </div>

      <!-- 右列：积分榜 -->
      <el-card shadow="never" class="block board-card">
        <template #header>
          <div class="block-header">
            <span class="block-title">积分榜</span>
            <el-select
              v-model="activeSeason"
              size="small"
              style="width: 160px"
              @change="handleSeasonChange"
            >
              <el-option :value="0" label="当前赛季（实时）" />
              <el-option
                v-for="s in seasons"
                :key="s.id"
                :value="s.id"
                :label="s.name"
              />
            </el-select>
          </div>
        </template>

        <div class="my-points">
          <div class="my-item">
            <span class="my-label">我的积分</span>
            <span class="my-value" data-testid="growth-my-points">{{ board?.points ?? 0 }}</span>
          </div>
          <div class="my-item">
            <span class="my-label">我的排名</span>
            <span class="my-value" data-testid="growth-my-rank">
              {{ board?.rank ? `第 ${board.rank} 名` : '未上榜' }}
            </span>
          </div>
        </div>

        <el-table :data="board?.boardList ?? []" class="board-table" size="small">
          <el-table-column label="排名" width="72">
            <template #default="{ row }">
              <span class="rank-cell" :class="`rank-${row.rank}`" data-testid="growth-board-rank">
                {{ row.rank }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="用户" data-testid="growth-board-name" />
          <el-table-column prop="points" label="积分" width="90" align="right" data-testid="growth-board-points" />
        </el-table>

        <el-empty
          v-if="!(board?.boardList ?? []).length"
          description="榜单还没有数据"
          :image-size="60"
          data-testid="growth-board-empty"
        />

        <div class="board-pager">
          <el-button
            size="small"
            :disabled="boardPageNo <= 1"
            @click="handleBoardPage(boardPageNo - 1)"
          >
            上一页
          </el-button>
          <span class="board-page">第 {{ boardPageNo }} 页</span>
          <el-button size="small" :disabled="!boardHasMore" @click="handleBoardPage(boardPageNo + 1)">
            下一页
          </el-button>
        </div>

        <div class="board-note">
          ★ 积分榜接口不返回总条数，所以这里是"上一页/下一页"而不是页码跳转
          （用「这一页有没有满」判断后面还有没有）。
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.growth-page {
  max-width: 1080px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.head-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.growth-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}

.left-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.block :deep(.el-card__body) {
  padding-top: 12px;
}

.block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.block-title {
  font-weight: 600;
}

.weekday-row {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
  margin-bottom: 6px;
}

.weekday-row span {
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.calendar {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}

.day-cell {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  border-radius: 6px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-lighter);
}

.day-cell.is-blank {
  background: transparent;
}

.day-cell.is-signed {
  background: var(--el-color-primary-light-7);
  color: var(--el-color-primary-dark-2);
  font-weight: 700;
}

.day-cell.is-today {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}

.sign-row {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sign-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sign-days b {
  font-size: 18px;
  color: var(--el-color-primary);
}

.sign-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.points-row {
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.points-row:last-child {
  border-bottom: none;
}

.points-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.points-name {
  font-size: 13px;
}

.points-value {
  font-size: 14px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.points-max {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.board-card {
  position: sticky;
  top: 84px;
}

.my-points {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 10px 0 14px;
}

.my-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.my-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.my-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.board-table {
  width: 100%;
}

.rank-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
}

.rank-cell.rank-1 {
  background: #ffd666;
  color: #7a5200;
}

.rank-cell.rank-2 {
  background: #d9dee7;
  color: #4a5568;
}

.rank-cell.rank-3 {
  background: #f0c9a4;
  color: #7a4a1d;
}

.board-pager {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.board-page {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.board-note {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

@media (max-width: 900px) {
  .growth-body {
    grid-template-columns: 1fr;
  }

  .board-card {
    position: static;
  }
}
</style>
