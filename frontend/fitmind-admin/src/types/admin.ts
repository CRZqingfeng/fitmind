/**
 * 管理端业务类型
 *
 * ⚠️ 与用户端同一套后端约定（踩过的坑在这里同样成立）：
 *   ① **`Long` 全局序列化成 String**（`JacksonConfig` 的 `serializerByType(Long.class, ToStringSerializer)`）
 *      → 所有 id 类型必须是 `string`
 *   ② **分页的 `total` / `pages` 也是字符串**（见 `utils/format.ts` 的 `pageTotal`）
 *   ③ **金额一律是"分"**（后端从不用元）
 *   ④ 后端时间是不带时区的 ISO 串（部分来自 ES 的字段是**数组**）
 */

/** 分页结果（`com.fitmind.common.domain.dto.PageDTO`） */
export interface PageDTO<T> {
  total: string
  pages: string
  list: T[]
}

// ============================================================
// 一、课程管理（fm-course）
// ============================================================

/** 课程状态：1-待上架 2-已上架 3-下架 4-已完结（从 `CourseStatus` 常量抄） */
export const COURSE_STATUS = { DRAFT: 1, ON_SHELF: 2, OFF_SHELF: 3, FINISHED: 4 } as const

/** 课程类型：1-训练计划课 2-单动作教学 3-理论饮食课 */
export const COURSE_TYPE_OPTIONS = [
  { value: 1, label: '训练计划课' },
  { value: 2, label: '单动作教学' },
  { value: 3, label: '理论饮食课' },
]

/** 课程列表项（`CoursePageVO`） */
export interface CoursePageVO {
  id: string
  name: string
  courseType: number | null
  coverUrl: string | null
  thirdCateId: string | null
  /** 1-免费 2-付费 */
  free: number | null
  /** 单位：分 */
  price: number | null
  status: number | null
  statusDesc: string | null
  /** 填写进度（建课四步走到哪一步） */
  step: number | null
  sectionNum: number | null
  mediaDuration: number | null
  publishTimes: number | null
  publishTime: string | null
  createTime: string | null
  updateTime: string | null
}

/** 课程分页查询（`CoursePageQuery`） */
export interface CoursePageQuery {
  pageNo?: number
  pageSize?: number
  name?: string
  courseType?: number
  status?: number
  firstCateId?: string
  /**
   * ⚠️ **死参数**：DTO 里写了 `draft-只看草稿 / shelf-只看正式`，
   *   但两个实现（`CourseServiceImpl` / `CourseDraftServiceImpl` 的 `queryForPage`）
   *   都**没有读它** —— 实测 `source=shelf` 与不传的返回完全一致。
   *   "查哪张表"实际由 `status` 决定（见 `CoursePageVO` 上的说明）。
   *   保留在类型里是为了让读代码的人知道"它存在但没用"，前端不提供对应控件。
   */
  source?: 'draft' | 'shelf'
}

/**
 * ★★ 关于这个接口最该记住的一条（实测）：
 *   `GET /cs/courses/page` **不传 `status` 恒定返回 0 条**。
 *   原因不是 bug，是 `CourseController.queryForPage` 的**按状态路由**：
 *     已上架(2) / 已完结(4) → 查 `course` 正式表
 *     其它（含 **null**）    → 查 `course_draft` 草稿表
 *   而草稿在上架时被删掉 → 草稿表是空的 → 0 条。
 *   （实测：库里 11 门已上架课程，`?pageNo=1&pageSize=50` → `total="0"`；
 *   加 `&status=2` → `total="11"`。）
 *   所以管理端页面把「状态」做成**必选**，不给"全部"。
 */

/**
 * 上架前校验（`GET /cs/courses/checkBeforeUpShelf/{id}`）
 *
 * ★★ 这个接口**没有返回值**（后端 `void`）—— 它是一个"校验失败就抛异常"的接口：
 *   · 允许上架 → HTTP 200 + `code 200` + `data: null`
 *   · 不允许   → HTTP 200 + 业务码非 200 + `msg` 是原因（如"课程已上架"）
 *   所以前端**不能**等一个 `{canUpShelf: false}`
 *   （第一版就是按这种形状写的类型，属于想当然 —— 实测才发现是 void）。
 */
