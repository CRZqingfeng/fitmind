<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { applyRefund, cancelOrder, getOrder } from '@/api/trade'
import type { Order, OrderDetail } from '@/types/trade'
import { OrderStatus } from '@/types/trade'
import { formatDateTime, formatPrice, orderStatusTagType } from '@/utils/format'

/**
 * 订单详情（M7-3）
 *
 * ★ 退款是**按课程**发起的（用户想的是"退这门课"，不是"退订单里的第 2 行"），
 *   所以接口收的是 `courseId` 而不是明细 id —— 明细 id 用户根本看不到，
 *   让前端传一个拿不到的值，接口设计上就走不通。
 *   详见 `RefundFormDTO` 与 `applyRefund` 的注释。
 */
const route = useRoute()
const router = useRouter()

/** 明细状态（对应后端 `TradeConstants.DETAIL_*`） */
const DETAIL = {
  NO_PAY: 1,
  PAYED: 2,
  CLOSED: 3,
  REFUNDING: 4,
  REFUNDED: 5,
} as const

const orderId = computed(() => String(route.params.id ?? ''))
const order = ref<Order | null>(null)
const loading = ref(true)

const refundVisible = ref(false)
const refundTarget = ref<OrderDetail | null>(null)
const refundSubmitting = ref(false)
const refundForm = reactive({ refundReason: '', questionDesc: '' })

/** 退款原因给几个常见选项，省得用户面对空白框发愣 */
const REASON_OPTIONS = [
  '训练计划与我的目标不符',
  '课程内容与描述不一致',
  '动作难度不适合我',
  '器械条件不满足',
  '误购 / 重复购买',
  '其他原因',
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    order.value = await getOrder(orderId.value)
  } catch {
    order.value = null
  } finally {
    loading.value = false
  }
}

function canRefund(detail: OrderDetail) {
  // 只有"已支付且未退过"的明细能申请退款
  return detail.status === DETAIL.PAYED && order.value?.status !== OrderStatus.CLOSED
}

function openRefund(detail: OrderDetail) {
  refundTarget.value = detail
  refundForm.refundReason = ''
  refundForm.questionDesc = ''
  refundVisible.value = true
}

async function submitRefund() {
  if (!refundTarget.value) return
  if (!refundForm.refundReason.trim()) {
    ElMessage.warning('请选择或填写退款原因')
    return
  }
  refundSubmitting.value = true
  try {
    await applyRefund({
      courseId: refundTarget.value.courseId,
      refundReason: refundForm.refundReason.trim(),
      questionDesc: refundForm.questionDesc.trim() || undefined,
    })
    refundVisible.value = false
    ElMessage.success('退款申请已提交，等待审批')
    // ★ 重新拉订单：退款后的状态由服务端给（明细会变成"退款中"），
    //   不在本地改状态 —— 否则"页面上退款中、后台其实没申请成功"这种事无法发现
    await load()
  } catch {
    /* request.ts 已提示（比如"已有进行中的退款申请""超出可退次数"） */
  } finally {
    refundSubmitting.value = false
  }
}

