<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { Ticket } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { exchangeCouponByCode, myCoupons } from '@/api/trade'
import type { Coupon } from '@/types/trade'
import { UserCouponStatus } from '@/types/trade'
import { formatDateTime } from '@/utils/format'
import { ApiError } from '@/utils/request'

/**
 * 我的优惠券 + 兑换码（M7-12）
 *
 * ★ 这是 **M7 方案 §7.1 第 10 页「我的优惠券 / 兑换码」**——方案里写了，但此前**没有页面**：
 *   `GET /prs/user-coupons` 只在结算页被调用（当"可选券列表"用），
 *   用户没有任何入口查看自己总共有哪些券、也没地方输兑换码。
 *
 * 用到的两个接口：
 *   · `GET  /prs/user-coupons`（可按 status 筛选）
 *   · `POST /prs/user-coupons/exchange/{code}` ★ 兑换码是一次性的
 *
 * ⚠️ 一个已知的后端局限（`#C27`，不在本轮范围）：
 *   优惠券**没有"券列表"查询接口**，只有"我的券"。所以这个页面能显示的是
 *   "我已经领到的券"，而不是"有哪些券可以领"。
 *   → 页面上如实说明，而不是给一个点了会报错的"去领券"按钮。
 */
const list = ref<Coupon[]>([])
const loading = ref(false)
/** undefined = 全部；1-未使用 2-已使用 3-已失效 */
const status = ref<number | undefined>(undefined)

const code = ref('')
const exchanging = ref(false)

const isEmpty = computed(() => !loading.value && list.value.length === 0)

async function load() {
  loading.value = true
  try {
    list.value = (await myCoupons(status.value)) ?? []
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

function switchStatus(next: number | undefined) {
  status.value = next
  load()
}

async function handleExchange() {
  const value = code.value.trim()
  if (!value) {
    ElMessage.warning('请输入兑换码')
    return
  }
  exchanging.value = true
  try {
    await exchangeCouponByCode(value)
    ElMessage.success('兑换成功，券已到账')
    code.value = ''
    // 兑换成功后把筛选切回"未使用"，否则用户切到"已使用"tab 时会以为没到账
    status.value = UserCouponStatus.UNUSED
    await load()
  } catch (e) {
    // 二次兑换会被后端拒（券码一次性），把后端文案如实展示
    ElMessage.error(e instanceof ApiError ? e.message : '兑换失败')
  } finally {
    exchanging.value = false
  }
}

/** 券状态的展示文案与颜色（后端 status 是权威，这里只做映射） */
function statusText(c: Coupon): string {
  if (c.status === UserCouponStatus.USED) return '已使用'
  if (c.status === UserCouponStatus.INVALID || c.expired) return '已失效'
  return '未使用'
}

function statusType(c: Coupon): 'success' | 'info' | 'warning' {
  if (c.status === UserCouponStatus.USED) return 'info'
  if (c.status === UserCouponStatus.INVALID || c.expired) return 'warning'
  return 'success'
}
</script>

<template>
  <div class="coupons-page">
    <div class="page-head">
      <h2>我的优惠券</h2>
      <span class="head-count">共 {{ list.length }} 张</span>
    </div>

    <!-- 兑换码 -->
    <el-card shadow="never" class="exchange-card">
      <div class="exchange-row">
        <el-input
          v-model="code"
          data-testid="coupon-exchange-input"
          placeholder="输入兑换码"
          clearable
          @keyup.enter="handleExchange"
        >
          <template #prefix>
            <el-icon><Ticket /></el-icon>
          </template>
        </el-input>
        <el-button
          data-testid="coupon-exchange-submit"
          type="primary"
          :loading="exchanging"
          @click="handleExchange"
        >
          兑换
        </el-button>
      </div>
      <div class="exchange-hint">
        兑换码一次性有效，重复兑换会被后端拒绝。
      </div>
    </el-card>

    <!-- 状态筛选 -->
    <el-radio-group
      class="status-tabs"
      :model-value="status === undefined ? 'all' : String(status)"
      data-testid="coupon-status-tabs"
      @update:model-value="
        (v) => switchStatus(v === 'all' ? undefined : Number(v))
      "
    >
      <el-radio-button value="all">全部</el-radio-button>
      <el-radio-button :value="String(UserCouponStatus.UNUSED)">未使用</el-radio-button>
      <el-radio-button :value="String(UserCouponStatus.USED)">已使用</el-radio-button>
      <el-radio-button :value="String(UserCouponStatus.INVALID)">已失效</el-radio-button>
    </el-radio-group>

    <el-empty v-if="isEmpty" description="这里还没有券" data-testid="coupons-empty">
      <span class="empty-tip">
        券的来源是「活动发放」或「兑换码」——本项目没有"券列表"接口（后端 #C27），
        所以这里只展示已领到的券。
      </span>
    </el-empty>

    <div v-loading="loading" class="coupon-list">
      <el-card
        v-for="c in list"
        :key="c.id"
        shadow="never"
        class="coupon-card"
        data-testid="coupon-card"
      >
        <div class="coupon-left">
          <div class="coupon-name" data-testid="coupon-name">{{ c.name }}</div>
          <div class="coupon-rule" data-testid="coupon-rule">{{ c.rule || '—' }}</div>
        </div>
        <div class="coupon-right">
          <el-tag :type="statusType(c)" data-testid="coupon-status">{{ statusText(c) }}</el-tag>
          <div class="coupon-term">
            {{ formatDateTime(c.termBeginTime) }} ~ {{ formatDateTime(c.termEndTime) }}
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.coupons-page {
  max-width: 880px;
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

.exchange-card {
  margin-bottom: 16px;
}

.exchange-row {
  display: flex;
  gap: 8px;
}

.exchange-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.status-tabs {
  margin-bottom: 16px;
}

.coupon-list {
  min-height: 100px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.coupon-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.coupon-name {
  font-size: 15px;
  font-weight: 600;
}

.coupon-rule {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.coupon-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.coupon-term {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: block;
  max-width: 420px;
  line-height: 1.7;
}
</style>
