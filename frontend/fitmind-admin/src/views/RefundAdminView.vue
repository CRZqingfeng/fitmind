<script setup lang="ts">
import { Check, Close, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { approveRefund, getNextToApprove, getRefundDetail, getRefundPage } from '@/api/refund'
import type { RefundApplyPageVO, RefundApplyVO } from '@/types/admin'
import { REFUND_STATUS } from '@/types/admin'
import { formatDateTime, formatPrice, formatYuan, pageTotal, refundStatusTagType } from '@/utils/format'

/**
 * 退款审批
 *
 * ★★ 本页最关键的一点：**"同意"不等于"退款成功"**。
 *
 *   同意之后的链路是：调渠道下单 → **渠道回调** → 改退款单 → 发 MQ
 *   → 撤课表 / 改订单明细。全是异步的。
 *   所以审批之后必须**轮询到终态**（5 退款成功 / 6 退款失败），
 *   否则页面上永远是"同意退款"，而用户以为自己已经收到钱了。
 *
 * ★ 另一个口径问题：`refundAmount` 与 `realPayAmount` 在详情里都返回。
 *   退款金额 = 该课程**实付**（不是原价），优惠按比例分摊过 ——
 *   详情里把这两个数并排展示，是为了让审批人一眼看清"退多少、为什么是这个数"。
 */
const loading = ref(false)
const rows = ref<RefundApplyPageVO[]>([])
const total = ref(0)
const acting = ref<string | null>(null)

const detail = ref<RefundApplyVO | null>(null)
const detailVisible = ref(false)
const detailLoading = ref(false)
/** 审批意见（同意/拒绝共用，弹窗里填） */
const opinion = ref('')

const query = reactive({
  pageNo: 1,
  pageSize: 10,
  refundStatus: REFUND_STATUS.PENDING as number | undefined,
  orderId: '',
})

async function load() {
  loading.value = true
  try {
    const page = await getRefundPage({ ...query, orderId: query.orderId || undefined })
    rows.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

async function openDetail(id: string) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getRefundDetail(id)
  } catch {
    /* 已提示 */
  } finally {
    detailLoading.value = false
  }
}

/**
 * 轮询退款状态直到终态
 *
 * ★ 为什么要轮询而不是"刷新一次看看"：链路有 4 段异步，
 *   刷新一次大概率还在中间态，用户会以为审批没生效然后**重复点同意**。
 */
async function waitForFinal(id: string, timeoutMs = 30000): Promise<RefundApplyVO | null> {
  const deadline = Date.now() + timeoutMs
  let last: RefundApplyVO | null = null
  while (Date.now() < deadline) {
    try {
      last = await getRefundDetail(id)
    } catch {
      /* 单次失败继续试 */
    }
    if (last && (last.status === REFUND_STATUS.SUCCESS || last.status === REFUND_STATUS.FAILED)) {
      return last
    }
    await new Promise((r) => setTimeout(r, 1200))
  }
  return last
}

async function handleApprove(row: RefundApplyPageVO | RefundApplyVO, approveType: number) {
  const isAgree = approveType === 1
  acting.value = row.id
  try {
    const { value } = await ElMessageBox.prompt(
      isAgree
        ? `确认**同意**退款：${row.courseName ?? ''}\n退款金额 ${formatYuan(row.refundAmount)}\n\n同意后会调用支付渠道发起退款（异步，随后会有回调）。`
        : `确认**拒绝**退款：${row.courseName ?? ''}\n\n请填写拒绝理由（会展示给用户）：`,
      isAgree ? '同意退款' : '拒绝退款',
      {
        type: isAgree ? 'warning' : 'info',
        confirmButtonText: isAgree ? '确认同意' : '确认拒绝',
        cancelButtonText: '取消',
        inputPlaceholder: isAgree ? '审批意见（选填）' : '拒绝理由（必填）',
        inputValidator: isAgree ? undefined : (v: string) => (v?.trim() ? true : '拒绝必须给出理由'),
      },
    )
    opinion.value = value ?? ''

    await approveRefund({ id: row.id, approveType, approveOpinion: opinion.value || undefined })

    if (!isAgree) {
      ElMessage.success('已拒绝')
      await load()
      return
    }

    // ★ 同意：这里必须等终态，否则验收和用户都会误以为"点完就到账了"
    const msg = ElMessage({ message: '已同意，正在等待渠道退款结果…', duration: 0, type: 'info' })
    const final = await waitForFinal(row.id)
    msg.close()
    if (final?.status === REFUND_STATUS.SUCCESS) {
      ElMessage.success(`退款成功（${final.refundOrderNo ?? '渠道单号缺省'}）`)
    } else if (final?.status === REFUND_STATUS.FAILED) {
      ElMessage.error(`退款失败：${final.failedReason ?? '渠道未返回原因'}`)
    } else {
      ElMessage.warning(`已同意，但 ${30} 秒内未拿到终态（当前：${final?.statusDesc ?? '未知'}），可稍后刷新`)
    }
    await load()
    if (detailVisible.value && detail.value?.id === row.id) await openDetail(row.id)
  } catch {
    /* 取消 / 已提示 */
  } finally {
    acting.value = null
  }
}

/** 逐条审批：直接拿下一个待审批的，审批人不用在列表里找 */
async function handleNext() {
  try {
    const next = await getNextToApprove()
    if (!next) {
      ElMessage.success('没有待审批的退款申请了')
      return
    }
    await openDetail(next.id)
  } catch {
    /* 已提示 */
  }
}

onMounted(load)
</script>

<template>
  <div class="fm-page refund-admin">
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-select
          v-model="query.refundStatus"
          placeholder="退款状态"
          clearable
          style="width: 150px"
          data-testid="refund-filter-status"
        >
          <el-option label="待审批" :value="REFUND_STATUS.PENDING" />
          <el-option label="已取消" :value="REFUND_STATUS.CANCELLED" />
          <el-option label="同意退款" :value="REFUND_STATUS.AGREED" />
          <el-option label="已拒绝" :value="REFUND_STATUS.REJECTED" />
          <el-option label="退款成功" :value="REFUND_STATUS.SUCCESS" />
          <el-option label="退款失败" :value="REFUND_STATUS.FAILED" />
        </el-select>

        <el-input
          v-model="query.orderId"
          placeholder="按订单 id 查"
          clearable
          style="width: 170px"
          data-testid="refund-filter-order"
        />

        <el-button type="primary" :icon="Search" @click="query.pageNo = 1; load()">查询</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>

        <div class="right-group">
          <el-button type="primary" plain data-testid="refund-next" @click="handleNext">逐条审批</el-button>
          <span class="total-hint" data-testid="refund-total">共 {{ total }} 条</span>
        </div>
      </div>

      <div class="hint-line">
        ★★ 「同意」之后是**异步链路**：调渠道 → 渠道回调 → 改单 → 发 MQ → 撤课表。
        所以点完同意页面会**继续等终态**（退款成功 / 失败），
        而不是立刻显示"已同意"就结束 —— 那样用户会以为钱已到账。
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        row-key="id"
        data-testid="refund-table"
        empty-text="没有符合条件的退款申请"
      >
        <el-table-column prop="id" label="申请 id" width="100" />

        <el-table-column label="课程" min-width="200">
          <template #default="{ row }">
            <div class="course-name" data-testid="refund-course">{{ row.courseName ?? '—' }}</div>
            <div class="sub">订单 {{ row.orderId }} / 明细 {{ row.orderDetailId }}</div>
          </template>
        </el-table-column>

        <el-table-column label="退款金额" width="120">
          <template #default="{ row }">
            <span class="amount" data-testid="refund-amount">{{ formatYuan(row.refundAmount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="原因" min-width="160">
          <template #default="{ row }">
            <span class="ellipsis">{{ row.refundReason ?? '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="refundStatusTagType(row.status)" size="small" data-testid="refund-status">
              {{ row.statusDesc ?? '—' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="申请时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-testid="refund-detail" @click="openDetail((row as RefundApplyPageVO).id)">详情</el-button>
            <template v-if="row.status === REFUND_STATUS.PENDING">
              <el-button
                link
                type="success"
                :icon="Check"
                :loading="acting === row.id"
                data-testid="refund-approve"
                @click="handleApprove(row as RefundApplyPageVO, 1)"
              >
                同意
              </el-button>
              <el-button
                link
                type="danger"
                :icon="Close"
                :loading="acting === row.id"
                data-testid="refund-reject"
                @click="handleApprove(row as RefundApplyPageVO, 2)"
              >
                拒绝
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="total"
        :page-sizes="[10, 20, 50]"
        data-testid="refund-pager"
        @current-change="load"
        @size-change="query.pageNo = 1; load()"
      />
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="退款详情" size="520px" data-testid="refund-drawer">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <div class="detail-head">
            <span class="detail-course">{{ detail.courseName ?? '—' }}</span>
            <el-tag :type="refundStatusTagType(detail.status)" size="small">{{ detail.statusDesc ?? '—' }}</el-tag>
          </div>
          <div v-if="detail.message" class="detail-msg">{{ detail.message }}</div>

          <el-descriptions :column="1" border size="small" class="detail-desc">
            <!-- ★ 金额三连：原价 / 分摊优惠 / 实付 = 退款金额 -->
            <el-descriptions-item label="课程原价">{{ formatPrice(detail.price) }}</el-descriptions-item>
            <el-descriptions-item label="分摊优惠">-{{ formatYuan(detail.discountAmount) }}</el-descriptions-item>
            <el-descriptions-item label="实付（= 退款金额）">
              <b data-testid="detail-real-pay">{{ formatYuan(detail.realPayAmount) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="退款原因">{{ detail.refundReason ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="问题说明">{{ detail.questionDesc ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="申请时间">{{ formatDateTime(detail.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="审批意见">{{ detail.approveOpinion ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="审批时间">{{ formatDateTime(detail.approveTime) }}</el-descriptions-item>
            <el-descriptions-item label="原支付单">{{ detail.payOrderNo ?? '—' }}（{{ detail.payChannel ?? '—' }}）</el-descriptions-item>
            <el-descriptions-item label="支付时间">{{ formatDateTime(detail.paySuccessTime) }}</el-descriptions-item>
            <el-descriptions-item label="退款单号">{{ detail.refundOrderNo ?? '—' }}（{{ detail.refundChannel ?? '—' }}）</el-descriptions-item>
            <el-descriptions-item label="失败原因">{{ detail.failedReason ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="完成时间">{{ formatDateTime(detail.finishTime) }}</el-descriptions-item>
          </el-descriptions>

          <div v-if="detail.status === REFUND_STATUS.PENDING" class="detail-actions">
            <el-button type="success" data-testid="drawer-approve" @click="handleApprove(detail, 1)">
              同意退款
            </el-button>
            <el-button type="danger" plain data-testid="drawer-reject" @click="handleApprove(detail, 2)">
              拒绝
            </el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.refund-admin {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.filter-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.right-group {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.total-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hint-line {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.8;
}

.course-name {
  font-weight: 600;
}

.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.amount {
  color: var(--el-color-danger);
  font-weight: 700;
}

.ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.detail-body {
  min-height: 200px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.detail-course {
  font-size: 15px;
  font-weight: 600;
}

.detail-msg {
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.7;
}

.detail-desc {
  margin-top: 12px;
}

.detail-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
</style>
