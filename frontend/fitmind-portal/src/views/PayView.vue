<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { CircleCheckFilled, Refresh, Wallet, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { applyPay, getOrder, getPayOrderStatus, mockPay } from '@/api/trade'
import type { Order, PayOrder, PayUrl } from '@/types/trade'
import { OrderStatus, PayStatus } from '@/types/trade'
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
 *
 * ★★ 页面有**三种状态，不是两种**（M6-8 #2）：
 *   ① 订单存在且待支付 → 收银台
 *   ② 订单存在且已支付 → 成功页
 *   ③ **订单查不到 / 加载失败 → 错误空态**
 *
 *   ★ 曾经的写法是"不是①就是②"（`canPay` 为 false 就直接 `settled = true`），
 *   而 `loadOrder()` 的 `catch {}` 会让 `order` 停在 `null` ——
 *   于是**打开一个不存在的订单号，页面会显示"支付成功 · 免费"**：
 *   失败被当成了成功，而且金额还被 `?? 0` 兜底成了 0 元。
 *
 *   ★ 这不是"少写了一个分支"，而是**把两件不同的事压成了同一个 boolean**：
 *   "不欠钱了"和"我查不到这笔单"是两回事，后者必须让用户看见。
 *   所以这里刻意用**独立的 `loadError`**，而不是给 `canPay` 加分支 ——
 *   独立状态无法被"将来某次改动"顺手折叠掉。
 */
const route = useRoute()
const router = useRouter()

const orderId = computed(() => String(route.params.orderId ?? ''))
const order = ref<Order | null>(null)
const payUrl = ref<PayUrl | null>(null)

const loading = ref(true)
const creating = ref(false)
const paying = ref(false)
/** 支付成功（订单存在且已不是待支付） */
const settled = ref(false)
/** ★ 订单查不到 / 加载失败 —— 与 settled **互斥**，绝不允许同时为真 */
const loadError = ref(false)

const remaining = ref('—')
let timer: number | undefined

/**
 * ★ 不兜底成 0。
 *
 * `formatPrice(undefined)` 会渲染成「—」，而 `formatPrice(0)` 渲染成「免费」——
 * 在支付页上，**"读不到金额"绝不能被显示成"免费"**。
 */
const amount = computed(() => order.value?.realAmount)
const canPay = computed(() => order.value?.status === OrderStatus.NO_PAY)

onMounted(async () => {
  if (!orderId.value) {
    ElMessage.error('缺少订单号')
    router.replace('/orders')
    return
  }
  const loaded = await loadOrder()
  tick()
  timer = window.setInterval(tick, 10000)
  loading.value = false

  // ★ 三态判定：只有"订单确实存在且已支付"才是成功；查不到一律走错误空态
  if (canPay.value) {
    // 只有待支付的订单才需要去申请支付单
    await createPay()
  } else if (loaded && order.value) {
    // 已支付的（比如用户刷新了页面）直接展示成功
    settled.value = true
  } else {
    loadError.value = true
  }
})

onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

function tick() {
  remaining.value = formatRemaining(order.value?.payOutTime)
}

/**
 * 拉订单。
 *
 * ★ 返回「有没有拿到订单」而不是 void：调用方要拿它判断三态。
 * 以前这里 `catch {}` 之后什么都不说，`order` 停在 null，
 * 和"订单真的不存在"长得一模一样 —— 这正是 #2 假成功的入口。
 */
async function loadOrder(): Promise<boolean> {
  try {
    // ★ silent：本页自己用"错误空态"表达"订单不存在"，不需要再来一个全局提示
    order.value = await getOrder(orderId.value, { silent: true })
    return order.value != null
  } catch {
    order.value = null
    return false
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
    // M7-12：拿到支付单号后顺手查一次支付单状态（渠道侧视角）
    await loadPayOrderStatus()
  } catch {
    /* 已提示 */
  } finally {
    creating.value = false
  }
}

// ==================== M7-12：支付单状态（渠道侧视角）====================

/**
 * 支付单状态 —— `GET /ps/pay-orders/{payOrderNo}`。
 *
 * ★ 它和"订单状态"是**两个不同的事实**，放在一起才看得出问题：
 *   · 订单状态（fm-trade）：由 MQ 异步驱动，可能**滞后**于渠道
 *   · 支付单状态（fm-pay）：渠道侧的原始事实，`1-未支付 / 2-已支付 / 3-已关闭`
 *
 *   所以当出现"我明明付款了，订单还是待支付"时，这块能立刻区分出
 *   是「渠道还没收到钱」还是「钱到了、MQ 还在路上」—— 这正是它的诊断价值。
 *
 * ⚠️ 两个实现细节（读后端源码确认）：
 *   ① 返回**裸 PO**，`status` 用的是 `PayConstants.PAY_STATUS_*` 那套编码
 *   ② **查不到、或不是自己的支付单 → 都返回 `data: null`**，不报错
 *      → 所以必须判 null，不能靠 catch 判断"不存在"
 */
const payOrder = ref<PayOrder | null>(null)

async function loadPayOrderStatus() {
  const no = payUrl.value?.payOrderNo
  if (!no) {
    payOrder.value = null
    return
  }
  try {
    payOrder.value = await getPayOrderStatus(no)
  } catch {
    payOrder.value = null
  }
}

function payStatusText(status: number | undefined): string {
  if (status === PayStatus.PAYED) return '渠道已支付'
  if (status === PayStatus.NO_PAY) return '渠道未支付'
  if (status === PayStatus.CLOSED) return '已关闭'
  return '—'
}

function payStatusType(status: number | undefined): 'success' | 'warning' | 'info' {
  if (status === PayStatus.PAYED) return 'success'
  if (status === PayStatus.NO_PAY) return 'warning'
  return 'info'
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
    // 顺手刷新渠道侧状态：它比订单状态更"先知道"，能看到 MQ 在路上的那一段
    await loadPayOrderStatus()
    if (order.value && order.value.status !== OrderStatus.NO_PAY) return true
  }
  return false
}

