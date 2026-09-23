<script setup lang="ts">
import { computed } from 'vue'

import { InfoFilled, ShoppingCart } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

import CourseCover from '@/components/CourseCover.vue'
import type { ChatCardData, CourseListCard, OrderConfirmCard } from '@/types/ai'
import { CARD_TYPE } from '@/types/ai'
import { formatDateTime, formatPrice } from '@/utils/format'

/**
 * 对话里的结构化卡片
 *
 * ============================================================
 * ★★ 卡片不是"模型写出来的文本"，是**工具返回的原始数据**
 * ============================================================
 * 后端的设计：给模型看的是文本（`"搜到 4 门相关课程：- id=535｜..."`），
 * 给前端渲染的是一份**单独的**结构化 JSON（`{type, title, keyword, courses:[...]}`）。
 * 理由是两者的字段需求不同 —— 卡片要封面图，模型不需要；混在一起两边都不好改。
 *
 * 这对前端的含义：
 *   · 卡片里的数字（价格、节数）**可信**，它就是数据库里的值，模型无从篡改；
 *   · 而回答正文里出现的价格，是模型"复述"的 —— 两者不一致时以卡片为准；
 *   · 所以卡片要点得动（跳到详情/支付页），让用户能自己去核对。
 *
 * ============================================================
 * ★ 未知卡片类型要优雅降级
 * ============================================================
 * 后端加一种新卡片时，前端可能还没发版。这时**不能渲染成空白** ——
 * 那会让用户以为"AI 没给东西"。至少要说明"这是什么、去哪儿看"。
 */
const props = defineProps<{ card: ChatCardData }>()

const router = useRouter()

const courseList = computed(() =>
  props.card.type === CARD_TYPE.COURSE_LIST ? (props.card as CourseListCard) : null,
)
const orderConfirm = computed(() =>
  props.card.type === CARD_TYPE.ORDER_CONFIRM ? (props.card as OrderConfirmCard) : null,
)

function goCourse(courseId: string | null | undefined) {
  if (!courseId) return
  router.push({ name: 'course-detail', params: { id: String(courseId) } })
}

function goPay() {
  const id = orderConfirm.value?.orderId
  if (!id) return
  router.push({ name: 'pay', params: { orderId: String(id) } })
}

/** 卡片的兜底标题（后端都给了 title，但不保证） */
const title = computed(() => props.card.title || '')
</script>

