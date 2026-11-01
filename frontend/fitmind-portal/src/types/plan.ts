/**
 * 计划域类型定义（M11-2：训练计划编排与执行）
 *
 * ⚠️ 三条与本文件强相关的后端事实（都实测过）：
 *
 * 1. **所有 id 都是 `string`**
 *    后端 `JacksonConfig` 把 `Long` 全局序列化成字符串（实测 `{"data":"1"}`），
 *    因为它们可能超过 JS 的安全整数范围。写成 `number` 的话
 *    `id === 1` 永远为 false —— 而且不报错，只是"点了没反应"。
 *
 * 2. **两种错误形态并存**
 *    `401 / 403` 是 **HTTP 状态码**（`request.ts` 统一处理）；
 *    业务校验失败是 **HTTP 200 + `code: 400`** ——
 *    例如"「周一练胸」还没有动作，先给它加一个动作再执行"。
 *    所以调用方不要只看 catch，记得看 `ApiError.code`。
 *
 * 3. **字典不许前端硬编码**
 *    分类 / 部位 / 器械 / 难度 / 组类型 / 星期全部来自 `getPlanDict()`。
 *    在 .vue 里写死"胸/背/腿"的下场：后端把"背"改成"背部"，
 *    筛选永远查不到东西，而且没有任何报错。
 */

/** 计划来源：0-官方 1-自建 2-AI */
export const PlanSource = {
  OFFICIAL: 0,
  USER: 1,
  AI: 2,
} as const

/** 计划状态：0-草稿 1-已发布 2-已下架 */
export const PlanStatus = {
  DRAFT: 0,
  PUBLISHED: 1,
  OFFLINE: 2,
} as const

/** 我执行的计划状态：0-进行中 1-已完成 2-已放弃 */
export const UserPlanStatus = {
  RUNNING: 0,
  FINISHED: 1,
  GAVE_UP: 2,
} as const

/** 一次训练的状态：0-进行中 1-已完成 2-已放弃 */
export const SessionStatus = {
  RUNNING: 0,
  FINISHED: 1,
  GAVE_UP: 2,
} as const

/**
 * 今日训练三态。
 *
 * ★★ 这三个值**必须分开**，不要合并成 `hasPlan: boolean`：
 *   0 = 还没有执行的计划（引导去挑计划）
 *   1 = 有计划但今天没安排（"休息也是训练的一部分"）
 *   2 = 今天要练（显示训练日 + 开始训练）
 * 合并的后果：用户明明在执行计划，首页却一直喊"还没有计划"，他又去执行了一遍。
 */
export const TodayStatus = {
  NO_PLAN: 0,
  REST: 1,
  TRAIN: 2,
} as const

/** 组类型：0-正式 1-热身 2-拉伸（拉伸组要显示标签） */
export const GroupType = {
  FORMAL: 0,
  WARMUP: 1,
  STRETCH: 2,
} as const

// ==================== 字典 ====================

/** 字典项（值 + 展示标签） */
export interface PlanOption {
  value: string | number
  label: string
}

/** `GET /pls/exercises/dict` */
export interface PlanDict {
  /** 左侧栏分类（热身/拉伸/有氧，不含"力量"——力量类按部位切） */
  categories: string[]
  /** 11 个部位 */
  bodyParts: string[]
  /** 8 类器械（史密斯/悍马机 P0 无数据） */
  equipments: string[]
  goals: string[]
  difficulties: PlanOption[]
  groupTypes: PlanOption[]
  weekdays: PlanOption[]
}

// ==================== 动作库 ====================

/** 动作（列表项） */
export interface Exercise {
  id: string
  name: string
  enName: string | null
  category: string
  bodyPart: string
  equipment: string
  difficulty: number
  imageUrl: string | null
}

/** 动作详情。`heat` / `lumbarPressure` / `gifUrl` 数据集里没有 → null，界面显示"—" */
export interface ExerciseDetail extends Exercise {
  heat: number | null
  lumbarPressure: number | null
  gifUrl: string | null
  primaryMuscles: string[]
  secondaryMuscles: string[]
  steps: string[]
  tips: string | null
}

// ==================== 计划（模板）====================

