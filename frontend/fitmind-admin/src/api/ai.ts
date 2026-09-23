/**
 * AI 运营（fm-aigc，网关前缀 /ais）
 *
 * ★★ `/ais/admin/**` **校验管理员**（实测：学员 token → 403）。
 *   这也是 M7-7 把这几块**留在管理端**的原因：
 *   降级演练开关能人为制造故障，放在学员可调的位置等于开了个演示事故的口子。
 */
import type {
  FailModeResult,
  KnowledgeRebuildResult,
  KnowledgeStatus,
  LimitOverrideResult,
  LimitView,
} from '@/types/admin'
import { http } from '@/utils/request'

/** 知识库状态（索引 / 向量数 / 维度一致性 / 语料条数） */
export function getKnowledgeStatus(): Promise<KnowledgeStatus> {
  return http.get<KnowledgeStatus>('/ais/admin/knowledge/status')
}

/**
 * 重建知识库索引（删索引 → 建索引 → 语料分块 → 向量化写入）
 *
 * ★ 这是个**长耗时**接口（要重新向量化整份语料）：本地小语料也要几秒，
 *   接了真实 embedding 服务会更久。所以：
 *   · 前端必须给明确的 loading（不能只转个圈，要说清在做什么）
 *   · 请求要单独把超时放长（默认 10s 会直接断）
 */
export function rebuildKnowledge(): Promise<KnowledgeRebuildResult> {
  return http.post<KnowledgeRebuildResult>('/ais/admin/knowledge/rebuild', null, { timeout: 180000 })
}

/**
 * ★★ 故障注入：开关"检索强制失败"（用来演示降级路径）
 *
 * ⚠️ 它会**真的影响所有用户的对话**（检索一律失败 → 走"无依据回答"降级分支），
 *   所以调用方必须：二次确认 + 页面常驻醒目提示 + 一键关闭。
 *   `enabled=false` 立即恢复；进程重启也会恢复配置值（不持久化）。
 */
export function setKnowledgeFailMode(enabled: boolean): Promise<FailModeResult> {
  return http.post<FailModeResult>('/ais/admin/knowledge/fail-mode', null, { params: { enabled } })
}

/** 某个用户的今日 AI 配额视图 */
export function getLimitView(userId: string): Promise<LimitView> {
  return http.get<LimitView>('/ais/admin/limit', { params: { userId } })
}

/** 临时调额（不持久化，重启恢复） */
export function overrideLimit(
  dailyChats: number,
  dailyTokens: number,
  maxConcurrent = 2,
): Promise<LimitOverrideResult> {
  return http.post<LimitOverrideResult>('/ais/admin/limit', null, {
    params: { dailyChats, dailyTokens, maxConcurrent },
  })
}

/** 恢复为配置额度 */
export function resetLimitOverride(): Promise<boolean> {
  return http.delete<boolean>('/ais/admin/limit')
}

/** 清空某用户今日计数（验收/演示用） */
export function resetLimitCounter(userId: string): Promise<boolean> {
  return http.delete<boolean>('/ais/admin/limit/counter', { params: { userId } })
}

/**
 * 提示词来源与字数（`GET /ais/chat/prompts`）
 *
 * ★ 这个接口在 `/ais/chat/**` 下（不是 admin），**学员也能看** ——
 *   但提示词本身不是敏感信息（泄露的是"系统怎么写的"，不是密钥），
 *   而且让用户看到"AI 被怎么约束的"反而增加可信度。管理端也复用它。
 */
export function getPrompts(): Promise<Record<string, string>> {
  return http.get<Record<string, string>>('/ais/chat/prompts')
}

/** 当前模型通道（`mock` 确定性替身 / `deepseek` 真实模型） */
export function getProvider(): Promise<string> {
  return http.get<string>('/ais/chat/provider')
}