export type UpShelfCheckResult = null

// ============================================================
// 一-b、分类与题目
// ============================================================

/** 分类（`CategoryVO`） */
export interface CategoryVO {
  id: string
  name: string
  parentId: string | null
  level: number | null
  /** 1-启用 2-禁用 */
  status: number | null
  courseNum: number | null
  children: CategoryVO[] | null
}

/** 题目（管理端列表项） */
export interface SubjectVO {
  id: string
  courseId: string | null
  name: string
  /** 1-单选 2-多选 3-不定向 4-判断 */
  subjectType: number | null
  difficulty: number | null
  score: number | null
  options: string[] | null
  answer: string | null
  analysis: string | null
}

// ============================================================
// 二、媒资管理（fm-media）
// ============================================================

/** 媒资状态：1-上传中 2-已上传 3-已使用 */
export const MEDIA_STATUS = { UPLOADING: 1, UPLOADED: 2, USED: 3 } as const

/** 媒资列表项（`MediaVO`） */
export interface MediaVO {
  id: string
  filename: string
  /** 封面地址（带签名，可直接显示） */
  coverUrl: string | null
  duration: number | null
  /** 字节 */
  size: number | null
  useTimes: number | null
  status: number | null
  createTime: string | null
  creater: string | null
}

/** 媒资分页查询（`MediaQuery`） */
export interface MediaQuery {
  pageNo?: number
  pageSize?: number
  name?: string
  status?: number
}

/** 上传签名（`UploadSignatureDTO`）：申请 → 直传 → 确认 三步的"申请"结果 */
export interface UploadSignature {
  mediaId: string
  key: string
  /** 预签名 PUT 地址 */
  uploadUrl: string
  /** 签名有效期（秒） */
  expire: number
}

// ============================================================
// 三、退款审批（fm-trade）
// ============================================================

/** 退款状态：1-待审批 2-已取消 3-同意退款 4-已拒绝 5-退款成功 6-退款失败 */
export const REFUND_STATUS = {
  PENDING: 1,
  CANCELLED: 2,
  AGREED: 3,
  REJECTED: 4,
  SUCCESS: 5,
  FAILED: 6,
} as const

/** 退款申请列表项（`RefundApplyPageVO`） */
export interface RefundApplyPageVO {
  id: string
  orderId: string
  orderDetailId: string
  courseName: string | null
  /** 单位：分 */
  refundAmount: number | null
  refundReason: string | null
  status: number | null
  statusDesc: string | null
  createTime: string | null
  approveTime: string | null
  finishTime: string | null
}

/** 退款详情（`RefundApplyVO`）—— 审批页要看的全量信息 */
export interface RefundApplyVO extends RefundApplyPageVO {
  courseId: string | null
  coverUrl: string | null
  /** 课程原价（分，下单时快照） */
  price: number | null
  /** 分摊到的优惠（分） */
  discountAmount: number | null
  /** ★ 实付金额（分）—— 退款金额就是它 */
  realPayAmount: number | null
  /** 状态说明（给用户看的一句话） */
  message: string | null
  questionDesc: string | null
  approveOpinion: string | null
  refundOrderNo: string | null
  refundChannel: string | null
  failedReason: string | null
  payOrderNo: string | null
  payChannel: string | null
  orderTime: string | null
  paySuccessTime: string | null
}

/** 退款分页查询（`RefundApplyPageQuery`） */
export interface RefundApplyPageQuery {
  pageNo?: number
  pageSize?: number
  id?: string
  orderId?: string
  orderDetailId?: string
  refundStatus?: number
}

/** 审批入参（`ApproveFormDTO`）：approveType 1-同意 2-拒绝 */
export interface ApproveForm {
  id: string
  approveType: number
  approveOpinion?: string
  remark?: string
}

// ============================================================
// 四、数据看板（fm-data）
// ============================================================

/**
 * 运营概览（`OverviewVO`）
 *
 * ★ `source` 是这个对象里**最该展示**的字段：
 *   `realtime` 实时计数（还会变）/ `archived` 已落库（不再变）/ `empty` 无数据。
 *   看板上的数字"还会不会变"决定了运营能不能拿它做决定 ——
 *   不标出来的话，同一句话在上午和下午看到的是两个数，用的人会以为系统坏了。
 */
