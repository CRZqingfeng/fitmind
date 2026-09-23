/**
 * 课程 / 搜索相关类型
 *
 * ★ 三条贯穿全文件的硬约定（都是实测确认的，见 M7 方案 §十二）：
 *   1. **后端 Long 一律序列化成 string**（`JacksonConfig` 全局配了
 *      `serializerByType(Long.class, ToStringSerializer.instance)`），
 *      所以所有 id 类型都是 `string`，不是 `number`。
 *   2. **价格单位是分**（`price: 19900` = 199 元），展示前要换算。
 *   3. 后端字段可能是 `null`（不是"键不存在"），所以用 `| null` 而不是 `?`。
 *
 * ⚠️ 另外记录两处**后端自身的不一致**（前端只能各自适配，别想当然）：
 *   · `free`：搜索 VO 里是 **Boolean**，课程全文里是 **Integer（1-免费 2-付费）**
 *   · 动作数量：搜索 VO 叫 `sections`，课程全文叫 `sectionNum`
 */

/**
 * 分页结果（`com.fitmind.common.domain.dto.PageDTO`）
 *
 * ★★ `total` / `pages` 是**字符串**，不是数字。
 *   它们来自 MyBatis-Plus 的 `long`，被全局的 Long→String 序列化规则转成了字符串
 *   （实测：`{"total":"8","pages":"1","list":[...]}`）。
 *
 *   ⚠️ 这里**必须如实声明成 string**。第一版声明成 `number` 时 TS 完全放行，
 *      于是写出了 `page.total + 1` —— 运行期是 `"7" + 1 === "71"`（字符串拼接，不报错），
 *      断言永远不相等，而报错信息里只看得到"页面 null"，**看不出是类型问题**。
 *      ★ 教训：**一个不准确的类型声明比没有类型更危险**，因为它让 TS 帮你放过错误。
 *
 *   → 需要参与运算/传给 el-pagination 时，用 `utils/format.ts` 的 `pageTotal()` 转一次。
 */
export interface PageDTO<T> {
  total: string
  pages: string
  list: T[]
}

/** 课程检索结果（fm-search 的 `CourseSearchVO`） */
export interface CourseSearchVO {
  id: string
  name: string
  /**
   * ★ 关键词命中时由 ES 高亮生成，命中词被 `<em>` 包裹。
   * **不能直接 v-html** —— 课程名是用户可输入内容，直接用等于开 XSS 口子。
   * 用 `utils/format.ts` 的 `safeHighlight()` 渲染。
   */
  highlightName: string | null
  coverUrl: string | null
  /** 分类路径，如「增肌 > 胸部 > 哑铃」 */
  categoryPath: string | null
  /** ⚠️ 搜索侧是布尔 */
  free: boolean | null
  /** 单位：分 */
  price: number | null
  /** 课程类型：1-训练计划课 2-单动作教学 3-理论饮食课 */
  type: number | null
  /** 评分（百分制） */
  score: number | null
  /** 报名人数 */
  sold: number | null
  /** ⚠️ 搜索侧叫 sections（动作数量） */
  sections: number | null
  teacherNames: string[] | null
  /** ⚠️ 搜索接口返回的是**数组** `[2026,9,18,20,0,7]`，不是 ISO 字符串 —— 见 `formatDateTime()` */
  publishTime: string | number[] | null
  /** 训练目标：1-增肌 2-减脂 3-塑形 4-保持 */
  goalType: number | null
  /** 适用水平：1-零基础 2-初级 3-中级 4-高级 */
  trainingLevel: number | null
  equipment: string | null
  totalDays: number | null
  /** 视频总时长，单位秒 */
  mediaDuration: number | null
  actionNames: string[] | null
}

/** 课程检索条件（fm-search 的 `CourseSearchQuery`，继承 `PageQuery`） */
export interface CourseSearchQuery {
  pageNo?: number
  pageSize?: number
  keyword?: string
  categoryIdLv1?: string
  categoryIdLv2?: string
  categoryIdLv3?: string
  /** 只看免费 */
  free?: boolean
  type?: number
  goalType?: number
  trainingLevel?: number
  /** 最低价格，单位分 */
  minPrice?: number
  /** 最高价格，单位分 */
  maxPrice?: number
  /** 排序字段 */
  rankBy?: 'default' | 'price' | 'publishTime' | 'sold'
  /** 是否升序（仅对 price/publishTime/sold 生效） */
  rankAsc?: boolean
}

/** 课程分类（fm-course 的 `CategoryVO`） */
export interface CategoryVO {
  id: string
  name: string
  /** 父分类 id，一级为 '0' */
  parentId: string
  /** 层级：1-训练目标 2-训练部位 3-器械场景 */
  level: number
  priority: number | null
  /** 状态：1-启用 2-禁用 */
  status: number
  statusDesc: string | null
  courseNum: number | null
  children: CategoryVO[] | null
  createTime: string | null
}

