import { ReplyUserType } from '@/types/social'

/**
 * 回答者的**身份徽章**文案与颜色（M10-1 第 2 期从 `QuestionDetailView.vue` 抽出）。
 *
 * ============================================================
 * ★ 为什么要抽出来
 * ============================================================
 * 播放页的评论区（`components/learn/QaPanel.vue`）和问答详情页
 * （`views/QuestionDetailView.vue`）渲染的是同一份 `ReplyVO`，
 * 都要按 `userType` 显示徽章。
 *
 * 如果各写一份，那么"加一种身份"就要改两处 —— 而漏掉一处的表现是
 * **同一个回答在两个页面上显示成不同身份**，这种 bug 很难被想到。
 * （本轮就正好要加 `BOT`：一个地方忘改，机器人就会在某个页面被显示成"学员"。）
 *
 * ★ 未知的 `userType` 一律**降级成"学员"而不是抛错**：
 *   后端将来加新身份时，旧版前端不应该因为不认识它就白屏。
 *   这和 `ChatCard.vue` 里"未知卡片类型要优雅降级"是同一条原则。
 */

/** `userType` → 徽章文案 */
export function replyUserLabel(t: number | null | undefined): string {
  if (t === ReplyUserType.ADMIN) return '管理员'
  if (t === ReplyUserType.COACH) return '教练'
  if (t === ReplyUserType.BOT) return 'AI 助手'
  return '学员'
}

/**
 * `userType` → el-tag 的颜色。
 *
 * ★ 给机器人用 `warning`（橙）而不是 `success`：徽章的意义是**让用户一眼看出
 *   对方不是人**，颜色上就该与"教练 / 管理员"这类真人身份明显区分开。
 */
export function replyUserTag(t: number | null | undefined): 'danger' | 'success' | 'info' | 'warning' {
  if (t === ReplyUserType.ADMIN) return 'danger'
  if (t === ReplyUserType.COACH) return 'success'
  if (t === ReplyUserType.BOT) return 'warning'
  return 'info'
}
