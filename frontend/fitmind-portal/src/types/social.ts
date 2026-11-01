/**
 * 社区（问答 / 笔记）+ 站内信 的类型定义（M7-5）
 *
 * 与其它域一致：**所有 Long 都是 string**（`JacksonConfig` 全局转的）。
 *
 * ★ 两个容易混的字段（后端就是这么设计的，前端必须认清）：
 *   - **`liked` 是"当前用户有没有点过赞"**，由 fm-learning 在返回回答/笔记时
 *     **实时向 fm-remark 查**的，不是冗余字段 —— 所以前端**不需要**再单独调点赞查询接口。
 *   - **`likedTimes` 是总数**。两者一起给，界面才能正确渲染"已赞 ❤️ 12"。
 */

// ==================== 枚举 ====================

/** 点赞的业务类型（fm-remark 的 `bizType`） */
export const LikeBizType = {
  /** 问答回答 */
  QA: 'QA',
  /** 笔记 */
  NOTE: 'NOTE',
} as const

/** 回答者类型 */
export const ReplyUserType = {
  ADMIN: 1,
  STUDENT: 2,
  COACH: 3,
  /**
   * ★ AI 机器人（M10-1 第 2 期预留）。
   *
   * 后端 `ReplyVO.userType` 本来就是 int（由 fm-user 查出后透传），
   * 所以**加这个值不需要改后端** —— 将来机器人回复时带 `userType = 4`，
   * 前端就会把它渲染成「AI 助手」徽章。
   *
   * ⚠️ 但它必须**永远只出现在评论区**：学习视频下面挂着动作小测，
   * 任何"能在题目旁边自由提问"的 AI 入口都等于递答案（见 M10-1 §1.1 的红线）。
   */
  BOT: 4,
} as const

/** 站内信类型 */
export const NoticeType = {
  SYSTEM: 0,
  NOTE: 1,
  QA: 2,
  OTHER: 3,
  PRIVATE: 4,
} as const

// ==================== 问答 ====================

export interface Question {
  id: string
  title: string
  description: string | null
  courseId: string | null
  /** 训练日 id */
  chapterId: string | null
  /** 训练动作 id */
  sectionId: string | null
  userId: string
  /** ★ 匿名提问时后端给空，界面不要显示"匿名用户"头像占位 */
  userName: string | null
  userIcon: string | null
  anonymity: boolean | null
  latestReplyContent: string | null
  latestReplyUser: string | null
  answerTimes: number | null
  createTime: string | null
}

export interface QuestionForm {
  courseId?: string
  chapterId?: string
  sectionId?: string
  /**
   * 标题 —— ★ M10-1 第 2 期起**可以不传**（后端会用 `description` 前 20 字兜一个）。
   *
   * 播放页的评论区是"边看边问"的形态，让用户先想个标题反人性（B 站的评论也没有标题）。
   * ⚠️ 但它仍是 60 字上限的字符串：社区页照常传，行为不变。
   */
  title?: string
  description?: string
  anonymity?: boolean
}

export interface QuestionPageQuery {
  pageNo?: number
  pageSize?: number
  courseId?: string
  sectionId?: string
  /** 只看我提问的 */
  onlyMine?: boolean
  /**
   * 浏览范围（M10-1 第 3 期）。
   *
   * ★ **不传** = 按 courseId / sectionId 过滤，且二者**至少传一个**（否则后端 400）；
   * ★ **传 `'ALL'`** = 全站广场，忽略 course/section。
   *
   * ⚠️ 后端用的是"显式开关"而不是"删掉校验"：`accept-web-m7-5.mjs:262-273` 有 3 条负向断言
   *    （测试标题写着"假绿过一次的地方"）钉着"不带上下文必须 400"。
   */
  scope?: 'ALL'
  /** 只看还没有人回答的（`answer_times = 0`）—— 广场的「待回答」页签 */
  unanswered?: boolean
  /** 排序：不传=最新；`hot`=最热（按回答数） */
  sort?: 'hot'
}

export interface Reply {
  id: string
  questionId: string
  /** 上级回答 id（`0` 表示顶层回答） */
  answerId: string | null
  userId: string
  userName: string | null
  userIcon: string | null
  /** 1-管理员 2-学员 3-教练 */
  userType: number | null
  content: string
  targetUserId: string | null
  targetReplyId: string | null
  targetUserName: string | null
  /** 下属评论数 */
  replyTimes: number | null
  likedTimes: number | null
  /** 当前用户是否点过赞（后端实时查的） */
  liked: boolean | null
  anonymity: boolean | null
  hidden: boolean | null
  createTime: string | null
}

export interface ReplyForm {
  questionId: string
  /** 回答顶层问题时留空或传 0 */
  answerId?: string
  targetReplyId?: string
  targetUserId?: string
  content: string
  anonymity?: boolean
}

export interface ReplyPageQuery {
  pageNo?: number
  pageSize?: number
  /** 查某问题的回答 */
  questionId?: string
  /** 查某回答下的评论 */
  answerId?: string
}

// ==================== 笔记 ====================

export interface Note {
  id: string
  /** 持有者 id（★ 采集后是采集者，不是原作者） */
  userId: string
  /** 原作者 id */
  authorId: string | null
  authorName: string | null
  authorIcon: string | null
  courseId: string | null
  chapterId: string | null
  sectionId: string | null
  /** 笔记对应的视频时间点（秒） */
  noteMoment: number | null
  content: string
  isPrivate: boolean | null
  /** 本条是否采集自他人 */
  isGathered: boolean | null
  /** 当前用户是否采集过这条笔记 */
  gatheredByMe: boolean | null
  likedTimes: number | null
  liked: boolean | null
  createTime: string | null
}

export interface NoteForm {
  /** 修改时必填 */
  id?: string
  courseId?: string
  chapterId?: string
  sectionId?: string
  /** 视频时间点（秒） */
  noteMoment?: number
  content: string
  isPrivate?: boolean
}

export interface NotePageQuery {
  pageNo?: number
  pageSize?: number
  courseId?: string
  sectionId?: string
  /** 只看我的 */
  onlyMine?: boolean
  /**
   * 浏览范围（M10-1 第 3 期）：与 `QuestionPageQuery.scope` 同一套语义。
   * ★ 全站模式下**别人的私有笔记不会出现** —— 这条由 `NoteMapper.xml` 的
   *   `AND (n.is_private = 0 OR n.user_id = #{userId})` 守住，不靠调用方记得过滤。
   */
  scope?: 'ALL'
}

// ==================== 点赞 ====================

export interface LikeForm {
  bizId: string
  /** `QA` | `NOTE` */
  bizType: string
  /** true-点赞 false-取消点赞 */
  liked: boolean
}

// ==================== 站内信 ====================

export interface InboxMessage {
  id: string
  /** 0-系统 1-笔记 2-问答 3-其它 4-私信 */
  type: number
  title: string
  content: string | null
  /** 0-未读 1-已读 */
  isRead: number
  pushTime: string | null
}

export interface InboxQuery {
  pageNo?: number
  pageSize?: number
  /** 0-未读 1-已读；不传查全部 */
  isRead?: number
  type?: number
}
