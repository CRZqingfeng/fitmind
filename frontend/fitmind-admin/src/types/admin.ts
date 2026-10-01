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

/**
 * 分类（`CategoryVO`）
 *
 * ⚠️ M7-12 补全：M7-8 那份定义只写了 id/name/parentId/level/status/courseNum/children，
 *   而后端 VO 还有 **priority / statusDesc / createTime**（读过 `CategoryVO.java`）。
 *   少了 `priority` 的后果很具体：**分类管理页没法显示与编辑排序值**，
 *   而它恰恰是"同一父下谁在前"的唯一依据。
 *
 * ★ 三级树：level 1-训练目标 2-训练部位 3-器械场景。
 */
export interface CategoryVO {
  id: string
  name: string
  parentId: string | null
  level: number | null
  /** 排序优先级，**越大越靠前**（后端原话） */
  priority: number | null
  /** 1-启用 2-禁用 */
  status: number | null
  statusDesc: string | null
  courseNum: number | null
  createTime: string | null
  children: CategoryVO[] | null
}

/**
 * 题目（管理端，`SubjectVO`）
 *
 * ★ `answer` 与 `answers` 是**同一个答案的两种形态**：
 *   `answer` 原文（"1,3"）/ `answers` 解析成序号数组（[1,3]）。
 *   编辑表单用 `answers` 直接回填勾选状态，比手动 split 稳。
 */
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
  answers: number[] | null
  analysis: string | null
}

/** 题型：与后端 `SubjectType` 一致 */
export const SUBJECT_TYPE = { SINGLE: 1, MULTI: 2, INDEFINITE: 3, JUDGE: 4 } as const
export const SUBJECT_TYPE_OPTIONS = [
  { value: SUBJECT_TYPE.SINGLE, label: '单选题' },
  { value: SUBJECT_TYPE.MULTI, label: '多选题' },
  { value: SUBJECT_TYPE.INDEFINITE, label: '不定向题' },
  { value: SUBJECT_TYPE.JUDGE, label: '判断题' },
]

/** 难易度：1-简单 2-中等 3-困难 */
export const DIFFICULTY_OPTIONS = [
  { value: 1, label: '简单' },
  { value: 2, label: '中等' },
  { value: 3, label: '困难' },
]

// ============================================================
// 一-c、建课（M7-12 第 2 期新增）
// ============================================================

/**
 * 建课进度（`CourseStep`，`fm-course/.../constants/CourseConstants.java`）
 *
 * ★★ 是 **5 步**，不是"四步"（M7 方案 §7.2 当年写错了）
 *   —— 上架门槛是 `REQUIRED_FOR_SHELF = TEACHER`。
 */
export const COURSE_STEP = {
  BASE_INFO: 1, // 课程名称、封面、分类、是否免费、价格
  CATALOGUE: 2, // 训练目录：训练日 / 训练动作
  MEDIA: 3, // 给每个训练动作挂视频 + 组数次数
  SUBJECT: 4, // 动作小测（★ 只能在草稿态配）
  TEACHER: 5, // 教练（上架门槛）
} as const

export const COURSE_STEP_LABELS = ['基本信息', '训练目录', '训练视频', '动作小测', '课程教练']

/** 训练目录类型：1-训练日 2-训练动作 */
export const CATA_TYPE = { DAY: 1, SECTION: 2 } as const

/** 动作类型：1-力量 2-有氧 3-拉伸 */
export const ACTION_TYPE_OPTIONS = [
  { value: 1, label: '力量' },
  { value: 2, label: '有氧' },
  { value: 3, label: '拉伸' },
]

/** 课程保存结果（`CourseSaveVO`）：`id` 是后续 4 步的 key，`step` 是当前进度 */
export interface CourseSaveVO {
  id: string
  name: string
  /** 当前填写进度：1-基本信息 2-目录 3-视频 4-习题 5-教练 */
  step: number | null
}

/** 课程名是否已存在（`NameExistVO`） */
export interface NameExistVO {
  exist: boolean | null
}