export interface OverviewVO {
  statDate: number | null
  signInCount: number | null
  learnCount: number | null
  practiceCount: number | null
  orderCount: number | null
  /**
   * 成交额（分）
   *
   * ⚠️ **是字符串**：后端字段是 `Long`，被全局 Long→String 规则转掉了
   *   （实测 `netAmount` 返回 `"0"`，而不是 `0`）。
   *   类型里如实写成 `number | string` 而不是只写 `number` ——
   *   "声明得比实际窄"会让 TS 放行 `amount + 1` 这类会变成字符串拼接的写法。
   *   展示一律走 `formatYuan()`（它同时接受两种）。
   */
  orderAmount: number | string | null
  refundCount: number | null
  /** 退款额（分）—— 同 `orderAmount`，Long → 字符串 */
  refundAmount: number | string | null
  assessCount: number | null
  /** 净成交额（分）= 成交额 - 退款额 —— 同 `orderAmount`，Long → 字符串 */
  netAmount: number | string | null
  source: 'realtime' | 'archived' | 'empty' | string | null
}

/** 课程销量榜条目（`CourseTopVO`） */
export interface CourseTopVO {
  rank: number | null
  courseId: string | null
  orderCount: number | null
  /** 销售额（分）—— `Long` → **字符串**（见 `OverviewVO.orderAmount` 的说明） */
  orderAmount: number | string | null
  refundCount: number | null
  source: string | null
}

/** 趋势数据（`TrendVO`）—— ★ 各系列是**等长并列数组**，不是对象列表 */
export interface TrendVO {
  /** x 轴：yyyyMMdd 整数 */
  dates: number[]
  orderCounts: number[]
  /**
   * 成交额（分）
   *
   * ⚠️ `List<Long>` → **字符串数组**（不是数字数组）。
   *   画图时 `Number(v) / 100` 没问题，但直接当数字用就会踩到字符串拼接。
   */
  orderAmounts: (number | string)[]
  signInCounts: number[]
  learnCounts: number[]
  assessCounts: number[]
}

// ============================================================
// 五、AI 运营（fm-aigc）
// ============================================================

/** 知识库状态（`GET /ais/admin/knowledge/status`，是个 Map 不是 DTO） */
export interface KnowledgeStatus {
  indexName: string
  indexExists: boolean
  healthy: boolean
  /**
   * 索引里的向量条数
   *
   * ⚠️ record 里声明的是 `long` → **字符串**（实测返回 `"78"`）。
   *   同记录里的 `mappedDims`/`corpusDocs`/`chunkCount` 是 `Integer`/`int`，是数字。
   *   "同一个对象里有的字段是数字、有的是字符串"就是这套全局序列化规则的后果。
   */
  vectorCount: number | string
  /** 索引 mapping 里的维度 */
  mappedDimensions: number
  /** 配置要求的维度 */
  configuredDimensions: number
  /** 语料文档数 */
  corpusDocs: number
  /** 分块数 */
  chunkCount: number
  message: string
  /** 语料明细（`corpusInfo()` 展开进来的，字段不固定） */
  [key: string]: unknown
}

/** 知识库重建结果（`POST /ais/admin/knowledge/rebuild`） */
export interface KnowledgeRebuildResult {
  indexName: string
  healthy: boolean
  corpusDocs: number
  chunkCount: number
  vectorCount: number
  configuredDimensions: number
  costMs: number
  message: string
}

/** 故障注入开关结果（`POST /ais/admin/knowledge/fail-mode`） */
export interface FailModeResult {
  simulateFailure: boolean
  note: string
}

/** AI 限流视图（`GET /ais/admin/limit?userId=`） */
export interface LimitView {
  enabled: boolean
  usedChats: number
  dailyChats: number
  usedTokens: number
  dailyTokens: number
  /** 当前并发占用 */
  running: number
  maxConcurrent: number
}

/** 额度覆盖结果（`POST /ais/admin/limit`） */
export interface LimitOverrideResult {
  dailyChats: number
  dailyTokens: number
  maxConcurrent: number
  note: string
}

// ============================================================
// 六、优惠券（fm-promotion）
// ============================================================

