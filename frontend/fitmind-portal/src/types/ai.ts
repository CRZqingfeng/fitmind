/**
 * AI 教练（fm-aigc）的类型定义
 *
 * 这个模块的响应体是全项目**最结构化**的一个 —— 后端刻意把"AI 怎么想的"
 * 全部摊平成了字段（引用来源 / 工具调用 / token / 首字延迟 / 记忆 / 压缩 / 限流），
 * 而不是让前端从回答文本里猜。所以类型要写全，调试面板才有东西可渲染。
 */

// ============================================================
// SSE 事件
// ============================================================

/**
 * 事件类型（后端 `ChatEventVO.type`，字符串枚举）
 *
 * ⚠️ 后端发的是 `event:` 行上的**事件名**，不是 JSON 字段 ——
 * 解析时要按帧取名字，不能指望 data 里带 type。
 *
 * - `DATA`  增量文本（一个分片，可能只有 1~4 个字）
 * - `PARAM` 结构化载荷（JSON 字符串，含 `rag` / `cards`）
 * - `STOP`  收尾（JSON 字符串，含本轮全部指标）
 *
 * ★ 值必须与后端 `AigcConstants.EVENT_*` 一致 —— 从代码抄，不猜。
 */
export const SSE_EVENT = {
  DATA: 'DATA',
  PARAM: 'PARAM',
  STOP: 'STOP',
} as const

/** 一帧解析结果 */
export interface SseFrame {
  /** 事件名：DATA / PARAM / STOP */
  event: string
  /** 载荷（多行 data 用 \n 连接，与 SSE 规范一致） */
  data: string
}

/** PARAM 事件的载荷：一次承载引用与卡片两种结构化数据 */
export interface ChatParamPayload {
  /** 检索引用（仅在真的有命中时出现） */
  rag?: RagInfo
  /** 工具卡片（仅在工具产出卡片时出现） */
  cards?: ChatCardData[]
}

// ============================================================
// RAG 引用
// ============================================================

/** 一条引用来源 */
export interface RagSource {
  /** 文档 id，如 `05-protein-intake` */
  docId: string
  title: string
  /** 命中的小节标题（★ 字段名是 `section`，不是 `chunkIndex`） */
  section: string
  /**
   * 分数。★★ **语义不统一，展示时必须如实标注**：
   *   · 来自**向量**那一路 → 余弦相似度，范围 [0,1]
   *   · 来自**关键词**那一路 → **BM25 原始分，无上界**（实测出现过 6.96）
   * 因为混合检索用 RRF 按"排名"融合、两路分数不可比，所以这里保留的是原始分。
   */
  score: number
  /** 命中片段预览 */
  preview: string
}

/** 检索统计（`RagTrace.toMap()`） */
export interface RagInfo {
  /** `rag` / `degraded` / `skipped` / `off` —— 四值枚举，不是布尔 */
  mode: string
  /** 本次是否走了混合检索 */
  hybrid: boolean
  /** 候选条数（向量 + 关键词，融合前） */
  candidateCount: number
  /** 实际注入 prompt 的条数 */
  injectedCount: number
  /** 注入正文总字数 */
  contextChars: number
  /** 检索耗时（毫秒） */
  searchMs: number
  sources: RagSource[]
  /** 降级原因等说明（`mode=degraded` 时有值） */
  note?: string
}

// ============================================================
// 工具调用
// ============================================================

/** 一次工具调用明细 */
export interface ToolInvocation {
  /** 工具名，如 `searchCourses` */
  name: string
  /** 参数（JSON 字符串；★ 查询类工具的档案工具参数恒为 `{}`，因为身份来自登录态） */
  args: string
  /** 结果摘要（人读的一行，不是完整返回） */
  result: string
  costMs: number
  /** 因超出调用次数上限被拦下 */
  blocked?: boolean
  error?: string
}

/** 工具调用汇总（`ToolTrace.toMap()`） */
export interface ToolTrace {
  toolCallCount: number
  toolNames: string[]
  invocations: ToolInvocation[]
  cards?: ChatCardData[]
}

