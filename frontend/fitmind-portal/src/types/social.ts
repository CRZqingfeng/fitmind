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
  title: string
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
