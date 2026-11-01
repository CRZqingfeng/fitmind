import type { PageDTO } from '@/types/course'
import type {
  AiApplyResult,
  AiPreview,
  AiTarget,
  BodyMetric,
  DietLogCreated,
  DietPlanCard,
  DietPlanDetail,
  DietToday,
  Exercise,
  ExerciseDetail,
  Food,
  FoodDict,
  MyPlan,
  PlanCard,
  PlanDetail,
  PlanDict,
  Sport,
  SportDict,
  SportLogResult,
  TodayWorkout,
  WorkoutSession,
} from '@/types/plan'
import { http } from '@/utils/request'

/**
 * 计划域接口（fm-plan，网关前缀 `/pls`）
 *
 * 覆盖：推荐计划 · 计划详情 · 编排（训练日/动作项）· 执行计划 · 今日训练 · 训练执行 · 动作库
 *
 * ⚠️ **错误码有两种形态，别混**（与 `api/learning.ts` 同一套约定）：
 *   - `401 / 403` → **HTTP 状态码**（鉴权层拦下的，`request.ts` 已统一处理）
 *   - 业务校验失败 → **HTTP 200 + `code: 400`**
 *     例：空训练日执行 → `{"code":400,"msg":"「周一练胸」还没有动作，先给它加一个动作再执行"}`
 *
 * ★ 本模块与"课程学习计划"（`/ls/lessons/plans`）**没有任何关系**：
 *   那边是"某门课每周练几次"，这边是"周一练胸：深蹲 4 组 × 8 次 + 组休 90 秒"。
 *   两套接口、两套表、两套页面，不要互相复用。
 */

// ==================== 字典 ====================

/**
 * 字典（分类 / 部位 / 器械 / 目标 / 难度 / 组类型 / 星期）。
 *
 * ★ 页面里的所有枚举都从这里来，不要在 .vue 里写死数组 ——
 * 后端把"背"改成"背部"时，硬编码的筛选会静默失效（点了一点结果都没有，还不报错）。
 */
export function getPlanDict(): Promise<PlanDict> {
  return http.get<PlanDict>('/pls/exercises/dict')
}

// ==================== 动作库 ====================

/**
 * 动作库分页。
 *
 * `category` / `bodyPart` / `equipment` 是 **AND** 关系（左侧分类 + 器械 chips 同时生效）。
 * 左侧"胸/背/腿…"传 `bodyPart`，不是 `category`（力量类动作按部位切）。
 */
export function pageExercises(params: {
  pageNo: number
  pageSize: number
  category?: string
  bodyPart?: string
  equipment?: string
  keyword?: string
}): Promise<PageDTO<Exercise>> {
  return http.get<PageDTO<Exercise>>('/pls/exercises', { params })
}

/** 动作详情。`heat` / `lumbarPressure` / `gifUrl` 后端会返回 null（数据集没有），界面显示"—" */
export function getExercise(id: string): Promise<ExerciseDetail> {
  return http.get<ExerciseDetail>(`/pls/exercises/${id}`)
}

// ==================== 计划 ====================

/** 推荐计划（官方，卡片字段）。`goal` 不传 = 全部 */
export function getRecommendPlans(goal?: string): Promise<PlanCard[]> {
  return http.get<PlanCard[]>('/pls/plans/recommend', { params: goal ? { goal } : {} })
}

/** 我的计划（我执行过的计划 + 练到第几练） */
export function getMyPlans(): Promise<MyPlan[]> {
  return http.get<MyPlan[]>('/pls/plans/mine')
}

/** 今日训练（三态 + 今日/累计分钟数） */
export function getTodayWorkout(): Promise<TodayWorkout> {
  return http.get<TodayWorkout>('/pls/plans/today')
}

/** 计划详情（官方 / 自建共用；`editable` 决定底部是否显示「编辑」） */
export function getPlanDetail(id: string): Promise<PlanDetail> {
  return http.get<PlanDetail>(`/pls/plans/${id}`)
}

