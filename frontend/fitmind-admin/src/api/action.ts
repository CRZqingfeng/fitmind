import type { ActionAssessment, PageDTO } from '@/types/admin'
import { http } from '@/utils/request'

/**
 * 动作考核（fm-exam 管理端，网关前缀 `/es`）
 *
 * ★★ 这两个接口在 `/es/admin/**` 下 —— **要求管理员**。
 *   学员 token 调它们会拿到 **HTTP 403**（不是业务码），
 *   这是 M7-8 就实测确认过的权限边界，M7-10 的验收里也保留了这条负向断言。
 *
 * ★ 为什么评分这条要强调"并发下只有一次生效"（见后端 `@Operation` 注释）：
 *   两位教练同时打开同一条待评分记录、各自打分，后提交的那次不应该覆盖前一次 —— 
 *   否则"谁最后点的谁说了算"，而先评的那位会以为自己评过了。
 *   后端用条件更新保证只生效一次；前端要做的只是**评完立刻刷新列表**，
 *   让教练看到真实状态（而不是本地改一个数字了事）。
 */

/** 待评分列表 */
export function pagePendingActions(params: {
  pageNo: number
  pageSize: number
}): Promise<PageDTO<ActionAssessment>> {
  return http.get<PageDTO<ActionAssessment>>('/es/admin/action-assessments/pending', { params })
}

/** 给动作考核评分（0~100；并发下只有一次生效） */
export function reviewAction(payload: {
  id: string
  score: number
  comment?: string
}): Promise<void> {
  return http.put<void>('/es/admin/action-assessments/review', payload)
}
