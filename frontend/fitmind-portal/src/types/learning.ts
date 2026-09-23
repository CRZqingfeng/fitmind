/**
 * 学习域类型定义（M7-4：我的课程 / 学习进度 / 训练计划 / 签到 / 积分榜）
 *
 * ⚠️ 与交易域同一套约定：**Long 一律是 string**（`JacksonConfig` 全局转的），
 * 凡是 id 都用 `string`（lessonId / courseId / sectionId / mediaId）。
 */

// ==================== 枚举（const 对象，不是 enum —— tsconfig 禁了 enum）====================

/** 课表状态 */
export const LessonStatus = {
  NOT_STARTED: 0,
  LEARNING: 1,
  FINISHED: 2,
  EXPIRED: 3,
} as const

/** 计划状态 */
export const PlanStatus = {
  NONE: 0,
  ONGOING: 1,
} as const

/** 训练目录节点类型 */
export const CataType = {
  DAY: 1,
  SECTION: 2,
} as const

/** 积分类型（出自 `GET /ls/boards/points/today` 的返回） */
export const PointsType = {
  LEARN: 1,
  SIGN: 2,
  QA: 3,
  NOTE: 4,
  QUIZ: 5,
} as const

// ==================== 课表 ====================

export interface LearningLesson {
  /** 课表 id（★ 上报学习进度要用它，不是 courseId） */
  id: string
  courseId: string
  courseName: string
  coverUrl: string | null
  /** 0-未学习 1-学习中 2-已学完 3-已过期 */
  status: number
  statusDesc: string
  weekFreq: number | null
  /** 0-没有计划 1-计划进行中 */
  planStatus: number | null
  learnedSections: number | null
  sections: number | null
  /**
   * ⚠️ **这个字段后端没填，恒为 null**（实测 `GET /ls/lessons/page` 返回 null）。
   * 真正的"上次学到哪"要从 `GET /ls/learning-records/course/{courseId}` 的
   * `latestSectionId` 拿 —— 别在这里找。
   */
  latestSectionId: string | null
  latestLearnTime: string | null
  expireTime: string | null
  createTime: string | null
}

/** 单个训练动作的学习进度 */
export interface LearningRecord {
  sectionId: string
  /** 断点秒数 */
  moment: number | null
  /**
   * ⚠️ 注意语义：后端**只要收到过上报就置为 true**（实测上报 `moment=60/duration=120`，
   * 只看了一半，返回的也是 `finished: true`）。它表达的是"这条动作有学习记录"，
   * 而不是"视频看完了" —— 前端不要按播放比例去理解它。
   */
  finished: boolean | null
  finishTime: string | null
}

export interface LearningProgress {
  lessonId: string
  courseId: string
  status: number
  statusDesc: string
  /** 最近学习的动作 id（★ 播放器"继续学习"就跳到这里） */
  latestSectionId: string | null
  records: LearningRecord[] | null
}

// ==================== 训练计划 ====================

export interface LearningPlan {
  /** 课表 id */
  id: string
  courseId: string
  courseName: string
  coverUrl: string | null
  weekFreq: number | null
  learnedSections: number | null
  sections: number | null
  status: number
  statusDesc: string
}

export interface LearningPlanPage {
  /** 本周已完成的训练动作数 */
  weekFinished: number
  /** 本周计划的训练频率总数（所有进行中计划的 weekFreq 之和） */
  weekTotalPlan: number
  list: LearningPlan[] | null
}

// ==================== 签到 ====================

export interface SignRecord {
  /** 本月已签到的日期（几号）。★ 直接按数字高亮日历，不要自己算日期差 */
  days: number[] | null
  /** 连续签到天数（含跨月续接） */
  continuousDays: number | null
}

export interface SignResult {
  signDays: number
  /** 本次签到奖励积分（不含基础分） */
  rewardPoints: number
  /** 本次合计得分（基础分 + 奖励） */
  totalPoints: number
}

// ==================== 积分 ====================

export interface PointsBoardItem {
  rank: number
  name: string
  points: number
}

export interface PointsBoard {
  points: number
  /** 未上榜为 null */
  rank: number | null
  boardList: PointsBoardItem[] | null
}

/** 今日各类型积分统计 */
export interface PointsStatistics {
  type: number
  typeDesc: string
  points: number
  /** 该类型每日上限（★ 0 = 不限） */
  maxPoints: number
}

export interface PointsBoardSeason {
  id: number
  name: string
  beginTime: string | null
  endTime: string | null
}
