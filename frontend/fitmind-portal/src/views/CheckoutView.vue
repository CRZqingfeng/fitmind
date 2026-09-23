<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { myCoupons, placeOrder, prePlaceOrder, previewDiscount } from '@/api/trade'
import { useAuthStore } from '@/stores/auth'
import type { Coupon, CouponDiscount, PrePlaceOrder } from '@/types/trade'
import { UserCouponStatus } from '@/types/trade'
import { formatPrice, formatRemaining } from '@/utils/format'

/**
 * 下单确认（M7-3）
 *
 * ★★ 这个页面最容易做错的地方是"金额"：
 *
 *   1. `prePlace` 返回的 `discountAmount` **恒为 0** —— 后端预下单不算优惠券。
 *      拿它当"应付金额"展示，用户会看到"应付 199"，提交后却变成 189，
 *      然后你没法解释那 10 块是哪来的。
 *
 *   2. 用户选券之后必须**另调促销服务**（`/prs/internal/discount/detail`）拿优惠额，
 *      而且这个数仍然只是**预估** —— 真正生效的是提交订单时服务端算的那一份。
 *
 *   3. 所以这个页面的所有金额都标注为"预估"，提交后到支付页展示的是
 *      **订单里真实存下来的金额**。这不是偷懒，是承认"前端的算术只配用来渲染"。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const courseIds = computed(() =>
  String(route.query.courseIds ?? '')
    .split(',')
    .filter(Boolean),
)

const loading = ref(true)
const submitting = ref(false)
const preview = ref<PrePlaceOrder | null>(null)

const coupons = ref<Coupon[]>([])
const selectedCouponIds = ref<string[]>([])
const discount = ref<CouponDiscount | null>(null)
const discountLoading = ref(false)
/**
 * 券试算链路不可用（促销服务没起 / 接口被拦）
 *
 * ★ 必须把"没选券"和"选不了券"分成两件事告诉用户：
 *   前者是用户的选择，后者是系统的故障 —— 混成一句"无优惠"，
 *   用户在演示时会以为"优惠券功能没做"。
 */
const discountUnavailable = ref(false)

/** 封面加载失败兜底 */
const coverFailed = ref<Record<string, boolean>>({})

/** 距离订单自动关闭还剩多久（每 10 秒刷一次就够，不必每秒） */
const remaining = ref('—')
let timer: number | undefined

const totalAmount = computed(() => preview.value?.totalAmount ?? 0)
const discountAmount = computed(() => discount.value?.discountAmount ?? 0)
/** 预估应付：仅用于展示，提交后以订单里的 realAmount 为准 */
const payableAmount = computed(() => Math.max(totalAmount.value - discountAmount.value, 0))
const usedRules = computed(() => discount.value?.rules ?? [])

onMounted(async () => {
  if (courseIds.value.length === 0) {
    ElMessage.error('缺少课程参数，请从购物车或课程详情进入')
    router.replace('/cart')
    return
  }

  try {
    preview.value = await prePlaceOrder(courseIds.value)
  } catch {
    // 预下单失败（课程下架/已购买/有未支付旧单）——后端已给出原因，回购物车让用户处理
    router.replace('/cart')
    return
  } finally {
    loading.value = false
  }

  tick()
  timer = window.setInterval(tick, 10000)

  // 券列表加载失败不该拦住下单，静默即可
  try {
    coupons.value = (await myCoupons(UserCouponStatus.UNUSED)) ?? []
  } catch {
    coupons.value = []
  }
})

onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

function tick() {
  remaining.value = formatRemaining(preview.value?.payOutTime)
}

/** 勾选/取消优惠券 → 重新试算 */
async function recomputeDiscount() {
  if (selectedCouponIds.value.length === 0) {
    discount.value = null
    return
  }
  const userId = auth.user?.id
  if (!userId || !preview.value) {
    discount.value = null
    return
  }

  discountLoading.value = true
  try {
    discount.value = await previewDiscount({
      userId,
      courseIds: courseIds.value,
      prices: (preview.value.courses ?? []).map((c) => c.price),
      userCouponIds: selectedCouponIds.value,
    })
  } catch {
    // ★ 试算失败时必须**把选中的券清掉**：否则页面上会保留"已选券"的状态，
    //   而金额又是原价 —— 用户以为券生效了，提交后金额对不上
    discountUnavailable.value = true
    discount.value = null
    selectedCouponIds.value = []
  } finally {
    discountLoading.value = false
  }
}