/**
 * 课程基本信息（fm-course 的 `CourseBaseInfoVO`，由 `/cs/courses/baseInfo/{id}` 返回）
 *
 * ★ 它是 `CourseFullDTO`（`/cs/courses/internal/{id}/full`）的**超集** ——
 *   比后者多了课程正文四段（介绍 / 适合人群 / 详情 / 注意事项）与器械清单、预计消耗。
 *   所以详情页用它**一个**接口就够，不必再拼 `full`。
 *
 * ⚠️ 代价：它是"管理端语义"的接口，**未上架的课程也会返回**
 *   （`fromDraft=true` 或 `status != 2`）。用户端必须自己判断，
 *   否则会把编辑中的草稿课展示给用户。
 */
export interface CourseBaseInfoVO {
  id: string
  name: string
  courseType: number | null
  coverUrl: string | null
  firstCateId: string | null
  secondCateId: string | null
  thirdCateId: string | null
  /** ⚠️ 整数：1-免费 2-付费（与 `CourseSearchVO.free` 的布尔不一致） */
  free: number | null
  /** 单位：分 */
  price: number | null
  /** 状态：2-已上架 3-下架 4-已完结 */
  status: number | null
  statusDesc: string | null
  /** 建课流程走到第几步（1 基本信息 → 2 目录 → 3 视频 → 5 教练） */
  step: number | null
  /** 课程有效期，单位天 */
  validDuration: number | null
  goalType: number | null
  trainingLevel: number | null
  totalDays: number | null
  weeklyFrequency: number | null
  equipment: string | null
  /** 单次训练预计消耗，单位千卡 */
  estimatedCalories: number | null
  /** true 表示课程处于编辑态（未上架）—— 用户端不应展示 */
  fromDraft: boolean | null
  courseIntroduce: string | null
  usePeople: string | null
  courseDetail: string | null
  precautions: string | null
  requiredEquipment: string | null
  publishTime: string | null
  updateTime: string | null
}

/** 训练目录节点（fm-course 的 `CataVO`，训练日与训练动作共用一个结构） */
export interface CataVO {
  id: string
  index: number
  name: string
  /** 1-训练日 2-训练动作 */
  type: number
  mediaId: string | null
  mediaName: string | null
  mediaDuration: number | null
  /** 是否可试看 */
  trailer: boolean | null
  sets: number | null
  reps: number | null
  restSeconds: number | null
  /** 建议负重，单位 kg */
  weight: number | null
  /** 计时类动作的时长，单位秒 */
  durationSeconds: number | null
  /** 动作类型：1-力量 2-有氧 3-拉伸 */
  actionType: number | null
  equipment: string | null
  targetMuscles: string | null
  /** 该节点下的小测题目数 */
  subjectNum: number | null
  /** 子节点（训练日下面的训练动作） */
  sections: CataVO[] | null
}

/** 课程教练（fm-course 的 `CourseTeacherVO`） */
export interface CourseTeacherVO {
  id: string
  name: string
  icon: string | null
  photo: string | null
  intro: string | null
  isShow: boolean | null
}

// ==================== 字典（枚举 → 中文） ====================

/**
 * ★ 这些映射**必须与后端一致**，值是从后端源码/接口实测抄来的，不是猜的。
 * 猜错的代价是"页面显示空白标签"，而且不报错。
 */
interface DictItem {
  value: number
  label: string
}

/** 训练目标（CourseFullDTO.goalType / CourseSearchVO.goalType） */
export const GOAL_TYPES: DictItem[] = [
  { value: 1, label: '增肌' },
  { value: 2, label: '减脂' },
  { value: 3, label: '塑形' },
  { value: 4, label: '保持' },
]

/** 适用水平 */
export const TRAINING_LEVELS: DictItem[] = [
  { value: 1, label: '零基础' },
  { value: 2, label: '初级' },
  { value: 3, label: '中级' },
  { value: 4, label: '高级' },
]

/** 课程类型 */
export const COURSE_TYPES: DictItem[] = [
  { value: 1, label: '训练计划' },
  { value: 2, label: '单动作教学' },
  { value: 3, label: '理论饮食' },
]

/** 排序方式（对应 `rankBy`，`rankAsc` 控制升降序） */
export const RANK_BY_OPTIONS: { value: NonNullable<CourseSearchQuery['rankBy']>; label: string }[] = [
  { value: 'default', label: '综合' },
  { value: 'sold', label: '报名人数' },
  { value: 'publishTime', label: '上架时间' },
  { value: 'price', label: '价格' },
]

/** 取字典中文名；找不到时返回空串而不是原值（避免页面上出现孤零零的 "7"） */
export function labelOf(dict: DictItem[], value: number | null | undefined): string {
  if (value === null || value === undefined) return ''
  return dict.find((d) => d.value === value)?.label ?? ''
}