/** 计划卡片 */
export interface PlanCard {
  id: string
  title: string
  summary: string | null
  coverUrl: string | null
  goal: string
  difficulty: number
  difficultyLabel: string
  trainDaysPerWeek: number
  /** 预估千卡；**0 表示暂无估算**（数据集缺 heat），界面显示"—"而不是 0 */
  kcalEstimate: number
  equipment: string[]
  source: number
  /** 卡片副标题，后端拼好：一周5练 · 增肌 · 进阶 */
  subtitle: string
}

/** 我的计划（"我的计划" tab） */
export interface MyPlan {
  id: string
  title: string
  coverUrl: string | null
  goal: string
  trainDaysPerWeek: number
  dayCount: number
  finishedDays: number
  status: number
  statusLabel: string
  startDate: string
  currentDayIndex: number
  /** "已练 3/5 天"，后端拼 */
  progressText: string
}

/** 训练日里的动作项 */
export interface PlanDayItem {
  id: string
  exerciseId: string
  exerciseName: string
  imageUrl: string | null
  category: string | null
  bodyPart: string | null
  equipment: string | null
  sort: number
  groupType: number
  groupTypeLabel: string
  sets: number
  reps: number
  /** 计时型单组秒数（平板/跳绳），次数型为 0 */
  durationSec: number
  weightKg: number | null
  /** 后端算好的"斤"（= kg × 2）*/
  weightJin: number | null
  restSec: number
  /** "共4组·每组8次·组休60秒"，后端拼 */
  prescription: string
}

/** 训练日 */
export interface PlanDay {
  id: string
  dayIndex: number
  weekday: number | null
  weekdayLabel: string
  name: string
  itemCount: number
  estMinutes: number
  /** "7个动作·预计43分钟" / "还没有动作"，后端拼 */
  summary: string
  items: PlanDayItem[]
}

/** 计划详情 */
export interface PlanDetail {
  id: string
  title: string
  summary: string | null
  coverUrl: string | null
  goal: string
  difficulty: number
  difficultyLabel: string
  trainDaysPerWeek: number
  kcalEstimate: number
  equipment: string[]
  source: number
  official: boolean
  /** 官方计划不可编辑 */
  editable: boolean
  /** 我对这个计划的状态：null-没执行过 */
  userPlanStatus: number | null
  userPlanId: string | null
  days: PlanDay[]
}

// ==================== 今日训练 ====================

/** 今日训练卡（`GET /pls/plans/today`） */
export interface TodayWorkout {
  hasPlan: boolean
  status: number
  statusLabel: string
  /** 后端拼好的下一步提示，直接显示 */
  tips: string
  planId: string | null
  planTitle: string | null
  planDayId: string | null
  dayName: string | null
  dayIndex: number | null
  exerciseCount: number
  estMinutes: number
  /** 今日已训练分钟（后端算，前端不重算） */
  todayMinutes: number
  /** 累计训练分钟 */
  totalMinutes: number
  sessionId: string | null
  sessionFinished: boolean
}

// ==================== 训练执行（日志）====================

/** 一组完成情况 */
export interface WorkoutSet {
  setNo: number
  weightKg: number | null
  reps: number
  durationSec: number
  done: number
}

/** 执行页里的一个动作 */
export interface WorkoutItem {
  itemId: string
  exerciseId: string
  exerciseName: string
  imageUrl: string | null
  category: string | null
  groupType: number
  groupTypeLabel: string
  sets: number
  reps: number
  durationSec: number
  weightKg: number | null
  /** ★ 倒计时用这个，不要在前端写死 60 */
  restSec: number
  prescription: string
  doneSets: number
  setLogs: WorkoutSet[]
}

/** 一次训练（执行页数据） */
export interface WorkoutSession {
  sessionId: string
  sessionDate: string
  status: number
  statusLabel: string
  planId: string | null
  planTitle: string | null
  planDayId: string | null
  planDayName: string | null
  startTime: string
  endTime: string | null
  durationSec: number
  kcal: number
  /** 总容量 = Σ(kg × 次数)。★ 只转 Long，BigDecimal 仍是数字 */
  totalVolume: number
  doneSets: number
  totalSets: number
  items: WorkoutItem[]
}

// ===================================================================
// M11-3 饮食侧类型（P1）
// ===================================================================

