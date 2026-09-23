/**
 * 体测评估 / 动作小测相关类型（M7-6）
 *
 * ★ 三条贯穿全文件的约定（与前几个域一致）：
 *   1. `id` / `userId` 等 Long 字段都被全局序列化成 **string**
 *   2. `BigDecimal`（bmi / whr / leanMass / fatMass / 各围度）是**数字**，不是字符串 ——
 *      Long→String 的规则只管 Long，不管 BigDecimal
 *      （这点很坑：同一个响应里 `id` 是 "26"、`bmi` 是 21.3）
 *   3. 后端字段可能是 `null`，所以用 `| null` 而不是可选 `?`
 */

/** 体测录入表单（`BodyAssessFormDTO`）—— 只有身高体重必填 */
export interface BodyAssessForm {
  height: number | null
  weight: number | null
  /** 以下全为选填；不填就传 undefined，**不要传 null**（后端按"非空"判断） */
  targetWeight?: number
  bodyFatRate?: number
  waist?: number
  chest?: number
  arm?: number
  hip?: number
  /** 训练目标：1-增肌 2-减脂 3-塑形 4-保持 */
  goalType?: number
  /** 体测时间，留空=现在；传值可以**补录**历史体测 */
  assessTime?: string
}

/**
 * 体测报告（`BodyAssessVO`）
 *
 * ★ 这份报告里**所有指标都是后端算的**（BMI、分级、BMR、TDEE、去脂体重、综合评分、
 *   结论文案、训练建议）—— 前端一律只负责"显示"。
 *   理由很实际：这些公式和分级阈值（中国标准 / 性别差异）一旦前端再实现一份，
 *   迟早和后端不一致，而"两处算出来不一样"是最难解释的一类 bug。
 */
export interface BodyAssessReport {
  id: string
  /** 性别：0-男 1-女 2-未知（★ 来自用户档案的快照，不是本次提交的字段） */
  gender: number | null
  height: number | null
  weight: number | null
  targetWeight: number | null
  bodyFatRate: number | null
  waist: number | null
  chest: number | null
  arm: number | null
  hip: number | null
  bmi: number | null
  /** BMI 分级：1-偏瘦 2-正常 3-超重 4-肥胖（中国标准） */
  bmiLevel: number | null
  bmiLevelDesc: string | null
  /** 体脂分级：1-偏低 2-运动员 3-正常 4-偏高 5-肥胖 */
  fatLevel: number | null
  fatLevelDesc: string | null
  /** 腰臀比 */
  whr: number | null
  /** 腰臀比分级：1-正常 2-中心性肥胖 */
  whrLevel: number | null
  whrLevelDesc: string | null
  /** 基础代谢 kcal/天（Mifflin-St Jeor） */
  bmr: number | null
  /** 每日总消耗 kcal/天 */
  tdee: number | null
  leanMass: number | null
  fatMass: number | null
  /** 综合评分 0-100 */
  score: number | null
  goalType: number | null
  /** 一句话结论 */
  summary: string | null
  /** ★ 训练建议（后端生成的长文本：热量目标 + 训练侧重 + 风险提示） */
  suggestion: string | null
  assessTime: string | null
}

/**
 * 体测趋势（`BodyTrendVO`，首次 vs 最新）
 *
 * ⚠️ 只有**一次**体测时，接口仍会返回对象，但 `first` / `latest` 可能相同、
 * 各种 `*Change` 全是 `null` —— 所以前端判断"能不能看趋势"要看 `times >= 2`，
 * 而不是"trend 是不是 null"。（完全没记录时接口返回 `data: null`。）
 */
export interface BodyTrend {
  first: BodyAssessReport | null
  latest: BodyAssessReport | null
  /** 体重变化 kg（负数=下降） */
  weightChange: number | null
  /** 体脂率变化 %（负数=下降） */
  fatRateChange: number | null
  /** 腰围变化 cm（负数=下降） */
  waistChange: number | null
  /** 综合评分变化（**正数=变好**，注意方向和其他几个相反） */
  scoreChange: number | null
  /** 间隔天数 */
  days: number | null
  /** 体测次数 */
  times: number | null
}