async function handleCancelOrder() {
  if (!order.value) return
  try {
    await ElMessageBox.confirm('确定要取消这笔订单吗？', '提示', {
      type: 'warning',
      confirmButtonText: '取消订单',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  await cancelOrder(order.value.id)
  ElMessage.success('订单已取消')
  await load()
}

function detailStatusText(status: number | null | undefined): string {
  switch (status) {
    case DETAIL.NO_PAY:
      return '待支付'
    case DETAIL.PAYED:
      return '已支付'
    case DETAIL.CLOSED:
      return '已关闭'
    case DETAIL.REFUNDING:
      return '退款中'
    case DETAIL.REFUNDED:
      return '已退款'
    default:
      return '—'
  }
}
</script>

<template>
  <div v-loading="loading" class="order-detail-page">
    <div class="page-head">
      <el-button link :icon="undefined" @click="router.push('/orders')">← 返回订单列表</el-button>
    </div>

    <el-empty v-if="!loading && !order" description="订单不存在或已被删除" />

    <template v-if="order">
      <el-card shadow="never" class="block">
        <div class="head-row">
          <div>
            <div class="order-id" data-testid="detail-order-id">{{ order.id }}</div>
            <div class="order-sub">下单时间 {{ formatDateTime(order.createTime) }}</div>
          </div>
          <el-tag :type="orderStatusTagType(order.status)" size="large" data-testid="detail-order-status">
            {{ order.statusDesc }}
          </el-tag>
        </div>

        <el-descriptions :column="2" class="meta">
          <el-descriptions-item v-if="order.payTime" label="支付时间">
            {{ formatDateTime(order.payTime) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.payOutTime && order.canPay" label="支付截止">
            {{ formatDateTime(order.payOutTime) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.closeTime" label="关闭时间">
            {{ formatDateTime(order.closeTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="order.canPay || order.canCancel" class="head-actions">
          <el-button v-if="order.canPay" type="primary" @click="router.push(`/pay/${order.id}`)">
            去支付
          </el-button>
          <el-button v-if="order.canCancel" @click="handleCancelOrder">取消订单</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          <span class="block-title">课程明细</span>
        </template>

        <div
          v-for="detail in order.details ?? []"
          :key="detail.id"
          class="detail-row"
          data-testid="detail-course-row"
        >
          <div class="detail-main">
            <div class="detail-name">{{ detail.courseName }}</div>
            <div class="detail-sub">
              <span>有效期：{{ detail.validDuration ? `${detail.validDuration} 天` : '永久' }}</span>
              <span v-if="detail.courseExpireTime">
                · 到期：{{ formatDateTime(detail.courseExpireTime) }}
              </span>
              <el-tag
                v-if="detail.status !== DETAIL.PAYED"
                size="small"
                :type="detail.status === DETAIL.REFUNDING ? 'warning' : 'info'"
              >
                {{ detailStatusText(detail.status) }}
              </el-tag>
            </div>
          </div>

          <div class="detail-price">
            <div class="price-real">{{ formatPrice(detail.realPayAmount ?? detail.price) }}</div>
            <div v-if="(detail.discountAmount ?? 0) > 0" class="price-origin">
              {{ formatPrice(detail.price) }}
            </div>
          </div>

          <div class="detail-actions">
            <el-button
              v-if="canRefund(detail)"
              size="small"
              data-testid="detail-refund-btn"
              @click="openRefund(detail)"
            >
              申请退款
            </el-button>
            <span v-else-if="detail.status === DETAIL.REFUNDING" class="refund-hint">
              退款处理中
            </span>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <div class="amount-line">
          <span>课程合计</span>
          <span>{{ formatPrice(order.totalAmount) }}</span>
        </div>
        <div class="amount-line">
          <span>优惠</span>
          <span class="amount-discount">
            {{ (order.discountAmount ?? 0) > 0 ? `-${formatPrice(order.discountAmount)}` : formatPrice(0) }}
          </span>
        </div>
        <el-divider />
        <div class="amount-line amount-total">
          <span>实付金额</span>
          <span data-testid="detail-real-amount">{{ formatPrice(order.realAmount) }}</span>
        </div>
      </el-card>
    </template>

    <el-dialog v-model="refundVisible" title="申请退款" width="520px">
      <div v-if="refundTarget" class="refund-dialog">
        <div class="refund-course">课程：{{ refundTarget.courseName }}</div>
        <div class="refund-amount">
          可退金额：{{ formatPrice(refundTarget.realPayAmount ?? refundTarget.price) }}
          <span class="refund-note">（最终以服务端计算为准）</span>
        </div>

        <el-form label-position="top">
          <el-form-item label="退款原因" required>
            <el-select
              v-model="refundForm.refundReason"
              placeholder="请选择退款原因"
              style="width: 100%"
              data-testid="refund-reason"
            >
              <el-option v-for="r in REASON_OPTIONS" :key="r" :label="r" :value="r" />
            </el-select>
          </el-form-item>
          <el-form-item label="补充说明（选填）">
            <el-input
              v-model="refundForm.questionDesc"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="可以补充一下具体情况，便于审批"
            />
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="refundVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="refundSubmitting"
          data-testid="refund-submit"
          @click="submitRefund"
        >
          提交申请
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.order-detail-page {
  max-width: 880px;
  margin: 0 auto;
}

.page-head {
  margin-bottom: 12px;
}

.block {
  margin-bottom: 16px;
}

.head-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.order-id {
  font-size: 15px;
  font-weight: 700;
  font-family: var(--fm-mono, monospace);
}

.order-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.meta {
  margin-top: 12px;
}

.head-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.block-title {
  font-weight: 600;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.detail-row:last-child {
  border-bottom: none;
}

.detail-main {
  flex: 1;
  min-width: 0;
}

.detail-name {
  font-size: 15px;
  font-weight: 600;
}

.detail-sub {
  margin-top: 5px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-wrap: wrap;
}

.detail-price {
  text-align: right;
  flex-shrink: 0;
}

.price-real {
  font-size: 16px;
  font-weight: 700;
  color: var(--el-color-danger);
}

.price-origin {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

.detail-actions {
  flex-shrink: 0;
  width: 92px;
  text-align: right;
}

.refund-hint {
  font-size: 12px;
  color: var(--el-color-warning);
}

.amount-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 14px;
}

.amount-discount {
  color: var(--el-color-success);
}

.amount-total {
  font-size: 16px;
  font-weight: 700;
}

.amount-total span:last-child {
  font-size: 24px;
  color: var(--el-color-danger);
}

.refund-course {
  font-size: 14px;
  font-weight: 600;
}

.refund-amount {
  margin: 8px 0 16px;
  font-size: 14px;
  color: var(--el-color-danger);
  font-weight: 700;
}

.refund-note {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
</style>
