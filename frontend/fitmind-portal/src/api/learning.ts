import type { PageDTO } from '@/types/course'
import type {
  LearningLesson,
  LearningPlanPage,
  LearningProgress,
  PointsBoard,
  PointsBoardSeason,
  PointsStatistics,
  SignRecord,
  SignResult,
} from '@/types/learning'
import { http } from '@/utils/request'

/**
 * 学习域接口（fm-learning，网关前缀 `/ls`）
 *
 * 覆盖：课表（我的课程）· 学习进度与上报 · 训练计划 · 签到 · 积分榜
 *
 * ⚠️ **错误码有两种形态，别混**（实测出来的）：
 *   - `401 / 403` → **HTTP 状态码**（鉴权层拦下来的，`request.ts` 已统一处理）
 *   - 业务校验失败（如"每周训练频率需在 1~7 之间"）→ **HTTP 200 + `code: 400`**
 *   两套并存是这个项目的既有实现，前端两边都要认。
 */

// ==================== 课表（我的课程）====================

/**
 * 报名（加入我的课程）。
 *
 * ★ 免费课走这里、**不走下单** —— 后端 `validateCourses` 明确拒绝免费课下单
 * （原话："免费课不该走下单，直接报名，否则会造出一堆 0 元订单"）。
 * 付费课则由支付成功后的 MQ 自动写课表。
 * 后端幂等：重复报名不会报错。
 */
export function enrollLesson(courseId: string): Promise<void> {
  return http.post<void>(`/ls/lessons/${courseId}`)
}

/** 移除我的课程（退课）。★ 只动课表，不影响订单与已付金额 */
export function removeLesson(courseId: string): Promise<void> {
  return http.delete<void>(`/ls/lessons/${courseId}`)
}

/** 我的课程分页（status：0-未学习 1-学习中 2-已学完 3-已过期；不传 = 全部） */
export function pageMyLessons(params: {
  pageNo: number
  pageSize: number
  status?: number
}): Promise<PageDTO<LearningLesson>> {
  return http.get<PageDTO<LearningLesson>>('/ls/lessons/page', { params })
}

/**
 * 按课程 id 查我的课表。
 *
 * ⚠️ 未报名时后端会报错（不是返回 null），调用方要 try 住 ——
 * 学习页用它拿 `lessonId`（★ 上报进度必须要它，`courseId` 不行）。
 */
export function getLessonByCourse(courseId: string): Promise<LearningLesson> {
  return http.get<LearningLesson>(`/ls/lessons/${courseId}`)
}

/** 当前正在学的课程（没有则返回 null） */
export function getCurrentLesson(): Promise<LearningLesson | null> {
  return http.get<LearningLesson | null>('/ls/lessons/now')
}

// ==================== 学习进度 ====================

/** 某门课的学习进度（含每个动作的断点与完成状态） */
export function getCourseProgress(courseId: string): Promise<LearningProgress> {
  return http.get<LearningProgress>(`/ls/learning-records/course/${courseId}`)
}

export interface LearningRecordForm {
  /** ★ 课表 id（`lesson.id`），不是课程 id */
  lessonId: string
  /** 训练动作 id（训练目录里 `type=2` 的节点 id） */
  sectionId: string
  /** 当前播放进度，单位秒 */
  moment: number
  /** 视频总时长，单位秒 */
  duration: number
}

/**
 * 上报学习进度。
 *
 * ★★ **后端"上报即完成"**：实测上报 `moment=60 / duration=120`（只看了一半），
 *   返回的进度里 `finished` 已经是 `true` —— 它不判断"看了百分之多少"。
 *
 *   所以**调用时机很关键** —— 前端不能跟着 `timeupdate` 频繁上报，
 *   否则用户点开视频几秒，这个动作就算"练完了"，进度条会骗人。
 *   本项目的做法（见 `LearnView.vue`）：只在
 *   **暂停 / 切换动作 / 离开页面 / 手动标记完成** 时上报，
 *   兼顾"保存断点"与"别乱算完成"。
 */
export function reportLearningRecord(form: LearningRecordForm): Promise<void> {
  return http.post<void>('/ls/learning-records', form)
}

// ==================== 训练计划 ====================

/** 我的训练计划（含本周完成动作数 / 本周计划总频次） */
export function getMyPlans(): Promise<LearningPlanPage> {
  return http.get<LearningPlanPage>('/ls/lessons/plans')
}

/**
 * 创建 / 更新训练计划。
 *
 * ⚠️ `freq` 必须 **1~7**：后端会校验，超出时返回 `code: 400`
 * + "每周训练频率需在 1~7 之间"。前端用 `el-input-number` 卡住范围，
 * 但不把它当唯一防线 —— 服务端的校验才是准的。
 */
export function savePlan(courseId: string, freq: number): Promise<void> {
  return http.post<void>('/ls/lessons/plans', { courseId, freq })
}

// ==================== 签到 ====================

/** 本月签到记录（已签日期 + 连续天数） */
export function getSignRecord(): Promise<SignRecord> {
  return http.get<SignRecord>('/ls/sign-records')
}

/**
 * 每日签到。
 *
 * 返回本次得分明细（基础分 + 奖励分），界面要把这个数展示出来 ——
 * 用户点"签到"最想知道的就是"我得了多少分"。
 * 重复签到会抛业务异常，把后端给的文案展示出来即可。
 */
export function signIn(): Promise<SignResult> {
  return http.post<SignResult>('/ls/sign-records')
}

// ==================== 积分榜 ====================

/**
 * 积分榜。
 *
 * ⚠️★ 返回值里**没有 `total`**（实测 `{points, rank, boardList}`），
 *   所以不能按常规分页器那样算总页数。当前用
 *   `boardList.length < pageSize` 判断"是否还有下一页"。
 *
 * @param season 赛季 id；`0` 或不传 = 当前赛季（Redis 实时榜）
 */
export function getPointsBoard(params: {
  pageNo?: number
  pageSize?: number
  season?: number
}): Promise<PointsBoard> {
  return http.get<PointsBoard>('/ls/boards', { params })
}

/** 历史赛季列表 */
export function getPointsSeasons(): Promise<PointsBoardSeason[]> {
  return http.get<PointsBoardSeason[]>('/ls/boards/seasons/list')
}

/** 今日各类型积分明细（1 学习 / 2 签到 / 3 问答 / 4 笔记 / 5 小测） */
export function getTodayPoints(): Promise<PointsStatistics[]> {
  return http.get<PointsStatistics[]>('/ls/boards/points/today')
}