/**
 * 训练目录节点（`CataVO`，**树形**：训练日 → 训练动作）
 *
 * ★★ `canUpdate` / `maxIndexOnShelf` / `maxSectionIndexOnShelf` 三个字段是**后端明确给的 UI 依据**：
 *   已上架的训练日受保护（`upShelf` 时会做保护性校验），
 *   前端要按 `canUpdate` 决定按钮可用性，而不是自己猜"第几个不能改"。
 */
export interface CataVO {
  id: string
  index: number | null
  name: string
  /** 1-训练日 2-训练动作 */
  type: number | null
  mediaId: string | null
  mediaName: string | null
  mediaDuration: number | null
  trailer: boolean | null
  sets: number | null
  reps: number | null
  restSeconds: number | null
  /** 建议负重（kg） */
  weight: number | null
  durationSeconds: number | null
  actionType: number | null
  equipment: string | null
  targetMuscles: string | null
  /** 该节点下已绑的题目数（0 = 还没配小测） */
  subjectNum: number | null
  sections: CataVO[] | null
  /** 是否可修改（已上架的目录受保护） */
  canUpdate: boolean | null
  maxIndexOnShelf: number | null
  maxSectionIndexOnShelf: number | null
}

/** 课程基本信息（管理端读回，`CourseBaseInfoVO`）—— 比用户端多正文四段 */
export interface CourseBaseInfoVO {
  id: string
  name: string
  courseType: number | null
  coverUrl: string | null
  firstCateId: string | null
  secondCateId: string | null
  thirdCateId: string | null
  /** 1-免费 2-付费 */
  free: number | null
  /** 单位：分 */
  price: number | null
  status: number | null
  statusDesc: string | null
  step: number | null
  validDuration: number | null
  goalType: number | null
  trainingLevel: number | null
  totalDays: number | null
  weeklyFrequency: number | null
  equipment: string | null
  estimatedCalories: number | null
  enrollStartTime: string | null
  enrollEndTime: string | null
  /** ★ true = 课程在编辑态（未上架）—— 决定"题目还能不能改" */
  fromDraft: boolean | null
  courseIntroduce: string | null
  usePeople: string | null
  courseDetail: string | null
  precautions: string | null
  requiredEquipment: string | null
  publishTime: string | null
  updateTime: string | null
}

/** 分类的新增 / 更新 / 启停入参（对应后端三个 DTO） */
export interface CategoryAddForm {
  name: string
  /** 一级分类传 0（**字符串 '0'**，因为 Long→String 契约） */
  parentId: string
  priority?: number
}

export interface CategoryUpdateForm {
  id: string
  name: string
  priority?: number
}

export interface CategoryStatusForm {
  id: string
  /** 1-启用 2-禁用 */
  status: number
}

/** 题目的新增 / 更新入参（`SubjectFormDTO`） */
export interface SubjectForm {
  /** 不传表示新增 */
  id?: string
  courseId: string
  name: string
  /** 1-单选 2-多选 3-不定向 4-判断 */
  subjectType: number
  difficulty: number
  /** 选择题 2~10 个；**判断题不传** */
  options?: string[]
  /** 选项序号（从 1 开始），多个逗号分隔；判断题 1-正确 2-错误 */
  answer: string
  analysis?: string
  /** 不传默认 5 */
  score?: number
}

/** 训练目录保存入参（`CataSaveDTO`，**树形**：训练日带 sections） */
export interface CataSaveForm {
  /** 不传表示新增（服务端生成） */
  id?: string
  /** 1-训练日 2-训练动作 */
  type: number
  name: string
  /**
   * 排序序号。
   *
   * ★★ **训练日必填、训练动作不用传** —— 这不是笔误：
   *   后端 `CataSaveDTO.index` 上确实有 `@NotNull`，但 `sections` 字段
   *   **没有标 `@Valid`** → 嵌套的动作**不参与级联校验**（`seed-demo-data.mjs` 里也从来不传）。
   *   动作的顺序由**数组顺序**决定，所以把它做成可选、并且只给顶层赋值。
   */
  index?: number
  /** 训练日下面的动作（不传 index） */
  sections?: CataSaveForm[]
}

