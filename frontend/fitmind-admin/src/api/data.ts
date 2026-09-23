/**
 * 数据看板（fm-data，网关前缀 /ds）
 *
 * ★★ `/ds/admin/**` **校验管理员**（实测：学员 token → 403）。
 *
 * ★ 日期参数是 **`yyyyMMdd` 整数**（`date=20260920`），不是 `2026-09-20`。
 *   传字符串会走到后端的 `parseDate` 并抛"日期不合法"（业务码非 200）。
 */
import type { CourseTopVO, OverviewVO, TrendVO } from '@/types/admin'
import { http } from '@/utils/request'

/** 运营概览（不传 date = 今天） */
export function getOverview(date?: number): Promise<OverviewVO> {
  return http.get<OverviewVO>('/ds/admin/data/overview', { params: { date } })
}

/** 课程销量榜 TopN */
export function getCourseTop(date?: number, limit = 10): Promise<CourseTopVO[]> {
  return http.get<CourseTopVO[]>('/ds/admin/data/course-top', { params: { date, limit } })
}

/** 趋势数据（最近 N 天，1~90） */
export function getTrend(days = 7): Promise<TrendVO> {
  return http.get<TrendVO>('/ds/admin/data/trend', { params: { days } })
}

/**
 * 手动落库（把某个日期的实时计数写进汇总表）
 *
 * ★ 后端是**幂等**的（可重复调用），返回写入的行数。
 *   为什么需要它：日计数靠定时任务落库，演示环境不一定等到那个点；
 *   而且"今天"的数据还在 Redis 里（`source=realtime`），落库后才是 `archived`。
 */
export function flushData(date?: number): Promise<number> {
  return http.post<number>('/ds/admin/data/flush', null, { params: { date } })
}
