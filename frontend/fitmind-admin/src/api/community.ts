/**
 * 社区内容审核（fm-learning，网关前缀 `/ls`）
 *
 * ============================================================
 * ★★ 这一期的核心是"**隐藏**"，而隐藏的语义必须讲清楚
 * ============================================================
 * 三个模块（问题 / 回答 / 笔记）都只有一种处置动作：**隐藏 / 折叠**（`hidden` 布尔），
 * **没有物理删除**。这是**刻意的设计**：审核要可追溯、可撤销 ——
 * 删掉一条违规内容，事后没人能证明它存在过、也没法复议。
 *
 *   隐藏的效果是"**学员端查不到**"，而管理端**照常能查到**（`/admin/*` 的
 *   分页接口注释原话："含被隐藏的"）→ 这正是审核闭环需要的：藏了还能找回来。
 *
 * ★ 三个模块的"隐藏"各有自己的名字与连带行为（读源码确认）：
 *
 * | 模块 | 动作名 | 连带行为 |
 * |---|---|---|
 * | 问题 | 隐藏 / 显示 | —— |
 * | 回答 | 隐藏 / 显示 | ★ **隐藏顶层回答会连带隐藏其下评论** |
 * | 笔记 | 折叠 / 恢复 | —— |
 *
 * → 所以 UI 上的措辞要跟着后端走（回答用"隐藏"、笔记用"折叠"），
 *   不能统一写成"删除"或"下架" —— 那会让运营以为内容没了。
 */
import type {
  NoteAdminDetailVO,
  NoteAdminVO,
  PageDTO,
  QuestionAdminVO,
  ReplyVO,
} from '@/types/admin'
import { http } from '@/utils/request'

// ==================== 问题 ====================

/** 问题分页（★ **含被隐藏的**，且匿名提问会返回真实用户 —— 审核才看得到） */
export function getQuestionPage(query: {
  pageNo?: number
  pageSize?: number
  courseId?: string
  /** 管理端状态：0-未查看 1-已查看 */
  status?: number
  beginTime?: string
  endTime?: string
}): Promise<PageDTO<QuestionAdminVO>> {
  return http.get<PageDTO<QuestionAdminVO>>('/ls/admin/questions/page', { params: query })
}

/** 问题详情 */
export function getQuestionDetail(id: string): Promise<QuestionAdminVO> {
  return http.get<QuestionAdminVO>(`/ls/admin/questions/${id}`)
}

/**
 * 隐藏 / 显示问题。
 *
 * ★ `hidden` 是**路径参数**（`/hidden/{hidden}`），不是 body —— 写错位置会 404。
 */
export function setQuestionHidden(id: string, hidden: boolean): Promise<void> {
  return http.put<void>(`/ls/admin/questions/${id}/hidden/${hidden}`)
}

// ==================== 回答 ====================

/** 回答分页（★ 含被隐藏的） */
export function getReplyPage(query: {
  pageNo?: number
  pageSize?: number
  /** 查某个问题下的回答 */
  questionId?: string
  /** 查某条回答下的评论 */
  answerId?: string
}): Promise<PageDTO<ReplyVO>> {
  return http.get<PageDTO<ReplyVO>>('/ls/admin/replies/page', { params: query })
}

/** 回答详情 */
export function getReplyDetail(id: string): Promise<ReplyVO> {
  return http.get<ReplyVO>(`/ls/admin/replies/${id}`)
}

/**
 * 隐藏 / 显示回答。
 *
 * ⚠️ ★ **隐藏"顶层回答"会连带隐藏它下面的所有评论**（后端注释原话）。
 *   所以 UI 上要提示这一连带效应 —— 否则运营会以为"只藏了这一条"。
 */
export function setReplyHidden(id: string, hidden: boolean): Promise<void> {
  return http.put<void>(`/ls/admin/replies/${id}/hidden/${hidden}`)
}

// ==================== 笔记 ====================

/** 笔记分页（★ 含被折叠的，且附"被采集次数"） */
export function getNotePage(query: {
  pageNo?: number
  pageSize?: number
  courseId?: string
  /** true-已折叠 false-正常 */
  hidden?: boolean
  beginTime?: string
  endTime?: string
}): Promise<PageDTO<NoteAdminVO>> {
  return http.get<PageDTO<NoteAdminVO>>('/ls/admin/notes/page', { params: query })
}

/**
 * 笔记详情。
 *
 * ★ 比列表项**多两个只有审核才该看到的字段**：作者**手机号**、以及**采集者名单**
 *   （谁把这条笔记采集走了）。这是管理端详情存在的意义所在 ——
 *   列表给"是什么"，详情给"这条内容牵涉到谁"。
 */
export function getNoteDetail(id: string): Promise<NoteAdminDetailVO> {
  return http.get<NoteAdminDetailVO>(`/ls/admin/notes/${id}`)
}

/** 折叠 / 恢复笔记（后端把笔记的处置叫"折叠"，不是"隐藏"） */
export function setNoteHidden(id: string, hidden: boolean): Promise<void> {
  return http.put<void>(`/ls/admin/notes/${id}/hidden/${hidden}`)
}
