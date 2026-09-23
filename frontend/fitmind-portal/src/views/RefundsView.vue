<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { cancelRefund, pageRefunds } from '@/api/trade'
import type { RefundApplyItem } from '@/types/trade'
import { RefundStatus } from '@/types/trade'
import { formatDateTime, formatPrice, pageTotal, refundStatusTagType } from '@/utils/format'

/**
 * 我的退款记录（M7-3）
 *
 * 退款是**异步**的：提交申请只是"我说要退"，后面还要经过审批 → 调渠道 → 结果回调。
 * 所以这个页面的价值是"让用户看得见进度"，而不是"点一下就完事"。
 */
const router = useRouter()

const list = ref<RefundApplyItem[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const loading = ref(false)

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

        <div v-if="item.status === RefundStatus.UN_APPROVE" class="card-actions">
          <el-button size="small" @click="handleCancel(item)">撤销申请</el-button>
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
}

.pager {
  margin-top: 20px;
  justify-content: center;
}
</style>