/** 训练视频 + 动作参数（`CourseMediaDTO`） */
export interface CourseMediaForm {
  /** 训练动作的目录 id */
  cataId: string
  /** 来自 fm-media 上传接口 */
  mediaId?: string
  videoName?: string
  mediaDuration?: number
  trailer?: boolean
  sets?: number
  reps?: number
  restSeconds?: number
  weight?: number
  durationSeconds?: number
  actionType?: number
}

/** 课程教练（`CourseTeacherSaveDTO`） */
export interface CourseTeacherForm {
  id: string
  teachers: { id: string; isShow: boolean }[]
}

/** 教练信息（`CourseTeacherVO`） */
export interface CourseTeacherVO {
  id: string
  name: string
  icon: string | null
  photo: string | null
  intro: string | null
  isShow: boolean | null
}

/** 节点-题目关联（`CataSubjectVO`） */
export interface CataSubjectVO {
  cataId: string
  cataName: string
  subjects: {
    id: string
    name: string
    subjectType: number | null
    difficulty: number | null
  }[]
}

// ============================================================
// 一-d、社区内容审核（M7-12 第 3 期，fm-learning）
// ============================================================
/**
 * ★★ 三个模块共用一条设计：**处置动作是"隐藏/折叠"，不是删除** ——
 *   审核要可追溯、可撤销。隐藏后**学员端查不到**，而管理端分页**照常能查到**
 *   （接口注释原话："含被隐藏的"）→ 藏了还能找回来。
 */

/** 管理端问题（`QuestionAdminVO`，★ 匿名提问在这里会返回**真实用户**） */
export interface QuestionAdminVO {
  id: string
  title: string
  description: string | null
  courseId: string | null
  courseName: string | null
  chapterId: string | null
  chapterName: string | null
  sectionId: string | null
  sectionName: string | null
  userId: string | null
  userName: string | null
  userIcon: string | null
  latestAnswerId: string | null
  /** 回答数 */
  answerTimes: number | null
  /** ★ 是否匿名提问（审核才能看到的真实身份） */
  anonymity: boolean | null
  hidden: boolean | null
  /** 管理端状态：0-未查看 1-已查看 */
  status: number | null
  createTime: string | null
}

/** 问题管理端状态 */
export const QUESTION_ADMIN_STATUS = { UNREAD: 0, READ: 1 } as const
export const QUESTION_ADMIN_STATUS_OPTIONS = [
  { value: QUESTION_ADMIN_STATUS.UNREAD, label: '未查看' },
  { value: QUESTION_ADMIN_STATUS.READ, label: '已查看' },
]

/**
 * 互动回答 / 评论（`ReplyVO`）
 *
 * ★ `answerId === '0'` 表示**顶层回答**，否则是"回答下的评论"。
 *   **隐藏顶层回答会连带隐藏其下所有评论**（后端行为）—— UI 要提示这一点。
 */
export interface ReplyVO {
  id: string
  questionId: string | null
  /** '0' 或 null 表示顶层回答 */
  answerId: string | null
  userId: string | null
  /** ★ 匿名时为空（审核能看到 `anonymity`） */
  userName: string | null
  userIcon: string | null
  /** 1-管理员 2-学员 3-教练 */
  userType: number | null
  content: string | null
  targetUserId: string | null
  targetReplyId: string | null
  targetUserName: string | null
  replyTimes: number | null
  likedTimes: number | null
  liked: boolean | null
  anonymity: boolean | null
  hidden: boolean | null
  createTime: string | null
}

/** 回答者类型 */
export const REPLIER_TYPE: Record<number, string> = { 1: '管理员', 2: '学员', 3: '教练' }

