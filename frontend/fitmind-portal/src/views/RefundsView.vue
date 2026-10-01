<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { cancelRefund, getRefundDetail, pageRefunds } from '@/api/trade'
import type { RefundApplyItem, RefundDetail } from '@/types/trade'
import { RefundStatus } from '@/types/trade'
import { formatDateTime, formatPrice, pageTotal, refundStatusTagType } from '@/utils/format'

/**
 * 我的退款记录（M7-3）
 *
 * 退款是**异步**的：提交申请只是"我说要退"，后面还要经过审批 → 调渠道 → 结果回调。
 * 所以这个页面的价值是"让用户看得见进度"，而不是"点一下就完事"。
 *
 * M7-12 补上**退款详情**（`GET /ts/refund-apply/{id}`）：
 * 列表只够"扫一眼进度"，而用户点进来的真正诉求通常是
 * 「为什么被拒了」「退到哪个渠道了」「渠道单号是多少」—— 这三样只在详情里。
 */
const router = useRouter()

const list = ref<RefundApplyItem[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const loading = ref(false)

// ==================== M7-12：退款详情 ====================
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RefundDetail | null>(null)

/**
 * 查详情。
 *
 * ★ 有意**不**用列表里已有的字段"凑"一个详情页：
 *   列表项没有 `approveOpinion` / `refundOrderNo` / `failedReason` / `payOrderNo`，
 *   而这几样恰恰是详情页存在的理由。省一次请求换来一个残缺的详情，不划算。
 */
async function openDetail(item: RefundApplyItem) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getRefundDetail(item.id)
  } catch {
    // 失败时给一个"至少能看"的降级：用列表项垫底（字段少但不会是空白弹窗）
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

/** 详情里的字段：null 一律显示 —（后端未填就是 null，不要显示 "null"） */
function val(v: unknown): string {
  return v === null || v === undefined || v === '' ? '—' : String(v)
}

const isEmpty = computed(() => !loading.value && list.value.length === 0)

async function load() {
  loading.value = true
  try {
    const page = await pageRefunds({ pageNo: pageNo.value, pageSize: pageSize.value })
    list.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function handleCancel(item: RefundApplyItem) {
  try {
    await ElMessageBox.confirm('确定要撤销这条退款申请吗？', '提示', {
      type: 'warning',
      confirmButtonText: '撤销申请',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  // 后端两种入参都支持，这里用退款申请 id（更直接）
  await cancelRefund({ id: item.id })
  ElMessage.success('退款申请已撤销')
  await load()
}
</script>

<template>
  <div class="refunds-page">
    <div class="page-head">
      <h2>我的退款</h2>
      <span class="head-count">共 {{ total }} 条</span>
    </div>

    <el-empty v-if="isEmpty" description="还没有退款记录" data-testid="refunds-empty">
      <el-button @click="router.push('/orders')">查看我的订单</el-button>
    </el-empty>

    <div v-loading="loading" class="refund-list">
      <el-card
        v-for="item in list"
        :key="item.id"
        shadow="never"
        class="refund-card"
        data-testid="refund-card"
      >
        <div class="card-head">
          <div class="card-left">
            <span class="course-name">{{ item.courseName }}</span>
            <span class="order-link" @click="router.push(`/orders/${item.orderId}`)">
              订单 {{ item.orderId }}
            </span>
          </div>
          <el-tag :type="refundStatusTagType(item.status)" data-testid="refund-status">
            {{ item.statusDesc }}
          </el-tag>
        </div>

        <div class="card-body">
          <div class="field">
            <span class="label">退款金额</span>
            <span class="amount" data-testid="refund-amount">{{ formatPrice(item.refundAmount) }}</span>
          </div>
          <div class="field">
            <span class="label">退款原因</span>
            <span>{{ item.refundReason || '—' }}</span>
          </div>
          <div class="field">
            <span class="label">申请时间</span>
            <span>{{ formatDateTime(item.createTime) }}</span>
          </div>
          <div v-if="item.finishTime" class="field">
            <span class="label">完成时间</span>
            <span>{{ formatDateTime(item.finishTime) }}</span>
          </div>
        </div>

        <div class="card-actions">
          <!-- M7-12：详情入口（任何状态都能看 —— 被拒/失败时更需要看原因） -->
          <el-button size="small" data-testid="refund-detail-open" @click="openDetail(item)">
            查看详情
          </el-button>
          <el-button
            v-if="item.status === RefundStatus.UN_APPROVE"
            size="small"
            @click="handleCancel(item)"
          >
            撤销申请
          </el-button>
        </div>
      </el-card>
    </div>

    <el-pagination
      v-if="total > pageSize"
      v-model:current-page="pageNo"
      class="pager"
      layout="prev, pager, next"
      :page-size="pageSize"
      :total="total"
      @current-change="load"
    />

    <!-- ==================== M7-12：退款详情抽屉 ==================== -->
    <el-drawer
      v-model="detailVisible"
      title="退款详情"
      size="480px"
      data-testid="refund-detail-drawer"
    >
      <el-skeleton v-if="detailLoading" :rows="8" animated />

      <el-empty v-else-if="!detail" description="详情加载失败，请稍后重试" />

      <div v-else class="detail-body" data-testid="refund-detail">
        <div class="detail-head">
          <span class="detail-course" data-testid="detail-course-name">{{ detail.courseName }}</span>
          <el-tag :type="refundStatusTagType(detail.status)" data-testid="detail-status">
            {{ detail.statusDesc }}
          </el-tag>
        </div>

        <el-alert
          v-if="detail.message"
          class="detail-message"
          type="info"
          :title="detail.message"
          :closable="false"
          show-icon
        />

        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="退款金额">
            <span class="detail-amount" data-testid="detail-refund-amount">
              {{ formatPrice(detail.refundAmount) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="课程原价">{{ formatPrice(detail.price) }}</el-descriptions-item>
          <el-descriptions-item label="分摊优惠">{{ formatPrice(detail.discountAmount) }}</el-descriptions-item>
          <el-descriptions-item label="实付金额">{{ formatPrice(detail.realPayAmount) }}</el-descriptions-item>
          <el-descriptions-item label="退款原因">{{ val(detail.refundReason) }}</el-descriptions-item>
          <el-descriptions-item label="问题说明">{{ val(detail.questionDesc) }}</el-descriptions-item>
          <el-descriptions-item label="申请时间">{{ formatDateTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="审批意见">{{ val(detail.approveOpinion) }}</el-descriptions-item>
          <el-descriptions-item label="审批时间">{{ formatDateTime(detail.approveTime) }}</el-descriptions-item>
          <el-descriptions-item label="退款渠道">{{ val(detail.refundChannel) }}</el-descriptions-item>
          <el-descriptions-item label="渠道退款单号">{{ val(detail.refundOrderNo) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.failedReason" label="失败原因">
            <span class="detail-failed">{{ detail.failedReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatDateTime(detail.finishTime) }}</el-descriptions-item>
          <el-descriptions-item label="原支付单号">{{ val(detail.payOrderNo) }}</el-descriptions-item>
          <el-descriptions-item label="原支付渠道">{{ val(detail.payChannel) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert class="detail-tip" type="warning" :closable="false">
          <template #title>
            课程名与含税金额都是<strong>下单时的快照</strong>，不是课程的当前值 ——
            退款详情是交易凭证的一部分，必须显示"当时买的是什么、花了多少"。
          </template>
        </el-alert>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.refunds-page {
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

.refund-list {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.card-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.course-name {
  font-size: 15px;
  font-weight: 600;
}

.order-link {
  font-size: 12px;
  font-family: var(--fm-mono, monospace);
  color: var(--el-color-primary);
  cursor: pointer;
}

.card-body {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 8px 20px;
}

.field {
  display: flex;
  gap: 8px;
  font-size: 13px;
}

.label {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.amount {
  font-weight: 700;
  color: var(--el-color-danger);
}

.card-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.pager {
  margin-top: 20px;
  justify-content: center;
}

/* ---- M7-12：详情抽屉 ---- */
.detail-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.detail-course {
  font-size: 16px;
  font-weight: 600;
}

.detail-amount {
  font-weight: 700;
  color: var(--el-color-danger);
}

.detail-failed {
  color: var(--el-color-danger);
}

.detail-tip {
  margin-top: 4px;
}
</style>