/** 餐次（记录侧只有这 4 个；计划侧的「三次加餐」靠 mealIndex 区分） */
export const MealType = {
  BREAKFAST: 1,
  LUNCH: 2,
  DINNER: 3,
  SNACK: 4,
} as const

/** 三大营养素进度。current / target 都由后端算好，前端不参与计算 */
export interface Macro {
  key: string
  label: string
  current: number
  target: number
  unit: string
}

/** 一条饮食记录 */
export interface DietLogItem {
  /** 记录 id（删除用） */
  id: string
  foodName: string
  amount: number
  unit: string
  gram: number
  kcal: number
  emoji: string | null
}

/** 一餐 */
export interface DietMeal {
  mealType: number
  label: string
  kcal: number
  items: DietLogItem[]
}

/**
 * 今日汇总（`GET /pls/diet/today`）。
 *
 * ★ `remainKcal` 可能是**负数**（吃超了）—— 界面上要显示"已超出 X"，
 * 不要 max(0) 掉：0 会让人以为刚好达标。
 * ★ `budgetKcal = 0` 且 `needProfile = true` 时表示"还没记录体重"，此时 `remainKcal` 是 null。
 */
export interface DietToday {
  date: string
  intakeKcal: number
  budgetKcal: number
  remainKcal: number | null
  weightKg: number | null
  bmi: number | null
  targetWeightKg: number | null
  /** 运动消耗：只展示，不抵扣预算 */
  sportKcal: number
  sportNote: string
  needProfile: boolean
  macros: Macro[]
  meals: DietMeal[]
}

/** 饮食计划卡片 */
export interface DietPlanCard {
  id: string
  title: string
  summary: string | null
  coverUrl: string | null
  goal: string
  cycleDays: number
  kcalPerDay: number
  subtitle: string
  tags: string[]
}

/** 饮食计划详情里的一份食物 */
export interface DietPlanItem {
  foodId: string
  foodName: string
  emoji: string | null
  amount: number
  unit: string
  gram: number
  kcal: number
  /** "50g" / "1 碗(200g)"，后端拼好 */
  amountText: string
}

/** 饮食计划详情里的一餐 */
export interface DietPlanMeal {
  mealType: number
  mealIndex: number
  /** 早餐 / 加餐① / 加餐② … */
  label: string
  /** "07:30"（后端切好，前端不要再切） */
  mealTime: string | null
  kcal: number
  items: DietPlanItem[]
}

/** 饮食计划详情里的第 N 天 */
export interface DietPlanDay {
  dayIndex: number
  kcalTotal: number
  proteinG: number
  carbG: number
  fatG: number
  meals: DietPlanMeal[]
}

/** 饮食计划详情 */
export interface DietPlanDetail {
  id: string
  title: string
  summary: string | null
  coverUrl: string | null
  goal: string
  cycleDays: number
  kcalPerDay: number
  /** "一周期5天" */
  cycleText: string
  days: DietPlanDay[]
}

/** 食物单位 */
export interface FoodUnit {
  unit: string
  gram: number
}

/** 食物（饮食库卡片） */
export interface Food {
  id: string
  name: string
  alias: string | null
  category: string
  kcalPer100g: number
  proteinPer100g: number | null
  carbPer100g: number | null
  fatPer100g: number | null
  emoji: string | null
  defaultUnit: string
  /** ★ 点＋之后能选哪几个单位，由这个数组决定（前端不写死） */
  units: FoodUnit[]
  favorite: boolean
  mine: boolean
  /** "338 千卡/100g" */
  kcalText: string
}

/** 食物库左侧入口：entry=过滤 search=搜索框 category=分类 */
export interface FoodDictEntry {
  key: string
  label: string
  kind: 'entry' | 'search' | 'category'
}

/** 食物库字典 */
export interface FoodDict {
  /** 只含有数据的分类（数据集没有中式/西式菜肴时就不会出现） */
  categories: string[]
  mealTypes: { value: number; label: string }[]
  entries: FoodDictEntry[]
}

/** 运动项目 */
export interface Sport {
  id: string
  name: string
  category: string
  kcalPer30min: number
  /** "260 千卡/30分钟" */
  kcalText: string
  tags: string[]
}

