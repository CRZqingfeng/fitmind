import type { PageDTO } from '@/types/course'
import type {
  ActionAssessment,
  ActionSubmitForm,
  BodyAssessForm,
  BodyAssessReport,
  BodyTrend,
  PracticeAnswer,
  PracticeQuestion,
  PracticeResult,
} from '@/types/assessment'
import { http } from '@/utils/request'

/**
 * 评估域接口（M7-6）
 *
 * 两个服务、两条前缀：
 *   · **体测**在 fm-exam → `/es/assessments/**`
 *   · **动作小测**在 fm-course → `/cs/practices/**`
 *     （小测挂在训练动作上，本质是课程内容，所以归 fm-course；
 *      而"动作**考核**"——学员录视频给教练打分——在 fm-exam，别混淆）
 *
 * ⚠️ 体测的 `latest` / `trend` 在**数据不足时返回 `data: null`**（不是报错）：
 *   · 一次都没测过 → `latest` 是 null
 *   · 只有一次 → `trend` 有对象但各种 change 是 null
 *   所以前端的"空态"判断要看数据本身，不要看 code。
 */

// ==================== 体测（fm-exam）====================

/** 提交一次体测（返回新记录 id） */
export function submitBodyAssess(form: BodyAssessForm): Promise<string> {
  return http.post<string>('/es/assessments', form)
}

/** 最新一次体测报告（★ 没记录时返回 null） */
export function getLatestAssess(): Promise<BodyAssessReport | null> {
  return http.get<BodyAssessReport | null>('/es/assessments/latest')
}

/** 体测趋势：首次 vs 最新（★ 没记录时返回 null；只有一次时 change 字段为 null） */
export function getAssessTrend(): Promise<BodyTrend | null> {
  return http.get<BodyTrend | null>('/es/assessments/trend')
}

/** 体测历史（分页） */
export function pageAssess(params: { pageNo: number; pageSize: number }): Promise<PageDTO<BodyAssessReport>> {
  return http.get<PageDTO<BodyAssessReport>>('/es/assessments/page', { params })
}

/** 单次体测详情 */
export function getAssessDetail(id: string): Promise<BodyAssessReport> {
  return http.get<BodyAssessReport>(`/es/assessments/${id}`)
}

// ==================== 动作小测（fm-course）====================

/**
 * 拉取某个训练动作的小测题目
 *
 * ★ 返回的题目里**没有答案与解析**（后端故意的，作答前不下发）。
 *   所以判分只能提交给后端做，前端不要试图本地判分。
 */
export function getPractice(cataId: string): Promise<PracticeQuestion[]> {
  return http.get<PracticeQuestion[]>(`/cs/practices/${cataId}`)
}

/**
 * 提交小测作答并判分（答对的题会得积分）
 *
 * ★ `answers[].answer` 是**选项序号**（从 1 开始），多选逗号分隔。
 *   这里由页面负责把 UI 的选中项规范化成这个格式（多选要排序，避免 "3,1" 被判错）。
 */
export function submitPractice(cataId: string, answers: PracticeAnswer[]): Promise<PracticeResult> {
  return http.post<PracticeResult>('/cs/practices/submit', { cataId, answers })
}


// ==================== 动作考核（fm-exam）====================

/**
 * 提交动作考核。
 *
 * ★ 前置：先用 `api/media.ts` 的 `uploadMedia(file)` 把视频传上去拿到 `mediaId`。
 *   后端 `ActionSubmitDTO.mediaId` 是 `@NotNull`（"请先上传动作视频"）。
 */
export function submitActionAssess(form: ActionSubmitForm): Promise<string> {
  return http.post<string>('/es/action-assessments', form)
}

/** 我的动作考核记录（分页） */
export function pageMyActionAssess(params: {
  pageNo: number
  pageSize: number
}): Promise<PageDTO<ActionAssessment>> {
  return http.get<PageDTO<ActionAssessment>>('/es/action-assessments/page', { params })
}

/** 动作考核详情（含教练评分与评语） */
export function getActionAssessDetail(id: string): Promise<ActionAssessment> {
  return http.get<ActionAssessment>(`/es/action-assessments/${id}`)
}