/** 训练目标（体测表单用；与课程的 goalType 同一套口径） */
export const BODY_GOAL_TYPES: Record<number, string> = {
  1: '增肌',
  2: '减脂',
  3: '塑形',
  4: '保持',
}

/** 体测指标的"理想区间"提示（前端只做展示文案，判定仍然以后端分级为准） */
export const BMI_LEVELS: Record<number, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> = {
  1: { label: '偏瘦', type: 'warning' },
  2: { label: '正常', type: 'success' },
  3: { label: '超重', type: 'warning' },
  4: { label: '肥胖', type: 'danger' },
}

// ============================================================
// 动作小测（fm-course 的 /cs/practices）
// ============================================================

/** 题型：1-单选 2-多选 3-不定向 4-判断 */
export const SUBJECT_TYPES: Record<number, string> = {
  1: '单选题',
  2: '多选题',
  3: '不定向选择',
  4: '判断题',
}

/** 判断题的"选项"由前端补：后端对判断题不下发 options */
export const JUDGE_OPTIONS = ['正确', '错误']

/**
 * 小测题目（`SubjectSimpleVO`）
 *
 * ★★ 注意这里**没有 `answer` / `analysis`** —— 后端是故意的：
 *   作答前不下发答案与解析，等提交判分后才通过 `PracticeResultVO.details` 给回来。
 *   所以前端也**不能**自己猜答案（判分完全在后端）。
 */
export interface PracticeQuestion {
  id: string
  name: string
  subjectType: number | null
  difficulty: number | null
  /** 选择题的选项数组；**判断题为空**（前端用 JUDGE_OPTIONS 兜底） */
  options: string[] | null
  score: number | null
}

/** 单题判分结果（`PracticeResultVO.Detail`） */
export interface PracticeDetail {
  subjectId: string
  /** 题干（判分后回显，前端不用再去对照） */
  name: string
  correct: boolean | null
  /** 我提交的答案（多选已排序） */
  yourAnswer: string | null
  rightAnswer: string | null
  analysis: string | null
}

/** 小测判分结果（`PracticeResultVO`） */
export interface PracticeResult {
  score: number | null
  totalScore: number | null
  correctCount: number | null
  totalCount: number | null
  details: PracticeDetail[] | null
}

/** 单题作答（提交用） */
export interface PracticeAnswer {
  subjectId: string
  /** 选项序号，多选逗号分隔（如 "1,3"）；判断题 1-正确 2-错误 */
  answer: string
}


// ==================== 动作考核（fm-exam）====================

/**
 * 一条动作考核记录。
 *
 * ★ `videoUrl` 是**带签名的临时地址**（会过期）——
 *   页面别把它缓存太久，过期后 `<video>` 会静默播不动（看起来像"卡住了"）。
 * ★ 管理端接口会多返回 `userName` / `cataName`，学员端接口不返回（都是 null）。
 */
export interface ActionAssessment {
  id: string
  userId: string | null
  /** 学员名（★ 管理端列表用） */
  userName: string | null
  courseId: string | null
  cataId: string | null
  /** 训练动作名（★ 管理端列表用） */
  cataName: string | null
  /**
   * ★ 媒资 id —— 用它现取播放地址（`getPlayUrl(mediaId)`）。
   *
   * ⚠️ 不要指望 `videoUrl`：播放地址是**带签名的临时地址**，没有存库，
   * 所以那个字段一直是 null（M7-10 实测确认）。
   */
  mediaId: string | null
  videoUrl: string | null
  remark: string | null
  /** 1-待评分 2-已评分 */
  status: number | null
  statusDesc: string | null
  /** 教练评分 0-100（未评分时为 null） */
  score: number | null
  comment: string | null
  coachId: string | null
  submitTime: string | null
  reviewTime: string | null
}

/** 提交动作考核 */
export interface ActionSubmitForm {
  /** ★ 必填：动作视频的媒资 id（先走 `uploadMedia` 拿到） */
  mediaId: string
  courseId?: string
  cataId?: string
  /** 自述（选填，例如说一下做这个动作时的感受） */
  remark?: string
}

/** 考核状态（★ 值从后端 `ActionAssessmentVO` 的注释抄，别猜） */
export const ACTION_STATUS = {
  PENDING: 1,
  REVIEWED: 2,
} as const
