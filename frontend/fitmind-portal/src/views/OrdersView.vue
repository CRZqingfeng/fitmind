<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { cancelOrder, pageOrders } from '@/api/trade'
import type { Order } from '@/types/trade'
import { OrderStatus } from '@/types/trade'
import { formatDateTime, formatPrice, orderStatusTagType, pageTotal } from '@/utils/format'

/**
 * 我的订单（M7-3）
 *
 * ★ 状态 tab 的取值直接抄后端 `OrderStatus`（注意**没有 4**）：
 *   1 待支付 / 2 已支付 / 3 已关闭 / 5 已报名 / 6 退款中 / 7 已退款
 *   顺序按"用户关心程度"排，而不是按数字大小 —— 没人会去找"状态 3"，
 *   但一定会去找"我那个待支付的单子"。
 */
const router = useRouter()

const TAB_ALL = -1
const tabs = [
  { label: '全部', value: TAB_ALL },
  { label: '待支付', value: OrderStatus.NO_PAY },
  { label: '已支付', value: OrderStatus.PAYED },
  { label: '已报名', value: OrderStatus.ENROLLED },
  { label: '退款中', value: OrderStatus.REFUNDED },
  { label: '已退款', value: OrderStatus.REFUND_SUCCESS },
  { label: '已关闭', value: OrderStatus.CLOSED },
]

const activeTab = ref<number>(TAB_ALL)
const list = ref<Order[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(5)
const loading = ref(false)

const isEmpty = computed(() => !loading.value && list.value.length === 0)

async function load() {
  loading.value = true
  try {
    const page = await pageOrders({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      status: activeTab.value === TAB_ALL ? undefined : activeTab.value,
    })
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

function handleTabChange() {
  pageNo.value = 1
  load()
}

async function handleCancel(order: Order) {
  try {
    await ElMessageBox.confirm('确定要取消这笔订单吗？', '提示', {
      type: 'warning',
      confirmButtonText: '取消订单',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  await cancelOrder(order.id)
  ElMessage.success('订单已取消')
  await load()
}
</script>

<template>
  <div class="orders-page">
    <div class="page-head">
      <h2>我的订单</h2>
      <span class="head-count">共 {{ total }} 笔</span>
    </div>

    <el-tabs v-model="activeTab" class="status-tabs" @tab-change="handleTabChange">
      <el-tab-pane
        v-for="tab in tabs"
        :key="tab.label"
        :label="tab.label"
        :name="tab.value"
      />
    </el-tabs>

    <el-empty v-if="isEmpty" description="这里还没有订单" data-testid="orders-empty">
      <el-button type="primary" @click="router.push('/courses')">去挑课程</el-button>
    </el-empty>

    <div v-loading="loading" class="order-list">
      <el-card
        v-for="order in list"
        :key="order.id"
        shadow="never"
        class="order-card"
        data-testid="order-card"
      >
        <div class="order-head">
          <div class="order-head-left">
            <span class="order-id" data-testid="order-id">{{ order.id }}</span>
            <span class="order-time">{{ formatDateTime(order.createTime) }}</span>
          </div>
          <el-tag :type="orderStatusTagType(order.status)" data-testid="order-status">
            {{ order.statusDesc }}
          </el-tag>
        </div>

        <div
          v-for="detail in order.details ?? []"
          :key="detail.id"
          class="order-item"
          @click="router.push(`/orders/${order.id}`)"
        >
          <div class="item-name">{{ detail.courseName }}</div>
          <div class="item-price">{{ formatPrice(detail.realPayAmount ?? detail.price) }}</div>
        </div>

        <div class="order-foot">
          <div class="order-amount">
            <span v-if="(order.discountAmount ?? 0) > 0" class="amount-origin">
              原价 {{ formatPrice(order.totalAmount) }}
            </span>
            <span>实付 <b data-testid="order-real-amount">{{ formatPrice(order.realAmount) }}</b></span>
          </div>
          <div class="order-actions">
            <el-button
              v-if="order.canPay"
              type="primary"
              size="small"
              data-testid="order-pay-btn"
              @click="router.push(`/pay/${order.id}`)"
            >
              去支付
            </el-button>
            <el-button
              v-if="order.canCancel"
              size="small"
              data-testid="order-cancel-btn"
              @click="handleCancel(order)"
            >
              取消订单
            </el-button>
            <el-button size="small" @click="router.push(`/orders/${order.id}`)">查看详情</el-button>
          </div>
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
.orders-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 8px;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.head-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.status-tabs {
  margin-bottom: 8px;
}

.order-list {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-card :deep(.el-card__body) {
  padding: 0;
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.order-head-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}

.order-id {
  font-size: 13px;
  font-family: var(--fm-mono, monospace);
  color: var(--el-text-color-regular);
}

.order-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.order-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.order-item:hover {
  background: var(--el-fill-color-light);
}

.item-name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-price {
  font-size: 14px;
  color: var(--el-text-color-regular);
  flex-shrink: 0;
}

.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  flex-wrap: wrap;
}

.order-amount {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 14px;
}

.order-amount b {
  font-size: 18px;
  color: var(--el-color-danger);
}

.amount-origin {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

.order-actions {
  display: flex;
  gap: 8px;
}

.pager {
  margin-top: 20px;
  justify-content: center;
}
</style>
