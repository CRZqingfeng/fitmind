/**
 * AI 教练接口
 *
 * 网关前缀 `/ais/**`（fm-aigc）。
 *
 * ★ 权限边界（实测）：`/ais/chat/**` 登录即可（学员可用）；
 *   而 `/ais/admin/**`（知识库状态 / 故障注入 / 限流覆盖）**要求管理员身份**，
 *   用户端拿不到 —— 那部分放在管理端（M7-8 的「AI 运营」页）。
 *   这里不为了"面板好看"去放开权限。
 */

import type {
  ChatMessage,
  ChatResult,
  ChatSession,
  MemoryView,
  QuotaView,
} from '@/types/ai'
import type { PageDTO } from '@/types/course'
import http from '@/utils/request'
import { streamChat, type StreamChatOptions, type StreamHandle } from '@/utils/sse'

/** 一轮对话的请求体（`ChatRequestDTO`） */
export interface ChatRequestBody {
  /** 不传则新建会话 */
  sessionId?: string
  content: string
  /** 编排策略：ROUTE / ENHANCE / SIMPLE */
  chatType?: string
  /** 训练目标（用于知识库元数据过滤），如 muscle_gain / fat_loss */
  goal?: string
  /** 检索条数覆盖 */
  ragTopK?: number
  /** 相似度阈值覆盖（0~1） */
  ragThreshold?: number
  /** 是否启用混合检索覆盖 */
  ragHybrid?: boolean
}

/**
 * 流式对话（SSE）
 *
 * ★ 不走 Axios —— 见 `utils/sse.ts` 的说明（EventSource 只支持 GET 且不能加鉴权头）。
 */
export function chatStream(body: ChatRequestBody, callbacks: Omit<StreamChatOptions, 'body'>): StreamHandle {
  return streamChat({ body: body as unknown as Record<string, unknown>, ...callbacks })
}

/**
 * 非流式对话
 *
 * ★ 它的价值不只是"给不想流的场景用"：它是**流式的对照组** ——
 *   验收脚本用它拿到"接口认定的完整回答"，再与前端逐帧拼接的结果比对，
 *   从而证明"前端解析没有丢字符"。没有这个对照，就无从发现丢字。
 */
export function chatOnce(body: ChatRequestBody): Promise<ChatResult> {
  return http.post<ChatResult>('/ais/chat', body)
}

/**
 * 停止本会话正在生成的一轮（分布式打断）
 *
 * 返回被停止的 requestId；返回 `null` 表示该会话当前没有在生成的轮次
 * （用户点了个已经结束的停止按钮 —— 正常情况，不报错）。
 */
export function stopGeneration(sessionId: string): Promise<string | null> {
  return http.post<string | null>('/ais/chat/stop', { sessionId })
}

/** 我的会话列表（分页） */
export function listSessions(pageNo = 1, pageSize = 20): Promise<PageDTO<ChatSession>> {
  return http.get<PageDTO<ChatSession>>('/ais/chat/sessions', { params: { pageNo, pageSize } })
}

/** 某个会话的全部消息（用于切回历史会话时重渲染） */
export function listMessages(sessionId: string): Promise<ChatMessage[]> {
  return http.get<ChatMessage[]>(`/ais/chat/sessions/${sessionId}/messages`)
}

/**
 * 上下文窗口体检报告
 *
 * ★ 排查"它怎么不记得刚才说的了"这类问题的第一站：
 *   能看到带上了几条历史、摘要多少字、TTL 多久。
 */
export function getMemory(sessionId: string): Promise<MemoryView> {
  return http.get<MemoryView>(`/ais/chat/sessions/${sessionId}/memory`)
}

/** 我的配额与并发占用 */
export function getQuota(): Promise<QuotaView> {
  return http.get<QuotaView>('/ais/chat/quota')
}

/**
 * 当前模型通道（`deepseek` / `mock`）
 *
 * ★ 前端**必须**把它显示出来：同一条回答，来自真实模型还是确定性 Mock，
 *   可信度与成本完全不同。不标注就等于让看的人自己猜。
 */
export function getProvider(): Promise<string> {
  return http.get<string>('/ais/chat/provider')
}

/** 7 个提示词的来源与字数（`route` / `consult` / `recommend` / `buy` / `knowledge` / `simple` / `compress`） */
export function getPrompts(): Promise<Record<string, string>> {
  return http.get<Record<string, string>>('/ais/chat/prompts')
}

/*
 * ⚠️ 以下三个能力**不在这里实现**，因为用户端根本调不通（实测 `/ais/admin/**` 要求管理员身份）：
 *   · `GET  /ais/admin/knowledge/status`  知识库状态（索引名/向量数/阈值/topK/chunk 参数）
 *   · `POST /ais/admin/knowledge/fail-mode` 降级演练开关
 *   · `GET/POST/DELETE /ais/admin/limit`  配额覆盖与计数重置
 *
 * ★ 不为"让面板更好看"去放开权限：降级演练开关是**能人为制造故障**的接口，
 *   放在学员可调的位置等于开了一个演示事故的口子。
 * → 它们的归宿是管理端的「AI 运营」页（M7-8），那里本来就要求管理员登录。
 */