// ============================================================
// 卡片（工具产出的结构化数据 —— ★ 不是模型生成的文本）
// ============================================================

/** 卡片类型（值与后端 `card.put("type", ...)` 一致） */
export const CARD_TYPE = {
  COURSE_LIST: 'COURSE_LIST',
  ORDER_CONFIRM: 'ORDER_CONFIRM',
} as const

/** 课程列表卡片里的单门课 */
export interface CardCourse {
  courseId: string
  courseName: string
  coverUrl: string | null
  /** 1-免费 2-付费 */
  free: number | null
  /** 单位：分 */
  price: number | null
  sectionNum: number | null
  validDuration: number | null
}

export interface CourseListCard {
  type: 'COURSE_LIST'
  title: string
  /** 实际用于检索的关键词（模型给的，值得展示 —— 能看出它"理解成了什么"） */
  keyword: string
  courses: CardCourse[]
}

export interface OrderConfirmCard {
  type: 'ORDER_CONFIRM'
  title: string
  /** ★ 字符串（Long→String 全局规则） */
  orderId: string
  totalAmount: number | null
  /** ⚠️ 预下单**不算优惠券**，这个值恒为 0 —— 展示时不要当"优惠了 0 元" */
  discountAmount: number | null
  realAmount: number | null
  payOutTime: string | null
  courses: CardCourse[]
}

export type ChatCardData = CourseListCard | OrderConfirmCard | { type: string; title?: string }

// ============================================================
// 指标
// ============================================================

/**
 * STOP 事件的载荷：本轮全部指标
 *
 * ★★ **注意它有两种形状**（实测出来的，不是猜的）：
 *
 *  ① 正常一轮：`sessionId` / `requestId` / `agentType` / `chatType` / token / 延迟 …（下面这些字段）
 *  ② **被限流的一轮**：只有 `limited` / `limitReason` / `quotaUsed` / `quotaLimit` /
 *     `quotaRemaining` / `firstTokenMs=0` / `totalMs=0` ——
 *     **连 sessionId 和 agentType 都没有**。
 *
 * 后端的用意是好的：限流是"预期内的业务状态"，所以它返回一条**说人话的助手消息 + 正常 STOP**，
 * 而不是 500 或 429 —— 前端不用为它写一套特殊错误分支（正常渲染即可），
 * 用户也能看懂"为什么没回答、什么时候能再用"。
 *
 * 但对类型的含义是：**上面那些字段全都可能缺席**。
 * 如果按"一定有"来声明，就会出现"限流那一轮读 `sessionId` 拿到 undefined"这种运行期问题，
 * 而 TS 全程放行。所以这里如实标成可选。
 */
export interface StopPayload {
  sessionId?: string | null
  requestId?: string | null
  /** `simple` / `recommend` / `consult` / `buy` / `knowledge` */
  agentType?: string | null
  /** ROUTE / ENHANCE / SIMPLE */
  chatType?: string | null
  firstTokenMs?: number | null
  totalMs?: number | null
  promptTokens?: number | null
  completionTokens?: number | null
  routePromptTokens?: number | null
  routeCompletionTokens?: number | null
  ragMode?: string | null
  ragHits?: number | null
  ragCandidates?: number | null
  ragSearchMs?: number | null
  ragContextChars?: number | null
  toolCallCount?: number | null
  toolNames?: string[] | null
  toolCalls?: ToolTrace | null
  /** 本轮是否被停止（用户主动 / 被新一轮接管） */
  stopped?: boolean | null
  /** 送进 prompt 的历史条数（多轮有没有生效，一眼可见） */
  memoryHistory?: number | null
  compressed?: boolean | null
  compressedCount?: number | null
  compressMs?: number | null
  degraded?: boolean | null

  // ---- ★ 下面这组只在"被限流"的载荷里出现（此时上面那些都没有） ----
  limited?: boolean | null
  limitReason?: string | null
  quotaUsed?: number | null
  quotaLimit?: number | null
  quotaRemaining?: number | null
}

