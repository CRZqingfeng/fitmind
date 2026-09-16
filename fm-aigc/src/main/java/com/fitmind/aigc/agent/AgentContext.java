package com.fitmind.aigc.agent;

/**
 * 一次提问的上下文 —— Agent 回答问题所需要的全部输入。
 *
 * <p>★ <b>{@code userId} 由编排层从 {@code UserContext} 放进来，绝不来自请求参数。</b>
 * 这是 M6-4 工具调用安全的地基：工具方法不接收 userId，
 * 它需要用户身份时从这里取 —— 于是"模型编造一个 userId 去查别人的数据"这件事
 * 在结构上就不可能发生（模型压根没有给 userId 的通道）。</p>
 *
 * @param sessionId 会话id
 * @param requestId 本轮请求id（工具结果的暂存与回传用它做 key）
 * @param userId    当前登录用户（来自 UserContext）
 * @param question  用户本次提问
 */
public record AgentContext(String sessionId, String requestId, Long userId, String question) {
}