/**
 * 创建空计划，返回 planId。
 *
 * ★ 不传 `title` 时后端填「未命名计划」——**前端不要拼这个默认名**：
 * 两边各写一份，用户看到的是"未命名计划"、库里存的是别的，排查时对不上。
 */
export function createPlan(payload: {
  title?: string
  summary?: string
  goal?: string
  difficulty?: number
}): Promise<string> {
  return http.post<string>('/pls/plans', payload)
}

/**
 * 执行本计划（= 加入我的计划，参考 App 没有独立的"加入"按钮）。
 *
 * **后端幂等**：重复调用复用同一条 `user_plan` 并重置开始日期，返回同一个 userPlanId。
 * 空训练日会被拒：`code: 400` + "「xxx」还没有动作，先给它加一个动作再执行"。
 */
export function executePlan(planId: string): Promise<string> {
  return http.post<string>(`/pls/plans/${planId}/execute`)
}

/**
 * 编辑计划（改名 / 描述 / 目标 / 难度）。**不传 / 传空串 = 不改**。
 *
 * 例外：`summary` 传空串 = 真的清空描述。
 * 官方计划不能改（`code:400` + "只能编辑自己创建的计划…"）。
 */
export function updatePlan(
  planId: string,
  payload: {
    title?: string
    summary?: string
    goal?: string
    difficulty?: number
  },
): Promise<void> {
  return http.put<void>(`/pls/plans/${planId}`, payload)
}

// ==================== 编排 ====================

/** 批量添加训练日（一次提交，不是循环调五次：循环会产生 5 个事务，第 4 个失败时前 3 个已落库） */
export function addPlanDays(planId: string, weekdays: number[]): Promise<void> {
  return http.post<void>(`/pls/plans/${planId}/days`, { weekdays })
}

/** 训练日重命名。`name` 传空串 / 不传 = 保持原值（后端刻意不加 @NotBlank） */
export function renamePlanDay(dayId: string, name: string): Promise<void> {
  return http.put<void>(`/pls/plans/days/${dayId}`, { name })
}

/** 往训练日批量加动作（默认 4 组 × 8 次、组休 60 秒；同一动作重复加会被跳过） */
export function addPlanItems(dayId: string, exerciseIds: string[]): Promise<void> {
  return http.post<void>(`/pls/plans/days/${dayId}/items`, { exerciseIds })
}

/** 删除动作项（后端会把后面的序号重排，不留空洞） */
export function removePlanItem(dayId: string, itemId: string): Promise<void> {
  return http.delete<void>(`/pls/plans/days/${dayId}/items/${itemId}`)
}

/**
 * 删除训练日（连同它下面的动作项）。
 *
 * ⚠️ **已经练过的那一天删不掉**：后端返回 `code:400` +
 * "「周一练胸」已经练过 2 次，删掉会让这些记录失去归属…"。
 * 前端不要自己在本地把这一行抹掉 —— 那句话是给用户看的，得弹出来。
 */
export function removePlanDay(dayId: string): Promise<void> {
  return http.delete<void>(`/pls/plans/days/${dayId}`)
}

// ==================== 训练执行 ====================

/**
 * 开始今日训练，返回 sessionId。
 *
 * **后端幂等**：同一天同一训练日只会有一条 session，重复调用返回同一个 id。
 * 前端不必自己判断"今天是不是已经开始过了"。
 */
export function startWorkout(planDayId: string): Promise<string> {
  return http.post<string>('/pls/workouts', { planDayId })
}

/** 执行页数据：训练日 + 动作项 + **已完成的组**（刷新页面靠它恢复打勾状态） */
export function getWorkoutSession(sessionId: string): Promise<WorkoutSession> {
  return http.get<WorkoutSession>(`/pls/workouts/${sessionId}`)
}

/**
 * 记录一组（**每组打完勾就调一次**，不要等全部做完再提交）。
 *
 * 后端按 `session + item + setNo` 覆盖写入 —— 重复提交同一组是"改成这个值"，
 * 不是"多做了一组"。所以可以放心地重试。
 */
