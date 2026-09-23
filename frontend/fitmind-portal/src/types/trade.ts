/**
 * 交易域类型定义（M7-3：购物车 / 订单 / 支付 / 退款 / 优惠券）
 *
 * ⚠️ 两条贯穿全项目的约定，这里不再逐字段重复：
 *   1. **所有 Long 字段都是 string** —— 后端 `JacksonConfig` 全局把 Long 序列化成字符串
 *      （雪花 id 超过 JS 的 2^53，不转字符串会精度丢失）。所以 orderId / courseId 一律 string。
 *   2. **金额一律是"分"**，且**只用于展示**。任何"该付多少钱"的计算都必须以服务端返回为准 ——
 *      前端的算术只配用来渲染，不配用来定价。
 */

// ==================== 枚举（用 const 对象而不是 enum）====================
// ★ 模板的 tsconfig 开了 `erasableSyntaxOnly`，TS 的 `enum` 会生成运行时代码，被禁掉了。
//   用 `as const` + 联合类型代替，效果一样且零运行时开销。

/** 订单状态（对应后端 `OrderStatus`，注意**没有 4**） */
export const OrderStatus = {
  NO_PAY: 1,
  PAYED: 2,
  CLOSED: 3,
  ENROLLED: 5,
  REFUNDED: 6,
  REFUND_SUCCESS: 7,
} as const

/** 退款申请状态（对应后端 `fm-trade` 的 `RefundStatus`） */
export const RefundStatus = {
  UN_APPROVE: 1,
  CANCEL: 2,
  AGREE: 3,
  REJECT: 4,
  SUCCESS: 5,
  FAILED: 6,
} as const

/** 优惠券使用状态（对应后端 `PromotionConstants`） */
export const UserCouponStatus = {
  UNUSED: 1,
  USED: 2,
  INVALID: 3,
} as const

/** 折扣类型：1-满减 2-每满减 3-折扣 4-无门槛 */
export const DiscountType = {
  THRESHOLD: 1,
  PER: 2,
  RATE: 3,
  NO_THRESHOLD: 4,
} as const

// ==================== 购物车 ====================

export interface CartItem {
  courseId: string
  courseName: string
  coverUrl: string | null
  /** 当前价格，单位分 */
  price: number
  /** 加入购物车时的价格快照，单位分（用于提示"降价了"） */
  addedPrice: number | null
  /** 已失效（课程下架 / 已过期等），前端应置灰且不可下单 */
  invalid: boolean | null
  invalidReason: string | null
  /** 已经买过了，不能再下单 */
  bought: boolean | null
}

// ==================== 下单 ====================

export interface PrePlaceCourse {
  courseId: string
  courseName: string
  coverUrl: string | null
  price: number
  validDuration: number | null
}

/**
 * 下单预览
 *
 * ⚠️ **`discountAmount` 恒为 0**：后端 `prePlaceOrder` 目前不算优惠券
 * （它是"只算课程原价"的预览）。真实优惠要到 `placeOrder` 提交时才由
 * 促销服务算出来。所以：
 *   - 预览页的"应付"必须标注为"未使用优惠券的金额"，或者
 *   - 用户勾选券时**另调** `/prs/internal/discount/detail` 拿准确金额
 * 详见 `CheckoutView.vue` 的处理。
 */
export interface PrePlaceOrder {
  orderId: string
  totalAmount: number
  discountAmount: number
  realAmount: number
  /** 支付截止时间（超时自动关单） */
  payOutTime: string | null
  courses: PrePlaceCourse[] | null
}

export interface PlaceOrderResult {
  orderId: string
  realAmount: number
  payOutTime: string | null
  /** false = 重复提交，返回的是已存在的订单（后端做了幂等） */
  created: boolean
}

// ==================== 订单 ====================

export interface OrderDetail {
  id: string
  courseId: string
  courseName: string
  coverUrl: string | null
  price: number
  discountAmount: number | null
  realPayAmount: number | null
  validDuration: number | null
  courseExpireTime: string | null
  status: number | null
}

export interface Order {
  id: string
  status: number
  /** 后端已给好中文文案，直接用（★ 不要前端再映射一遍，两处映射迟早不一致） */
  statusDesc: string
  totalAmount: number
  discountAmount: number | null
  realAmount: number
  message: string | null
  createTime: string | null
  payTime: string | null
  closeTime: string | null
  payOutTime: string | null
  canPay: boolean | null
  canCancel: boolean | null
  details: OrderDetail[] | null
}

// ==================== 支付 ====================

export interface PayUrl {
  orderId: string
  payOrderNo: string
  /** Mock 渠道下是收银台 HTML 地址；真实渠道才是二维码 */
  qrCodeUrl: string | null
  amount: number
  expireTime: string | null
  /** true = 复用了上一笔未过期的支付单（后端没重复建单） */
  reused: boolean | null
}

// ==================== 退款 ====================

export interface RefundForm {
  courseId: string
  refundReason: string
  questionDesc?: string
}

export interface RefundApplyItem {
  id: string
  orderId: string
  orderDetailId: string
  courseName: string
  refundAmount: number
  refundReason: string | null
  status: number
  statusDesc: string
  createTime: string | null
  approveTime: string | null
  finishTime: string | null
}

// ==================== 优惠券 ====================

export interface Coupon {
  id: string
  couponId: string
  name: string
  discountType: number
  /** 后端生成好的门槛/优惠说明文案，如「满 100 元减 10 元」 */
  rule: string | null
  /** 是否限定范围（限定某分类/某课程） */
  specific: boolean | null
  status: number
  termBeginTime: string | null
  termEndTime: string | null
  expired: boolean | null
}

/** 用券试算的结果（促销服务返回） */
export interface CouponDiscount {
  ids: string[] | null
  rules: string[] | null
  discountAmount: number
  /** 每门课分摊到的优惠金额：{ courseId: 分 } */
  discountDetail: Record<string, number> | null
}
