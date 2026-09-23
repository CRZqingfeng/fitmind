import type { LikeForm } from '@/types/social'
import { http } from '@/utils/request'

/**
 * 点赞接口（fm-remark，网关前缀 `/rs`）
 *
 * ★ 为什么单独一个文件、单独一个服务：
 *   "点赞"是一个**通用能力**（回答、笔记都点赞），所以它被抽到 fm-remark；
 *   而"回答""笔记"本身属于 fm-learning 的业务数据。
 *   服务边界在那里，前端就不要把它伪装成"回答的一部分" ——
 *   将来如果视频也要点赞，这里直接复用，不用动 social.ts。
 */

/**
 * 点赞 / 取消点赞。
 *
 * ★ 一个接口两种语义：靠 `liked` 传 true/false 区分 ——
 *   所以前端**不能**只按"点赞数变化"来推断状态，必须用返回后重新拉取的结果。
 *   这也是为什么 `ReplyVO.liked` / `NoteVO.liked` 是由后端实时查出来给我们的。
 */
export function likeBiz(form: LikeForm): Promise<void> {
  return http.post<void>('/rs/remarks/likes', form)
}

/**
 * 批量查"我点过赞的 id 集合"。
 *
 * ⚠️ 本项目里**通常用不上**：回答与笔记的接口都已经带了 `liked` 字段
 *   （fm-learning 返回前会去 fm-remark 查一次）。
 *   保留它是为了"列表里没有 liked 字段"的场景，别重复调。
 */
export function likedIds(bizType: string, bizIds: string[]): Promise<string[]> {
  return http.get<string[]>('/rs/remarks/likes/list', {
    params: { bizType, bizIds: bizIds.join(',') },
  })
}
