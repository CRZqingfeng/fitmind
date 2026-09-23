import type { PageDTO } from '@/types/course'
import type {
  Note,
  NoteForm,
  NotePageQuery,
  Question,
  QuestionForm,
  QuestionPageQuery,
  Reply,
  ReplyForm,
  ReplyPageQuery,
} from '@/types/social'
import { http } from '@/utils/request'

/**
 * 社区接口（fm-learning 的互动部分，网关前缀 `/ls`）
 *
 * 三块：问答（questions / replies）· 笔记（notes / gathers）
 *
 * ★ 点赞**不在这个文件**：它在独立的 fm-remark 服务（见 `api/remark.ts`）。
 *   虽然界面上"点赞"和"回答"看起来是一件事，但服务边界是分开的 ——
 *   引用/收藏在 fm-learning（业务侧），点赞在 fm-remark（通用的"点赞"能力，
 *   视频、回答、笔记都用它）。跨服务的能力不要在前端假装是同一个。
 */

// ==================== 问答 ====================

/** 提问 */
export function saveQuestion(form: QuestionForm): Promise<void> {
  return http.post<void>('/ls/questions', form)
}

/** 修改我的提问 */
export function updateQuestion(id: string, form: QuestionForm): Promise<void> {
  return http.put<void>(`/ls/questions/${id}`, form)
}

/** 删除我的提问 */
export function deleteQuestion(id: string): Promise<void> {
  return http.delete<void>(`/ls/questions/${id}`)
}

/** 问题分页（可按课程 / 训练动作 / 只看我的筛选） */
export function pageQuestions(query: QuestionPageQuery): Promise<PageDTO<Question>> {
  return http.get<PageDTO<Question>>('/ls/questions/page', { params: query })
}

/** 问题详情 */
export function getQuestion(id: string): Promise<Question> {
  return http.get<Question>(`/ls/questions/${id}`)
}

// ==================== 回答 ====================

/** 回答（`answerId` 留空 = 回答顶层问题；填了 = 回复某条回答） */
export function saveReply(form: ReplyForm): Promise<void> {
  return http.post<void>('/ls/replies', form)
}

/** 回答分页（传 `questionId` 查回答，传 `answerId` 查评论） */
export function pageReplies(query: ReplyPageQuery): Promise<PageDTO<Reply>> {
  return http.get<PageDTO<Reply>>('/ls/replies/page', { params: query })
}

// ==================== 笔记 ====================

/** 写笔记 */
export function saveNote(form: NoteForm): Promise<void> {
  return http.post<void>('/ls/notes', form)
}

/** 修改我的笔记 */
export function updateNote(id: string, form: NoteForm): Promise<void> {
  return http.put<void>(`/ls/notes/${id}`, form)
}

/** 删除我的笔记 */
export function deleteNote(id: string): Promise<void> {
  return http.delete<void>(`/ls/notes/${id}`)
}

/** 笔记分页（可按课程 / 训练动作 / 只看我的筛选） */
export function pageNotes(query: NotePageQuery): Promise<PageDTO<Note>> {
  return http.get<PageDTO<Note>>('/ls/notes/page', { params: query })
}

/**
 * 采集（收藏）别人的笔记。
 *
 * ★ 注意语义：采集**不是"标记一下"**，而是在自己名下**复制出一条新笔记**
 *   （`NoteVO.userId` 变成采集者、`authorId` 保留原作者、`isGathered = true`）。
 *   所以采集后再调 `GET /ls/notes`（只看我的）会多出一条 —— 这是对的，不是 bug。
 */
export function gatherNote(id: string): Promise<void> {
  return http.post<void>(`/ls/notes/gathers/${id}`)
}

/** 取消采集 */
export function removeGatherNote(id: string): Promise<void> {
  return http.delete<void>(`/ls/notes/gathers/${id}`)
}