/** 非流式对话结果（`POST /ais/chat`，字段是 STOP 载荷的超集） */
export interface ChatResult extends StopPayload {
  content: string
  provider?: string | null
  rag?: RagInfo | null
  cards?: ChatCardData[] | null
}

// ============================================================
// 会话与消息
// ============================================================

export interface ChatSession {
  /** ★ 字符串（雪花 id 走 Long→String） */
  sessionId: string
  title: string
  /** ⚠️ 是**字符串**（Long→String 全局规则）—— 参与算术前必须转数字 */
  msgCount: string | number
  lastMessage: string | null
  lastActive: string | null
  createTime: string | null
}

export interface ChatMessage {
  id: string
  /** user / assistant */
  role: string
  content: string
  agentType: string | null
  provider: string | null
  /** 卡片数据（历史重渲染靠它，否则回看历史就只剩文字） */
  params: ChatParamPayload | null
  toolCalls: ToolTrace | null
  firstTokenMs: number | null
  totalMs: number | null
  createTime: string | null
}

/** 上下文窗口体检报告（`GET /chat/sessions/{id}/memory`） */
export interface MemoryView {
  enabled: boolean
  recentTurns: number
  maxMessages: number
  ttlDays: number
  /** ⚠️ 字符串 */
  messageCount: string | null
  /** ⚠️ 字符串 */
  ttlSeconds: string | null
  /** 摘要字数（0 = 还没压缩过） */
  summaryChars: number
  summaryPreview: string | null
  /** 当前上下文窗口里的最近几条（只给预览，不是全文） */
  recentPreview: { role: string; preview: string }[] | null
}

/** 配额 / 限流视图 */
export interface QuotaView {
  enabled: boolean
  usedChats: number
  dailyChats: number
  remainingChats: number
  usedTokens: number
  dailyTokens: number
  /** 当前并发中的轮次 */
  running: number
  maxConcurrent: number
}

/** 知识库状态（管理端接口） */
export interface KnowledgeStatus {
  indexName: string
  indexExists: boolean
  healthy: boolean
  /** ⚠️ 字符串（Long→String） */
  vectorCount: string | null
  mappedDimensions: number
  configuredDimensions: number
  corpusDocs: number
  chunkCount: number
  message: string
  similarityThreshold: number
  corpusLocation: string
  topK: number
  hybrid: boolean
  dimensions: number
  chunkSize: number
  chunkOverlap: number
  embeddingModel: string
}

// ============================================================
// 编排策略
// ============================================================

/** 策略说明 —— 让用户知道"贵在哪"，这本身就是可观测性的一部分 */
export const CHAT_TYPES = [
  {
    value: 'SIMPLE',
    label: '直答',
    desc: '单次调用模型，不路由、不检索。最快最省，适合闲聊与常识问题',
  },
  {
    value: 'ENHANCE',
    label: '增强检索',
    desc: '先检索知识库再回答，并标注引用来源。适合训练/营养类问题',
  },
  {
    value: 'ROUTE',
    label: '智能路由',
    desc: '先花一次调用判断该由哪个 Agent 处理（推荐/咨询/下单/知识），可调用工具。能力最全，成本最高',
  },
] as const

export type ChatTypeValue = (typeof CHAT_TYPES)[number]['value']

/** 策略值 → 中文（找不到就原样返回，不显示空白） */
export function chatTypeLabel(value: string | null | undefined): string {
  if (!value) return '—'
  return CHAT_TYPES.find((t) => t.value === value)?.label ?? value
}

/** 检索模式 → 中文（★ 四个值从后端代码抄：rag/degraded/skipped/off） */
export const RAG_MODE_LABEL: Record<string, string> = {
  rag: '已检索',
  degraded: '检索降级',
  skipped: '未检索',
  off: '未启用',
}

export function ragModeLabel(mode: string | null | undefined): string {
  if (!mode) return '—'
  return RAG_MODE_LABEL[mode] ?? mode
}