export function logWorkoutSet(
  sessionId: string,
  payload: {
    itemId: string
    setNo: number
    weightKg?: number
    reps?: number
    durationSec?: number
  },
): Promise<void> {
  return http.put<void>(`/pls/workouts/${sessionId}/sets`, payload)
}

/** 完成训练（写 duration_sec / total_volume / status=1）。重复调用幂等，不会把时长覆盖成 0 */
export function finishWorkout(sessionId: string): Promise<void> {
  return http.post<void>(`/pls/workouts/${sessionId}/finish`)
}

/**
 * 取消打勾（删一组）。后面的组号由后端前移，保持 1..n。
 *
 * ⚠️ 三个参数缺一不可：只按 `setNo` 删会把"动作A第2组"和"动作B第2组"当成同一行。
 * ⚠️ 训练已完成后不能再改（`code:400` "这次训练已经完成了…"）。
 */
export function removeWorkoutSet(sessionId: string, itemId: string, setNo: number): Promise<void> {
  return http.delete<void>(`/pls/workouts/${sessionId}/items/${itemId}/sets/${setNo}`)
}

// ===================================================================
// M11-3 饮食侧接口（同一个服务就一个 api 文件，不新建 api/diet.ts）
//
// ⚠️ 与 P0 同一套错误形态：401/403 是 HTTP 状态码；业务校验失败是 HTTP 200 + code 400。
// ★ 前端**不算**热量 / 预算 / 消耗：这三个数字全部读后端。
// ===================================================================

/** 今日汇总（摄入 / 预算 / 还可以吃 / 三大营养素 / 四餐明细） */
export function getDietToday(date?: string): Promise<DietToday> {
  return http.get<DietToday>('/pls/diet/today', { params: date ? { date } : {} })
}

/** 推荐饮食计划（卡片字段） */
export function getDietRecommendPlans(): Promise<DietPlanCard[]> {
  return http.get<DietPlanCard[]>('/pls/diet/plans/recommend')
}

/** 饮食计划详情。`day` 不传 = 返回全部天（前端切 tab 不用再请求） */
export function getDietPlanDetail(id: string, day?: number): Promise<DietPlanDetail> {
  return http.get<DietPlanDetail>(`/pls/diet/plans/${id}`, { params: day ? { day } : {} })
}

/**
 * 记一份食物。
 *
 * ★ 不传 kcal：热量由后端按 `food 单价 × 折算克数` 算。
 * ★ 同一天同一餐同一食物同一单位再记一次会**合并**（后端六列一起累加），
 *   返回里的 `merged / message` 用来提示"已累加为 100g"。
 */
export function createDietLog(payload: {
  date?: string
  mealType: number
  foodId: string
  amount: number
  unit: string
}): Promise<DietLogCreated> {
  return http.post<DietLogCreated>('/pls/diet/logs', payload)
}

/** 删一条饮食记录（删完要重新拉 /diet/today，不要本地减数） */
export function removeDietLog(id: string): Promise<void> {
  return http.delete<void>(`/pls/diet/logs/${id}`)
}

/**
 * 食物库分页。
 *
 * `scope`：common 常见 / favorite 我的收藏 / mine 我添加的 —— 这是**入口**，不是分类。
 * `category`：主食/蔬果/… —— 这是**分类**。两者可以叠加（收藏里的主食）。
 */
export function pageFoods(params: {
  pageNo: number
  pageSize: number
  scope?: string
  category?: string
  keyword?: string
}): Promise<PageDTO<Food>> {
  return http.get<PageDTO<Food>>('/pls/foods', { params })
}

/** 食物库字典（分类只含有数据的；entries 标明每项是入口还是分类） */
export function getFoodDict(): Promise<FoodDict> {
  return http.get<FoodDict>('/pls/foods/dict')
}

/** 创建自定义食物，返回 foodId */
export function createFood(payload: {
  name: string
  category: string
  kcalPer100g: number
  proteinPer100g?: number
  carbPer100g?: number
  fatPer100g?: number
  units?: { unit: string; gram: number }[]
  emoji?: string
}): Promise<string> {
  return http.post<string>('/pls/foods', payload)
}

