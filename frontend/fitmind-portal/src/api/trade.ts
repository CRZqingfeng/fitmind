import type { PageDTO } from '@/types/course'
import type {
  CartItem,
  Coupon,
  CouponDiscount,
  Order,
  PayOrder,
  PayUrl,
  PlaceOrderResult,
  PrePlaceOrder,
  RefundApplyItem,
  RefundDetail,
  RefundForm,
} from '@/types/trade'
import { http } from '@/utils/request'

/**
 * 交易域接口（M7-3）
 *
 * 涉及三个服务：
 *   /ts  fm-trade      购物车、订单、退款申请
 *   /ps  fm-pay        支付单、Mock 渠道
 *   /prs fm-promotion  优惠券
 *
 * ⚠️ **数组参数必须自己 `join(',')`**：
 *   axios 默认把数组序列化成 `courseIds[]=1&courseIds[]=2`，
 *   而 Spring 的 `@RequestParam("courseIds") List<Long>` 认的是
 *   `courseIds=1,2`（逗号分隔）或 `courseIds=1&courseIds=2`（重复参数）——
 *   `courseIds[]` 这个**名字对不上**，后端会直接报"缺少必要参数"。
 */

// ==================== 购物车 ====================

/** 加入购物车（★ 重复加购后端会忽略，不报错） */
export function addToCart(courseId: string): Promise<void> {
  return http.post<void>('/ts/carts', { courseId })
}

/**
 * 我的购物车。
 *
 * ★★ 默认 **`silent: true`** —— 这个接口被**顶栏角标**调用，每个页面加载都会打一次，
 *   而角标只是**辅助信息**：交易服务抖一下就在「学习」或「AI 教练」页弹一条
 *   "服务暂时不可用"，会把用户吓一跳（他正在看视频，跟购物车毫无关系）。
 *   失败信息由调用方决定怎么呈现（购物车页显示"加载失败 + 重试"，见 `CartView.vue`）。
 *
 * 这是 M7-4 验收时真实暴露的问题：页面上有一条红条，而当时的断言全绿 ——
 * 因为"业务码非 200"既不产生 pageerror 也不产生 console error。
 */
export function getCart(opts: { silent?: boolean } = { silent: true }): Promise<CartItem[]> {
  return http.get<CartItem[]>('/ts/carts', { silent: opts.silent })
}

/** 从购物车移除一门课 */
export function removeCartItem(courseId: string): Promise<void> {
  return http.delete<void>(`/ts/carts/${courseId}`)
}

/** 清空购物车 */
export function clearCart(): Promise<void> {
  return http.delete<void>('/ts/carts')
}

// ==================== 下单 ====================

/**
 * 下单预览。
 *
 * ⚠️ 它**不算优惠券**（返回的 `discountAmount` 恒为 0），见 `PrePlaceOrder` 的注释。
 */
export function prePlaceOrder(courseIds: string[]): Promise<PrePlaceOrder> {
  return http.get<PrePlaceOrder>('/ts/orders/prePlace', {
    params: { courseIds: courseIds.join(',') },
  })
}

/**
 * 提交订单。
 *
 * `orderId` 由 `prePlaceOrder` 生成后带过来 —— 后端用它做主键做**幂等**：
 * 同一个 id 重复提交只会返回已存在的订单，不会下两单。
 *
 * `userCouponIds` 为空数组 = 不用券（后端据此跳过调用促销服务）。
 */
export function placeOrder(payload: {
  orderId: string
  courseIds: string[]
  userCouponIds?: string[]
}): Promise<PlaceOrderResult> {
  return http.post<PlaceOrderResult>('/ts/orders', {
    orderId: payload.orderId,
    courseIds: payload.courseIds,
    userCouponIds: payload.userCouponIds ?? [],
  })
}

/** 我的订单分页（status 不传 = 全部） */
export function pageOrders(params: {
  pageNo: number
  pageSize: number
  status?: number
}): Promise<PageDTO<Order>> {
  return http.get<PageDTO<Order>>('/ts/orders/page', { params })
}

/** 订单详情（含明细，★ 明细里才有 courseId，退款要用） */
export function getOrder(id: string): Promise<Order> {
  return http.get<Order>(`/ts/orders/${id}`)
}

/**
 * 发起支付（返回收银台地址）。
 *
 * 后端会**复用**同一订单未过期的支付单（`reused=true`），所以重复点"去支付"不会产生多笔支付单。
 */
export function applyPay(orderId: string, payChannelCode = 'mockPay'): Promise<PayUrl> {
  return http.post<PayUrl>(`/ts/orders/${orderId}/pay`, null, {
    params: { payChannelCode },
  })
}

/** 取消订单（只能取消待支付的） */
export function cancelOrder(orderId: string): Promise<void> {
  return http.put<void>(`/ts/orders/${orderId}/cancel`)
}

// ==================== 支付（本地闭环用的 Mock 渠道） ====================

/**
 * 模拟"用户在收银台付了钱"。
 *
 * 它会把回调**真的打一遍**（走验签 → MQ → 订单状态流转），
 * 所以不是"绕过支付直接改状态"——支付链路的每个环节都会真实执行。
 */
