<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { CircleCheckFilled, Refresh, Wallet } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { applyPay, getOrder, mockPay } from '@/api/trade'
import type { Order, PayUrl } from '@/types/trade'
import { OrderStatus } from '@/types/trade'
import { formatDateTime, formatPrice, formatRemaining } from '@/utils/format'

/**
 * 支付页（M7-3）
 *
 * ★ 金额从哪来：**从订单里读**（`GET /ts/orders/{id}` 的 `realAmount`），
 *   不从上一页的 query 里传。理由：query 是"用户能改的东西"，
 *   而这个页面要展示的是"你到底欠多少钱" —— 这类数字不该有第二条来源。
 *
 * ★ Mock 渠道是**真的走回调**的：点"模拟支付成功"会打 `/ps/mock/pay/{payOrderNo}`，
 *   它内部发起一次带签名的回调 → fm-pay 验签 → 发 MQ → fm-trade 改订单状态。
 *   所以它不是"前端直接把订单改成已支付"，支付链路的每一环都真的跑了一遍。
 */
const route = useRoute()
const router = useRouter()

const orderId = computed(() => String(route.params.orderId ?? ''))
const order = ref<Order | null>(null)
const payUrl = ref<PayUrl | null>(null)

const loading = ref(true)
const creating = ref(false)
const paying = ref(false)
/** 支付成功（或本来就已支付） */
const settled = ref(false)

const remaining = ref('—')
let timer: number | undefined

const amount = computed(() => order.value?.realAmount ?? 0)
const canPay = computed(() => order.value?.status === OrderStatus.NO_PAY)

onMounted(async () => {
  if (!orderId.value) {
    ElMessage.error('缺少订单号')
    router.replace('/orders')
    return
  }
  await loadOrder()
  tick()
  timer = window.setInterval(tick, 10000)
  loading.value = false

  // 只有待支付的订单才需要去申请支付单；已支付的（比如用户刷新了页面）直接展示成功
  if (canPay.value) {
    await createPay()
  } else {
    settled.value = true
  }
})

onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

function tick() {
  remaining.value = formatRemaining(order.value?.payOutTime)
}

async function loadOrder() {
  try {
    order.value = await getOrder(orderId.value)
  } catch {
    /* request.ts 已提示 */
  }
}

/**
 * 申请支付单。
 *
 * 后端会**复用**该订单未过期的支付单（返回 `reused=true`），
 * 所以"返回上一页再进来"不会在 fm-pay 里堆出一串支付单。
 */
async function createPay() {
  creating.value = true
  try {
    payUrl.value = await applyPay(orderId.value)
  } catch {
    /* 已提示 */
  } finally {
    creating.value = false
  }
}

/**
 * 模拟用户完成付款，然后**轮询订单状态**。
 *
 * ★ 为什么要轮询而不是直接改本地状态：
 *   订单状态由 fm-trade 消费 MQ 消息后更新，中间隔着一次异步投递。
 *   点完就假设"已支付"，会在消息稍慢时出现"页面说支付成功、订单列表还是待支付"。
 */
async function handleMockPay() {
  if (!payUrl.value?.payOrderNo) return
  paying.value = true
  try {
    await mockPay(payUrl.value.payOrderNo)
    const ok = await pollUntilSettled()
    if (ok) {
      settled.value = true
      ElMessage.success('支付成功')
    } else {
      ElMessage.warning('支付已受理，但订单状态还没更新，请稍后在「我的订单」里查看')
    }
  } catch {
    /* 已提示 */
  } finally {
    paying.value = false
  }
}

/** 最多等 15 秒（每 1 秒查一次） */
async function pollUntilSettled(): Promise<boolean> {
  for (let i = 0; i < 15; i++) {
    await new Promise((r) => setTimeout(r, 1000))
    await loadOrder()
    if (order.value && order.value.status !== OrderStatus.NO_PAY) return true
  }
  return false
}

async function handleRefresh() {
  await loadOrder()
  if (!canPay.value) settled.value = true
}
</script>