/** 删除自定义食物（只能删自己建的） */
export function removeFood(id: string): Promise<void> {
  return http.delete<void>(`/pls/foods/${id}`)
}

/** 收藏（重复收藏幂等） */
export function favoriteFood(id: string): Promise<void> {
  return http.post<void>(`/pls/foods/${id}/favorite`)
}

/** 取消收藏（没收藏过也返回成功） */
export function unfavoriteFood(id: string): Promise<void> {
  return http.delete<void>(`/pls/foods/${id}/favorite`)
}

/** 运动项目分页 */
export function pageSports(params: {
  pageNo: number
  pageSize: number
  category?: string
  keyword?: string
}): Promise<PageDTO<Sport>> {
  return http.get<PageDTO<Sport>>('/pls/sports', { params })
}

/** 运动字典（entries 里有「动作库」这个 kind=link 的跳转入口） */
export function getSportDict(): Promise<SportDict> {
  return http.get<SportDict>('/pls/sports/dict')
}

/**
 * 记一次运动，返回 {logId, kcal}。
 *
 * ★ 返回的 kcal **只用于弹层显示"本次消耗 XXX 千卡"**；
 *   首页的"还可以吃"不会因为它变（口径 1：运动消耗不抵扣预算）。
 */
export function createSportLog(payload: {
  date?: string
  sportId: string
  durationMin: number
}): Promise<SportLogResult> {
  return http.post<SportLogResult>('/pls/sport/logs', payload)
}

/** 删一条运动记录 */
export function removeSportLog(id: string): Promise<void> {
  return http.delete<void>(`/pls/sport/logs/${id}`)
}

/**
 * 记录体重（kg）。
 *
 * ★ 界面上填的是"斤"，提交前用 `utils/format.ts` 的 `toKg()` 换回 kg —— 唯一换算点。
 * ★ 第一次必须带 heightCm；之后可以只传体重（后端沿用上一次身高）。
 */
export function recordBodyMetric(payload: {
  date?: string
  heightCm?: number
  weightKg: number
  targetWeightKg?: number
  targetDate?: string
}): Promise<BodyMetric> {
  return http.post<BodyMetric>('/pls/body/metrics', payload)
}

/** 当前体征（没有则尝试用用户档案兜底；needProfile=true 表示连档案也没有） */
export function getLatestBodyMetric(): Promise<BodyMetric> {
  return http.get<BodyMetric>('/pls/body/metrics/latest')
}

// ==================== AI 定制计划（M11-4 / P2） ====================

/**
 * 目标推算（当前 → 目标、预计达成日、7 天折线）。
 *
 * 失败形态：`needProfile=true` 时不会 4xx，而是返回一堆 null —— 页面显示"先记录体重"。
 */
export function aiTarget(): Promise<AiTarget> {
  return http.get<AiTarget>('/pls/plans/ai/target')
}

/**
 * 生成预览（写两份草稿）。
 *
 * ★ 入参就是 `planSkeleton()` 的出参原样（全部可空；空 = 全用规则默认）。
 * ★ 这一步**会写库**（草稿），但草稿不会出现在「我的计划」里 —— 只有 apply 才发布。
 * ★ 业务校验失败是 HTTP 200 + code 400（例如"先记录一次体重"），不是 4xx。
 */
export function aiPreview(payload: {
  skeletonId?: string
  division?: string
  weeklyFrequency?: number
  intensity?: string
  mealSplit?: { mealType: number; ratio: number }[]
  dietAdvice?: string
  trainingAdvice?: string
  targetTip?: string
  degraded?: boolean
}): Promise<AiPreview> {
  return http.post<AiPreview>('/pls/plans/ai/preview', payload)
}

/**
 * 接受预览：草稿发布 + 立刻「执行本计划」。
 *
 * ★ 只传 previewId —— 服务端不认前端传的 planId（否则可以接受别人的草稿）。
 * ★ 幂等：连点两次返回同样的三个 id，不会建两条 user_plan。
 */
export function aiApply(previewId: string): Promise<AiApplyResult> {
  return http.post<AiApplyResult>('/pls/plans/ai/apply', { previewId })
}
