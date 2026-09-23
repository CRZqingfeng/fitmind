import type { PageDTO } from '@/types/course'
import type { InboxMessage, InboxQuery } from '@/types/social'
import { http } from '@/utils/request'

/**
 * 站内信接口（fm-message，网关前缀 `/sms`）
 *
 * 消息是**别人写进我收件箱**的：购课成功、退款到账、笔记被采集、问答被回复…
 * 都在各自的业务链路上由 MQ 触发生成（M4-4 起就接通了），
 * 前端只负责"看"和"标记已读"。
 */

/** 我的消息分页（可按 `isRead` / `type` 筛选） */
export function pageMessages(query: InboxQuery): Promise<PageDTO<InboxMessage>> {
  return http.get<PageDTO<InboxMessage>>('/sms/messages/page', { params: query })
}

/**
 * 未读条数（顶栏角标用）。
 *
 * ★★ 默认 **`silent: true`** —— 它是**每个页面都会打的辅助请求**，
 *   失败不该在用户正在看的页面上弹红条。
 *   这是 M7-4 踩过的坑（购物车角标 500 导致学习页挂红条），同一个坑不踩第二次。
 */
export function getUnreadCount(opts: { silent?: boolean } = { silent: true }): Promise<number> {
  return http.get<number>('/sms/messages/unread-count', { silent: opts.silent })
}

/** 标记单条已读 */
export function markMessageRead(id: string): Promise<void> {
  return http.put<void>(`/sms/messages/${id}/read`)
}

/** 全部标记已读 */
export function markAllMessagesRead(): Promise<void> {
  return http.put<void>('/sms/messages/read-all')
}
