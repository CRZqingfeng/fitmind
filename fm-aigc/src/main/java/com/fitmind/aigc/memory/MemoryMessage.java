package com.fitmind.aigc.memory;

import com.fitmind.aigc.constants.AigcConstants;

/**
 * 记忆里的一条消息。
 *
 * <p>★ 为什么不用 Spring AI 的 {@code UserMessage}/{@code AssistantMessage} 直接当存储结构：
 * 它们是<b>给模型看的</b>类型（有自己的 metadata、messageType 等），
 * 而这里要存的是<b>序列化到 Redis 的最小二元组</b>。
 * 用业务自己的 record 存，取出来再"翻译"成模型消息 ——
 * 于是"存储格式"和"模型协议"这两件会各自变化的事被分开了
 * （Spring AI 升级时改的是翻译那一行，不是存量数据）。</p>
 */
public record MemoryMessage(String role, String content) {

    public static MemoryMessage user(String content) {
        return new MemoryMessage(AigcConstants.ROLE_USER, content);
    }

    public static MemoryMessage assistant(String content) {
        return new MemoryMessage(AigcConstants.ROLE_ASSISTANT, content);
    }

    public boolean isUser() {
        return AigcConstants.ROLE_USER.equals(role);
    }

    public boolean isAssistant() {
        return AigcConstants.ROLE_ASSISTANT.equals(role);
    }
}