<template>
  <!-- ==================== 课程列表卡片 ==================== -->
  <div v-if="courseList" class="chat-card" data-testid="chat-card-course-list">
    <div class="card-head">
      <span class="card-title">{{ courseList.title || '为你找到的课程' }}</span>
      <el-tag v-if="courseList.keyword" size="small" type="info" effect="plain">
        检索词：{{ courseList.keyword }}
      </el-tag>
      <span class="card-count">{{ courseList.courses?.length || 0 }} 门</span>
    </div>

    <div class="course-grid">
      <div
        v-for="course in courseList.courses || []"
        :key="course.courseId"
        class="mini-course"
        data-testid="chat-course-item"
        role="button"
        tabindex="0"
        @click="goCourse(course.courseId)"
        @keydown.enter="goCourse(course.courseId)"
      >
        <CourseCover
          class="mini-cover"
          :cover-url="course.coverUrl"
          :name="course.courseName"
          aspect="16 / 10"
        />
        <div class="mini-body">
          <div class="mini-name" :title="course.courseName">{{ course.courseName }}</div>
          <div class="mini-meta">
            <span class="mini-price">{{ formatPrice(course.price, course.free === 1) }}</span>
            <span v-if="course.sectionNum" class="mini-sections">{{ course.sectionNum }} 节</span>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- ==================== 待支付订单卡片 ==================== -->
  <div v-else-if="orderConfirm" class="chat-card" data-testid="chat-card-order">
    <div class="card-head">
      <span class="card-title">{{ orderConfirm.title || '待支付订单' }}</span>
      <span class="card-count">订单号 {{ orderConfirm.orderId }}</span>
    </div>

    <div class="order-courses">
      <div
        v-for="course in orderConfirm.courses || []"
        :key="course.courseId"
        class="order-row"
        data-testid="chat-order-item"
        @click="goCourse(course.courseId)"
      >
        <span class="order-name">{{ course.courseName }}</span>
        <span class="order-price">{{ formatPrice(course.price, course.free === 1) }}</span>
      </div>
    </div>

    <div class="order-amounts">
      <div class="amount-row">
        <span>课程金额</span>
        <span>{{ formatPrice(orderConfirm.totalAmount, false) }}</span>
      </div>
      <!--
        ★ 这里刻意**只在真的非 0 时**显示优惠行。
        后端预下单不算优惠券（`discountAmount` 恒为 0），
        若照实渲染会出现"优惠 -¥0.00" —— 看起来像算错了。
      -->
      <div v-if="orderConfirm.discountAmount" class="amount-row discount">
        <span>优惠</span>
        <span>-{{ formatPrice(orderConfirm.discountAmount, false) }}</span>
      </div>
      <div class="amount-row payable">
        <span>应付</span>
        <span class="payable-value" data-testid="chat-order-payable">
          {{ formatPrice(orderConfirm.realAmount, false) }}
        </span>
      </div>
      <div v-if="orderConfirm.payOutTime" class="pay-deadline">
        支付截止：{{ formatDateTime(orderConfirm.payOutTime) }}
        <!-- ★ 这句话是给用户看的边界：AI 能帮你把订单拉到"待支付"，但**不能替你付款** -->
        <span class="boundary">（AI 只能帮你把订单建到待支付，付款需要你自己确认）</span>
      </div>
    </div>

    <div class="card-actions">
      <el-button type="primary" :icon="ShoppingCart" data-testid="chat-order-pay" @click="goPay">
        去支付
      </el-button>
    </div>
  </div>

  <!-- ==================== 未知类型（优雅降级） ==================== -->
  <div v-else class="chat-card unsupported" data-testid="chat-card-unknown">
    <el-icon><InfoFilled /></el-icon>
    <span>
      收到一种前端还不认识的卡片（<code>{{ card.type }}</code>）{{ title ? `：${title}` : '' }}。
      可以到「我的订单」或「我的课程」里查看。
    </span>
  </div>
</template>

<style scoped>
.chat-card {
  margin-top: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  overflow: hidden;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.card-title {
  font-size: 13px;
  font-weight: 600;
}

.card-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* ---- 课程列表 ---- */
.course-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
  padding: 12px;
}

.mini-course {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.18s ease, transform 0.18s ease;
}

.mini-course:hover {
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.1);
  transform: translateY(-1px);
}

.mini-cover {
  font-size: 26px;
}

.mini-body {
  padding: 8px 10px 10px;
}

.mini-name {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

.mini-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 6px;
}

.mini-price {
  font-size: 14px;
  font-weight: 700;
  color: var(--el-color-danger);
}

.mini-sections {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* ---- 订单卡片 ---- */
.order-courses {
  padding: 10px 12px 4px;
}

.order-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
  font-size: 13px;
  cursor: pointer;
}

.order-row:hover .order-name {
  color: var(--el-color-primary);
}

.order-price {
  color: var(--el-text-color-secondary);
}

.order-amounts {
  padding: 6px 12px 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
  margin-top: 6px;
}

.amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 3px 0;
  color: var(--el-text-color-regular);
}

.amount-row.discount {
  color: var(--el-color-success);
}

.amount-row.payable {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.payable-value {
  font-size: 18px;
  color: var(--el-color-danger);
}

.pay-deadline {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.boundary {
  color: var(--el-color-warning);
}

.card-actions {
  display: flex;
  justify-content: flex-end;
  padding: 0 12px 12px;
}

/* ---- 未知类型 ---- */
.unsupported {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
  border-style: dashed;
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
}
</style>