/** 管理端笔记（`NoteAdminVO`，★ 附"被采集次数"，这是审核关心的信号） */
export interface NoteAdminVO {
  id: string
  /** 持有者（采集来的笔记，持有者 ≠ 原作者） */
  userId: string | null
  authorId: string | null
  authorName: string | null
  courseId: string | null
  courseName: string | null
  chapterId: string | null
  chapterName: string | null
  sectionId: string | null
  sectionName: string | null
  /** 笔记对应的视频时间点（秒） */
  noteMoment: number | null
  content: string | null
  isPrivate: boolean | null
  hidden: boolean | null
  hiddenReason: string | null
  isGathered: boolean | null
  gatheredNoteId: string | null
  /** ★ 被采集次数 */
  usedTimes: number | null
  likedTimes: number | null
  createTime: string | null
}

/**
 * 管理端笔记详情（`NoteAdminDetailVO extends NoteAdminVO`）
 *
 * ★ 只多两个字段，但这两个字段**只有审核场景才该看到**：
 *   作者手机号、以及**采集者名单**（谁把这条笔记收藏走了）。
 */
export interface NoteAdminDetailVO extends NoteAdminVO {
  authorPhone: string | null
  gathers: string[] | null
}

// ============================================================
// 一-e、公告与通知模板（M7-12 第 3 期，fm-message）
// ============================================================

/** 通知类型：0-系统 1-笔记 2-问答 3-其它 4-私信 */
export const NOTICE_TYPE_OPTIONS = [
  { value: 0, label: '系统' },
  { value: 1, label: '笔记' },
  { value: 2, label: '问答' },
  { value: 3, label: '其它' },
  { value: 4, label: '私信' },
]

/** 模板状态：0-草稿 1-使用中 2-停用 */
export const NOTICE_TEMPLATE_STATUS = { DRAFT: 0, USING: 1, DISABLED: 2 } as const
export const NOTICE_TEMPLATE_STATUS_OPTIONS = [
  { value: NOTICE_TEMPLATE_STATUS.DRAFT, label: '草稿' },
  { value: NOTICE_TEMPLATE_STATUS.USING, label: '使用中' },
  { value: NOTICE_TEMPLATE_STATUS.DISABLED, label: '停用' },
]

/** 通知模板（后端直接返回 PO `notice_template`） */
export interface NoticeTemplate {
  id: string
  name: string
  /** ★ 程序按它找模板，**不可修改** */
  code: string
  type: number | null
  status: number | null
  /** 支持 `{占位符}` */
  title: string
  content: string
  /** 0-否 1-是 */
  isSmsTemplate: number | null
  createTime: string | null
  updateTime: string | null
}

/** 通知模板表单（`NoticeTemplateFormDTO`） */
export interface NoticeTemplateForm {
  /** 修改时必传 */
  id?: string
  name: string
  /** ★ 修改时后端**忽略**这个字段（代号不可改） */
  code: string
  type?: number
  status?: number
  title: string
  content: string
  isSmsTemplate?: number
}

/** 公告（后端直接返回 PO `public_notice`） */
export interface PublicNotice {
  id: string
  /** 0-系统 3-其它 */
  type: number | null
  title: string
  content: string
  /** ★ 未来时间 = 定时生效（学员端按 `pushTime <= now` 过滤，没有调度器） */
  pushTime: string | null
  expireTime: string | null
  createTime: string | null
}

/** 公告发布表单（`PublicNoticeFormDTO`） */
export interface PublicNoticeForm {
  type?: number
  title: string
  content: string
  /** 留空 = 立即生效 */
  pushTime?: string
  /** 留空 = 按默认有效期 */
  expireTime?: string
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
  /**
   * ★★ 直传时**必须原样带上**的 Content-Type（M9-1 起）。
   *
   * 阿里云 OSS 的 V4 签名把 Content-Type 算进签名，而浏览器直传 `File` 时一定会带它 ——
   * 与后端签名时的值不一致就是 `403 SignatureDoesNotMatch`（报错完全指不出原因）。
   * 所以值由后端算好回传，前端照带。
   *
   * ⚠️ MinIO 模式下为 `undefined`，那时沿用原有自猜逻辑，行为不变。
   */
  contentType?: string
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