/** 运动字典 */
export interface SportDict {
  categories: string[]
  entries: { key: string; label: string; kind: 'entry' | 'search' | 'link' | 'category' }[]
}

/** 记运动的结果（kcal 只用于弹层即时显示，不要拿去改首页总数） */
export interface SportLogResult {
  logId: string
  kcal: number
  text: string
}

/** 记饮食的结果 */
export interface DietLogCreated {
  logId: string
  kcal: number
  amount: number
  unit: string
  gram: number
  /** 是否与已有记录合并了 */
  merged: boolean
  /** 合并后该行的总份量 */
  totalAmount: number
  /** "已累加为 100克" —— 后端拼好 */
  message: string
}

/** 体征（体重 / 预算 / 三大营养素目标） */
export interface BodyMetric {
  recordDate: string | null
  heightCm: number | null
  weightKg: number | null
  targetWeightKg: number | null
  targetDate: string | null
  bmi: number | null
  kcalBudget: number | null
  proteinG: number | null
  carbG: number | null
  fatG: number | null
  /** true = 连用户档案里都没有体重，前端应弹「记录体重」 */
  needProfile: boolean
  /** 来自 fm-user 的档案兜底（不是本模块记的） */
  fromProfile: boolean
}

// ==================== AI 定制计划（M11-4 / P2） ====================

/**
 * 目标推算（结果页 ① 目标卡）。
 *
 * ★ 所有数字都是后端算好的：前端**不算**达成日期、不算每周变化量、不算折线。
 *   前端只做一件事：把 kg 乘 2 显示成"斤"（唯一换算点 `utils/format.ts`）。
 */
export interface AiTarget {
  currentWeightKg: number | null
  targetWeightKg: number | null
  currentBmi: number | null
  weeklyDeltaKg: number | null
  /** 0 = 已达成；null = 缺目标体重，无法推算 */
  daysToGoal: number | null
  targetDate: string | null
  /** 永远 7 个点；缺目标时是一条水平线 */
  forecast: AiForecastPoint[]
  /** true = 连体重都没有 → 引导去记录体重，而不是显示 0 */
  needProfile: boolean
  fromProfile: boolean
}

export interface AiForecastPoint {
  date: string
  weightKg: number
}

export interface AiMacroItem {
  key: 'protein' | 'carb' | 'fat'
  label: string
  gram: number
  kcal: number
  percent: number
}

export interface AiEvaluation {
  heightCm: number | null
  weightKg: number | null
  targetWeightKg: number | null
  bmi: number | null
  bmiLabel: string | null
  /** BMI 在刻度条上的位置 0~1（后端算，前端只画） */
  bmiPercent: number | null
}

export interface AiDistribution {
  strength: number
  cardio: number
  flexibility: number
}

export interface AiAdvice {
  dietAdvice: string | null
  trainingAdvice: string | null
  targetTip: string | null
  /** true = AI 那步失败/超时，已用规则骨架；页面提示"建议稍后重新生成" */
  degraded: boolean
  /** 服务端的补充说明（动作库不够之类的实话） */
  notes: string[]
}

/** 预览结果（结果页 4 个区块的全部数据） */
export interface AiPreview {
  previewId: string
  planDraftId: string
  dietDraftId: string
  target: AiTarget
  evaluation: AiEvaluation
  macros: { kcalBudget: number; items: AiMacroItem[] }
  planTitle: string
  difficultyLabel: string
  dietPlanTitle: string
  dietCycleDays: number
  dietDays: DietPlanDay[]
  trainDays: PlanDay[]
  distribution: AiDistribution
  advice: AiAdvice
}

/** fm-aigc 生成的骨架（AI 只出这一份东西，不含任何 id） */
export interface PlanSkeleton {
  skeletonId: string
  /** true = 连体重都没有，前端应引导去补档案/记体重 */
  needProfile: boolean
  degraded: boolean
  division: string
  weeklyFrequency: number
  intensity: 'LOW' | 'MEDIUM' | 'HIGH'
  mealSplit: { mealType: number; ratio: number }[]
  dietAdvice: string
  trainingAdvice: string
  targetTip: string
}

/** 接受预览的结果（三个 id 都有 = 已经真的加入我的计划了） */
export interface AiApplyResult {
  planId: string
  userPlanId: string
  dietPlanId: string
}