/**
 * 手动刷新订单状态。
 *
 * ★ 与 `onMounted` 走同一套三态判定 —— **不能**再写成
 * "只要不是待支付就 `settled = true`"：订单被删/查不到时那会直接编出一个"支付成功"。
 */
async function handleRefresh() {
  const loaded = await loadOrder()
  await loadPayOrderStatus()
  if (!loaded || !order.value) {
    loadError.value = true
    settled.value = false
    return
  }
  loadError.value = false
  settled.value = !canPay.value
}
</script>

<template>
  <div v-loading="loading" class="pay-page">
    <div class="page-head">
      <h2>订单支付</h2>
      <span class="order-no">订单号：{{ orderId }}</span>
    </div>

    <!-- ★ 错误空态（M6-8 #2）：订单查不到时**绝不能**落到"支付成功"那一支 -->
    <el-card v-if="loadError" shadow="never" class="block error-block" data-testid="pay-error">
      <el-icon class="error-icon"><WarningFilled /></el-icon>
      <div class="error-title" data-testid="pay-error-title">订单不存在或已关闭</div>
      <div class="error-desc">
        找不到订单号 <span class="error-order-no">{{ orderId }}</span> 的订单。
        可能是订单号有误、订单已被取消，或者这笔订单不属于当前账号。
      </div>
      <div class="error-actions">
        <el-button type="primary" @click="router.push('/orders')">返回我的订单</el-button>
        <el-button @click="router.push('/courses')">去选购课程</el-button>
      </div>
    </el-card>

    <el-card v-else-if="settled" shadow="never" class="block success-block">
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

    <!--
      ★ 收银台要等**订单确实拿到了**才渲染（`!loading`）。
        否则在"接口还没回来"的那一小段时间里，三态都是 false → 会落到这个 v-else，
        于是**用户会先看到一眼收银台（金额显示"—"），再跳成错误空态**。
        金额没有兜底成 0 只是让它不那么误导，但闪烁本身就该消掉 ——
        **"还没确认订单存在"和"订单存在且待支付"必须长得不一样。**
    -->
    <el-card v-else-if="!loading" shadow="never" class="block">
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

          <!-- M7-12：支付单状态（渠道侧）—— 与订单状态并排看，能一眼区分"钱没到"和"MQ 在路上" -->
          <div v-if="payOrder" class="pay-order-status" data-testid="pay-order-status">
            <span class="pos-label">渠道侧支付单</span>
            <el-tag size="small" :type="payStatusType(payOrder.status)" data-testid="pay-order-status-tag">
              {{ payStatusText(payOrder.status) }}
            </el-tag>
            <span class="pos-item">金额 {{ formatPrice(payOrder.amount) }}</span>
            <span class="pos-item">渠道 {{ payOrder.payChannelCode || '—' }}</span>
            <span v-if="payOrder.paySuccessTime" class="pos-item">
              支付成功于 {{ formatDateTime(payOrder.paySuccessTime) }}
            </span>
            <span v-if="payOrder.expireTime" class="pos-item">
              过期 {{ formatDateTime(payOrder.expireTime) }}
            </span>
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

/* M7-12：渠道侧支付单状态 */
.pay-order-status {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
}

.pos-label {
  color: var(--el-text-color-secondary);
}

.pos-item {
  color: var(--el-text-color-regular);
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

/* M6-8 #2：订单查不到时的错误空态 —— 用 warning 色，与"支付成功"的绿色明确区分 */
.error-block {
  text-align: center;
  padding: 24px 0;
}

.error-icon {
  font-size: 56px;
  color: var(--el-color-warning);
}

.error-title {
  margin-top: 12px;
  font-size: 20px;
  font-weight: 700;
}

.error-desc {
  margin: 8px auto 0;
  max-width: 460px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

.error-order-no {
  font-family: monospace;
  word-break: break-all;
}

.error-actions {
  margin-top: 20px;
  display: flex;
  justify-content: center;
  gap: 12px;
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