export function mockPay(payOrderNo: string): Promise<string> {
  return http.post<string>(`/ps/mock/pay/${payOrderNo}`)
}

/** 模拟支付失败（用于验证失败分支） */
export function mockPayFail(payOrderNo: string): Promise<string> {
  return http.post<string>(`/ps/mock/fail/${payOrderNo}`)
}

// ==================== 退款 ====================

/** 申请退款（按课程维度，一次退一门） */
export function applyRefund(form: RefundForm): Promise<string> {
  return http.post<string>('/ts/refund-apply', form)
}

/** 撤销退款申请 */
export function cancelRefund(payload: { id?: string; orderDetailId?: string }): Promise<void> {
  return http.put<void>('/ts/refund-apply/cancel', payload)
}

/** 我的退款记录 */
export function pageRefunds(params: {
  pageNo: number
  pageSize: number
  status?: number
}): Promise<PageDTO<RefundApplyItem>> {
  return http.get<PageDTO<RefundApplyItem>>('/ts/refund-apply/page', { params })
}

// ==================== 优惠券 ====================

/** 我的优惠券（status 不传 = 全部；1-未使用 2-已使用 3-已失效） */
export function myCoupons(status?: number): Promise<Coupon[]> {
  return http.get<Coupon[]>('/prs/user-coupons', {
    params: status === undefined ? {} : { status },
  })
}

/** 领取优惠券 */
export function receiveCoupon(couponId: string): Promise<void> {
  return http.post<void>(`/prs/user-coupons/${couponId}/receive`)
}

/**
 * 试算"用这几张券能省多少"。
 *
 * ★ 为什么敢调 `/internal/` 接口：网关只按 `/prs/**` 判"需要登录"，
 *   没有把 internal 单独拦掉（和 `/cs/courses/internal/{id}/full` 一样，已实测可访问）。
 *   但它的入参形态是**服务间调用**的样子（要传 userId、prices），
 *   所以调用方得自己把 userId 和价格凑齐 —— 这也是为什么它不适合当"唯一的下单页数据源"。
 */
export function previewDiscount(payload: {
  userId: string
  courseIds: string[]
  prices: number[]
  userCouponIds: string[]
}): Promise<CouponDiscount> {
  return http.get<CouponDiscount>('/prs/internal/discount/detail', {
    params: {
      userId: payload.userId,
      courseIds: payload.courseIds.join(','),
      prices: payload.prices.join(','),
      userCouponIds: payload.userCouponIds.join(','),
    },
  })
}

/**
 * 用兑换码兑换优惠券（M7-12）。
 *
 * 后端 `@Operation` 原文："★ 用兑换码兑换"。
 *
 * ⚠️ 兑换码是**一次性**的 —— 同一个码第二次兑换后端会拒。
 *    验收脚本要专门断言这一点（不能只看"第一次成功了"）。
 */
export function exchangeCouponByCode(code: string): Promise<void> {
  return http.post<void>(`/prs/user-coupons/exchange/${encodeURIComponent(code)}`)
}

// ==================== 退款详情（M7-12）====================

/**
 * 退款详情（按退款申请 id）。
 *
 * ★ 与列表接口的区别不只是"字段更多"：详情里有**审批意见**与**渠道侧单号**，
 *   这两样是用户点进详情唯一想看的东西。
 */
export function getRefundDetail(id: string): Promise<RefundDetail> {
  return http.get<RefundDetail>(`/ts/refund-apply/${id}`)
}

/**
 * 按**订单明细 id** 查最近一次退款（后端 `@Operation` 原文："我的课程页用"）。
 *
 * ★ 为什么"我的课程"需要它：课表里一条记录对应一个订单明细，
 *   页面上要能直接显示"这门课我申请过退款、现在是待审批" —— 而课表里**没有**退款申请 id，
 *   只有明细 id。所以这条路径是"从课程反查退款状态"的唯一入口。
 *
 * ⚠️ 返回 `data: null` 表示**这门课没有退款申请**（不是错误），调用方按空态处理。
 */
export function getRefundByDetailId(detailId: string): Promise<RefundDetail | null> {
  return http.get<RefundDetail | null>(`/ts/refund-apply/detail/${detailId}`, { silent: true })
}

// ==================== 支付单状态（M7-12）====================

/**
 * 查询支付单状态（后端 `@Operation` 原文："前端轮询支付结果"）。
 *
 * ★★ 两个"反直觉"要点（都是读后端源码确认的）：
 *   1. 返回的是**裸 PO**，`status` 取值 `1-未支付 / 2-已支付 / 3-已关闭`
 *      （`PayConstants.PAY_STATUS_*`），不是订单状态那套编码
 *   2. **查不到、或不是自己的支付单 → 都返回 `data: null`**（后端直接 return null，
 *      不报错）。所以**不能靠 catch 判断"不存在"**，必须判 null
 */
export function getPayOrderStatus(payOrderNo: string): Promise<PayOrder | null> {
  return http.get<PayOrder | null>(`/ps/pay-orders/${payOrderNo}`, { silent: true })
}