<template>
  <div v-loading="loading" class="pay-page">
    <div class="page-head">
      <h2>订单支付</h2>
      <span class="order-no">订单号：{{ orderId }}</span>
    </div>

    <el-card v-if="settled" shadow="never" class="block success-block">
      <el-icon class="success-icon"><CircleCheckFilled /></el-icon>
      <div class="success-title" data-testid="pay-success">支付成功</div>
      <div class="success-desc">
        已完成支付 {{ formatPrice(amount) }}
        <span v-if="order?.payTime">（{{ formatDateTime(order.payTime) }}）</span>
      </div>
      <div class="success-actions">
        <el-button type="primary" @click="router.push(`/orders/${orderId}`)">查看订单</el-button>
        <el-button @click="router.push('/courses')">继续挑课</el-button>
      </div>
    </el-card>

    <el-card v-else shadow="never" class="block">
      <div class="amount-box">
        <div class="amount-label">应付金额</div>
        <div class="amount-value" data-testid="pay-amount">{{ formatPrice(amount) }}</div>
        <div class="amount-hint">
          <template v-if="remaining !== '已超时'">剩余支付时间：{{ remaining }}</template>
          <template v-else>订单已超时，将被自动关闭</template>
        </div>
      </div>

      <el-divider />

      <div class="pay-body">
        <div class="pay-left">
          <div class="pay-channel">
            <el-icon><Wallet /></el-icon>
            <span>模拟支付渠道（mockPay）</span>
            <el-tag type="info" size="small">本地开发用</el-tag>
          </div>
          <p class="pay-note">
            真实渠道会在这里展示二维码。本地环境用 Mock 渠道：
            点击下方按钮会**发起一次真实的支付回调**（带签名 → 验签 → MQ → 订单状态流转），
            而不是绕过链路直接改状态。
          </p>
          <div v-if="payUrl" class="pay-meta">
            <div>支付单号：<span data-testid="pay-order-no">{{ payUrl.payOrderNo }}</span></div>
            <div v-if="payUrl.reused" class="reused">（复用了未过期的支付单，没有重复建单）</div>
          </div>
          <div v-else-if="creating" class="pay-meta">正在创建支付单…</div>
          <div v-else class="pay-meta">支付单创建失败，可
            <el-button link type="primary" @click="createPay">重试</el-button>
          </div>
        </div>

        <div class="pay-right">
          <el-button
            type="primary"
            size="large"
            :loading="paying"
            :disabled="!payUrl"
            data-testid="pay-mock-submit"
            @click="handleMockPay"
          >
            模拟支付成功
          </el-button>
          <el-button :icon="Refresh" @click="handleRefresh">刷新订单状态</el-button>
          <a
            v-if="payUrl?.qrCodeUrl"
            class="cashier-link"
            :href="payUrl.qrCodeUrl"
            target="_blank"
            rel="noopener"
          >
            打开模拟收银台页面
          </a>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.pay-page {
  max-width: 800px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.order-no {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.block {
  margin-bottom: 16px;
}

.amount-box {
  text-align: center;
  padding: 12px 0;
}

.amount-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.amount-value {
  margin: 8px 0;
  font-size: 36px;
  font-weight: 700;
  color: var(--el-color-danger);
}

.amount-hint {
  font-size: 13px;
  color: var(--el-color-warning);
}

.pay-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.pay-left {
  flex: 1;
  min-width: 0;
}

.pay-channel {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 8px;
}

.pay-note {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

.pay-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.reused {
  color: var(--el-color-success);
}

.pay-right {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex-shrink: 0;
  width: 200px;
}

.cashier-link {
  font-size: 12px;
  text-align: center;
  color: var(--el-color-primary);
}

.success-block {
  text-align: center;
  padding: 24px 0;
}

.success-icon {
  font-size: 56px;
  color: var(--el-color-success);
}

.success-title {
  margin-top: 12px;
  font-size: 20px;
  font-weight: 700;
}

.success-desc {
  margin-top: 8px;
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.success-actions {
  margin-top: 20px;
  display: flex;
  justify-content: center;
  gap: 12px;
}

@media (max-width: 720px) {
  .pay-body {
    flex-direction: column;
  }

  .pay-right {
    width: 100%;
  }
}
</style>