/**
 * 折扣类型（`PromotionConstants`，从代码抄）
 *
 * ⚠️ `discountValue` 的含义**随类型变化**（这是最容易写错的地方）：
 *   1 满减 / 2 每满减 → 减掉的**金额（分）**
 *   3 折扣           → 折扣值（如 80 表示 8 折，即收 80%）
 *   4 无门槛         → 减掉的**金额（分）**
 */
export const DISCOUNT_TYPES = [
  { value: 1, label: '满减', hint: '满 threshold 分减 discountValue 分' },
  { value: 2, label: '每满减', hint: '每满 threshold 分减 discountValue 分' },
  { value: 3, label: '折扣', hint: 'discountValue=80 表示 8 折（按比例）' },
  { value: 4, label: '无门槛', hint: '直接减 discountValue 分' },
]

/** 领取方式（`OBTAIN_MANUAL=1` / `OBTAIN_CODE=2`） */
export const OBTAIN_WAYS = [
  { value: 1, label: '手动领取' },
  { value: 2, label: '兑换码' },
]

/** 券的状态（`COUPON_DRAFT=1` / `COUPON_ISSUING=2` / `COUPON_FINISHED=3`） */
export const COUPON_STATUS = [
  { value: 1, label: '待发放' },
  { value: 2, label: '发放中' },
  { value: 3, label: '已结束' },
]

/** 券的适用范围类型（`SCOPE_CATEGORY=1` / `SCOPE_COURSE=2`） */
export const COUPON_SCOPE_TYPES = [
  { value: 1, label: '分类' },
  { value: 2, label: '课程' },
]

/** 优惠券（后端直接收 `Coupon` 实体，不是专门的表单 DTO） */
export interface Coupon {
  id: string | null
  name: string
  /** 1-满减 2-每满减 3-折扣 4-无门槛 */
  discountType: number
  /** 0-不限范围 1-限定范围 */
  specific: number
  /** 含义随 discountType 变化：金额（分）或折扣值 —— 见 DISCOUNT_TYPES 的说明 */
  discountValue: number
  /** 门槛金额（分） */
  thresholdAmount: number
  /** 折扣券的最高优惠上限（分） */
  maxDiscountAmount: number | null
  /** 领取方式：1-手动领取 2-兑换码（`OBTAIN_MANUAL` / `OBTAIN_CODE`） */
  obtainWay: number
  /** 1-待发放 2-发放中 3-已结束（`COUPON_DRAFT` / `COUPON_ISSUING` / `COUPON_FINISHED`） */
  status: number | null
  /** 发行总量 */
  totalNum: number
  issueBeginTime: string | null
  issueEndTime: string | null
  /** 每人限领 */
  userLimit: number
  /** 有效期（天）；与 termBeginTime/termEndTime 是两种口径 */
  termDays: number | null
  termBeginTime: string | null
  termEndTime: string | null
  creater: string | null
  createTime: string | null
  /** 限定范围的 id（`specific=1` 时用；bizType 见 `COUPON_SCOPE_TYPES`） */
  scopes: string[] | null
}


// ============================================================
// 七、动作考核（fm-exam）
// ============================================================

/**
 * 一条动作考核记录（管理端视角）。
 *
 * ★ 与学员端用的是**同一个后端 VO**，差别是：管理端接口会填充
 *   `userName` / `cataName`（学员端不填）—— 因为教练需要知道"这是谁做的哪个动作"。
 * ★ `videoUrl` 是**带签名的临时地址**，会过期；所以列表每次刷新都重新拉。
 */
export interface ActionAssessment {
  id: string
  userId: string | null
  /** 学员名（★ 管理端填充） */
  userName: string | null
  courseId: string | null
  cataId: string | null
  /** 训练动作名（★ 管理端填充） */
  cataName: string | null
  /**
   * ★ 媒资 id —— 用它现取播放地址（管理端走 `getPreviewUrl(mediaId)`）。
   * ⚠️ `videoUrl` 一直是 null（播放地址是带签名的临时地址，没有存库）。
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

/** 考核状态（★ 值从后端 `ActionAssessmentVO` 的注释抄，别猜） */
export const ACTION_STATUS = {
  PENDING: 1,
  REVIEWED: 2,
} as const