async function handleSubmit() {
  if (!preview.value) return
  submitting.value = true
  try {
    const result = await placeOrder({
      orderId: preview.value.orderId,
      courseIds: courseIds.value,
      userCouponIds: selectedCouponIds.value,
    })
    // 去支付页。金额让支付页自己从订单里读（那才是服务端真正记下来的数）
    router.push(`/pay/${result.orderId}`)
  } catch {
    /* 已由 request.ts 统一提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="checkout-page">
    <div class="page-head">
      <h2>确认订单</h2>
      <span v-if="preview" class="head-hint" data-testid="checkout-deadline">
        请在 {{ remaining }} 内完成支付，超时订单将自动关闭
      </span>
    </div>

    <template v-if="preview">
      <el-card shadow="never" class="block">
        <template #header>
          <span class="block-title">课程清单</span>
        </template>
        <div
          v-for="course in preview.courses ?? []"
          :key="course.courseId"
          class="course-row"
          data-testid="checkout-course-row"
        >
          <div class="row-cover">
            <img
              v-if="course.coverUrl && !coverFailed[course.courseId]"
              :src="course.coverUrl"
              :alt="course.courseName"
              @error="coverFailed[course.courseId] = true"
            />
            <div v-else class="cover-fallback">{{ course.courseName.slice(0, 1) }}</div>
          </div>
          <div class="row-info">
            <div class="row-name">{{ course.courseName }}</div>
            <div class="row-sub">
              有效期：{{ course.validDuration ? `${course.validDuration} 天` : '永久' }}
            </div>
          </div>
          <div class="row-price">{{ formatPrice(course.price) }}</div>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          <div class="block-header">
            <span class="block-title">优惠券</span>
            <el-tag v-if="discountUnavailable" type="warning" size="small">
              优惠券试算暂不可用，将按原价下单
            </el-tag>
          </div>
        </template>

        <el-empty
          v-if="coupons.length === 0"
          description="暂无可用优惠券"
          :image-size="60"
          data-testid="checkout-coupon-empty"
        />
        <el-checkbox-group
          v-else
          v-model="selectedCouponIds"
          class="coupon-group"
          data-testid="checkout-coupon-group"
          @change="recomputeDiscount"
        >
          <el-checkbox
            v-for="coupon in coupons"
            :key="coupon.id"
            :value="coupon.id"
            class="coupon-item"
            :disabled="discountUnavailable"
          >
            <span class="coupon-name">{{ coupon.name }}</span>
            <span v-if="coupon.rule" class="coupon-rule">{{ coupon.rule }}</span>
          </el-checkbox>
        </el-checkbox-group>

        <div v-if="usedRules.length" class="rule-hint" data-testid="checkout-coupon-rules">
          已应用：{{ usedRules.join('、') }}
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <div class="amount-line">
          <span>课程合计</span>
          <span data-testid="checkout-total">{{ formatPrice(totalAmount) }}</span>
        </div>
        <div class="amount-line">
          <span>优惠</span>
          <span class="amount-discount" data-testid="checkout-discount">
            {{ discountAmount > 0 ? `-${formatPrice(discountAmount)}` : formatPrice(0) }}
          </span>
        </div>
        <el-divider />
        <div class="amount-line amount-payable">
          <span>预估应付</span>
          <span data-testid="checkout-payable">{{ formatPrice(payableAmount) }}</span>
        </div>
        <div class="amount-note">
          最终金额以提交订单后服务端计算为准（★ 前端只做展示，不做定价）
        </div>
      </el-card>

      <div class="actions">
        <el-button size="large" @click="router.back()">返回</el-button>
        <el-button
          type="primary"
          size="large"
          :loading="submitting || discountLoading"
          data-testid="checkout-submit"
          @click="handleSubmit"
        >
          提交订单并支付
        </el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.checkout-page {
  max-width: 880px;
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

.head-hint {
  font-size: 13px;
  color: var(--el-color-warning);
}

.block {
  margin-bottom: 16px;
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

.course-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.course-row:last-child {
  border-bottom: none;
}

.row-cover {
  width: 88px;
  height: 58px;
  border-radius: 6px;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--el-fill-color-light);
}

.row-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #ff8a5b, #e5533d);
}

.row-info {
  flex: 1;
  min-width: 0;
}

.row-name {
  font-size: 15px;
  font-weight: 600;
}

.row-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.row-price {
  font-size: 16px;
  font-weight: 700;
  color: var(--el-color-danger);
}

.coupon-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.coupon-item {
  height: auto;
  align-items: flex-start;
}

.coupon-name {
  font-weight: 600;
  margin-right: 8px;
}

.coupon-rule {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rule-hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-color-success);
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

.amount-payable {
  font-size: 16px;
  font-weight: 700;
}

.amount-payable span:last-child {
  font-size: 24px;
  color: var(--el-color-danger);
}

.amount-note {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-bottom: 32px;
}
</style>
