/**
 * 题目 / 动作小测（fm-course，网关前缀 `/cs`）
 *
 * ★★ 一条贯穿整条链路的硬约束：**题目只存在于「草稿态」**
 *   （`seed-demo-data.mjs:1015-1031` 实测撞出来的，M7 方案 §16.1 有完整记录）
 *
 *   | 环节 | 后端校验的表 | 已上架的课会怎样 |
 *   |---|---|---|
 *   | `POST /subjects`（建题） | `course_draft` | 报「课程不存在，请先保存课程基本信息」 |
 *   | `POST /courses/subjects/save/{id}`（绑题） | `course_draft` + `course_catalogue_draft` | 同上 |
 *   | `upShelf`（上架） | —— | **把草稿整份删掉** → **上架后再也改不了题** |
 *
 *   → 所以：
 *     1. 题库页"新建题目"必须**先选一门草稿态的课**（页面按 `status` 提示）；
 *     2. 建课向导里配题必须排在上架之前，UI 要**如实说明**"上架后题目不可改"，
 *        而不是给一个点了会报错的按钮。
 */
import type { PageDTO, SubjectForm, SubjectVO } from '@/types/admin'
import { http } from '@/utils/request'

/** 题目分页（管理端；含答案与解析） */
export function getSubjectPage(query: {
  pageNo?: number
  pageSize?: number
  courseId?: string
  name?: string
}): Promise<PageDTO<SubjectVO>> {
  return http.get<PageDTO<SubjectVO>>('/cs/subjects/page', { params: query })
}

/**
 * 题目详情（含答案与解析）。
 *
 * ★ 与列表项同构，但**必须单独调**：列表接口给的是分页场景下的字段，
 *   编辑表单要用 `answers`（答案序号数组）直接回填勾选状态，
 *   靠 `answer` 原文手动 split 容易在"多选 + 空格 + 中文逗号"上翻车。
 */
export function getSubject(id: string): Promise<SubjectVO> {
  return http.get<SubjectVO>(`/cs/subjects/${id}`)
}

/**
 * 新建题目。
 *
 * ⚠️ `options` 的规则（`SubjectFormDTO` 的 `@Schema` 原文）：
 *   选择题 **2~10 个**；**判断题不传**（后端按 1-正确 / 2-错误 处理）。
 */
export function createSubject(body: SubjectForm): Promise<void> {
  return http.post<void>('/cs/subjects', body)
}

/** 修改题目（走 `PUT /subjects/{id}`；body 里的 id 可以不带，以路径为准） */
export function updateSubject(id: string, body: SubjectForm): Promise<void> {
  return http.put<void>(`/cs/subjects/${id}`, body)
}

/** 删除题目（**已被训练动作关联时会被后端拒绝** —— 先解绑再删） */
export function deleteSubject(id: string): Promise<void> {
  return http.delete<void>(`/cs/subjects/${id}`)
}
