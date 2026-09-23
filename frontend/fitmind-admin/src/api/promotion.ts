/**
 * 优惠券（fm-promotion，网关前缀 /prs）
 *
 * ⚠️ 三个**实测出来的硬约束**（不按这三条做，券会"建得出来但用不了"）：
 *
 * 1. **`issueEndTime` 必填**。`CouponServiceImpl.cacheCoupon` 里有
 *    `coupon.getIssueEndTime().atZone(...)` —— 直接解引用、**没有判空**。
 *    所以漏了它建券会 500（`NullPointerException`），而不是给你一条友好提示。
 *
 * 2. **建完必须"发放"**（`PUT /{id}/issue` 把状态置为 2-发放中）。
 *    `receiveCoupon` 第一件事就是校验 `status == COUPON_ISSUING`，
 *    否则报"券还未开始发放"。也就是说：**只创建不发放 = 一张谁都领不到的券**。
 *
 * 3. **有效期两种口径**：`termDays > 0` 时走"领取后 N 天"
 *    （`saveUserCoupon` 里 `now.plusDays(termDays)`），否则用 `termEndTime`。
 *    本页默认用 `termDays` —— 它不依赖"什么时候领"，演示时更不容易过期。
 *
 * ★ 另外：**后端没有"券列表"查询接口**（`CouponController` 只有 POST 和 PUT issue）。
 *   所以本页不假装有列表：创建后展示"本次创建的这张"，并提供"自己领一张"的入口
 *   （`POST /prs/user-coupons/{couponId}/receive`），
 *   这样管理员能在 `GET /prs/user-coupons` 里看到它确实生效了。
 */
import type { Coupon } from '@/types/admin'
import { http } from '@/utils/request'

/** 新增券（返回券 id） */
export function saveCoupon(coupon: Coupon): Promise<string> {
  return http.post<string>('/prs/coupons', coupon)
}

/** 开始发放（置为"发放中"并重新预热 Redis）—— 不发这一步，券领不到 */
export function beginIssueCoupon(couponId: string): Promise<void> {
  return http.put<void>(`/prs/coupons/${couponId}/issue`)
}

/** 领取一张券（管理员自己领，用来验证券真的可用） */
export function receiveCoupon(couponId: string): Promise<void> {
  return http.post<void>(`/prs/user-coupons/${couponId}/receive`)
}

/**
 * 我的券列表（`GET /prs/user-coupons`）
 *
 * ★ 注意这里看的是"**当前登录用户**的券"，不是"系统里所有券"——
 *   管理员领了才会出现在这里。列表接口不存在是后端现状（见文件头说明）。
 */
export function getMyCoupons(status?: number): Promise<Record<string, unknown>[]> {
  return http.get<Record<string, unknown>[]>('/prs/user-coupons', { params: { status } })
}

/** 生成兑换码（按券批量生成） */
export function generateExchangeCodes(couponId: string): Promise<string[]> {
  return http.get<string[]>(`/prs/exchange-codes/generate/${couponId}`)
}
