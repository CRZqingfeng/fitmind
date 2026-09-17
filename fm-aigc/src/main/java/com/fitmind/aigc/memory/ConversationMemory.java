package com.fitmind.aigc.memory;

import cn.hutool.core.util.StrUtil;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆门面 —— 对话主干只跟它打交道。
 *
 * <p>它回答两个问题：<b>① 这一轮该带上哪些历史（{@link #promptHistory}）；
 * ② 历史是不是该压缩了（{@link #compressIfNeeded}）。</b>
 * 具体怎么存（Redis 结构）、怎么压（调模型做摘要）分别下沉到
 * {@link ChatMemoryStore} 与 {@link ContextCompressor}。</p>
 *
 * <p>★ 组装成 Spring AI 的 {@code Message} 列表而不是字符串：
 * 多轮对话的正确形态是"多条消息"，不是"把历史拼成一段文本塞进用户消息"。
 * 后者会让模型分不清"哪句是我说的、哪句是用户说的"，
 * 也会把 history 和当前提问揉成一条 —— 而这个错误在 prompt 里看不出来。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMemory {

    private final ChatMemoryStore store;
    private final ContextCompressor compressor;
    private final AigcProperties props;

    /** 记一条消息（用户提问 / 助手回答）。写失败只记日志，见 {@link ChatMemoryStore#append} */
    public void record(String sessionId, String role, String content) {
        store.append(sessionId, role, content);
    }

    /**
     * 本轮要带给模型的历史：<b>[摘要] + [最近 N 轮原文]</b>。
     *
     * <p>摘要放在最前面（作为一条 system 消息），它代表"窗口之外的全部记忆"。
     * 顺序上必须是"摘要 → 历史 → 当前提问"：
     * 摘要讲的是更早的事，放后面会让模型以为它是刚发生的。</p>
     *
     * <p>⚠️ <b>调用时机很重要</b>：必须在"把当前提问写进记忆之前"调用，
     * 否则当前提问会既出现在历史里、又作为本轮提问出现一次 ——
     * 模型会以为自己被问了两次。见 {@code ChatServiceImpl.orchestrate} 的顺序注释。</p>
     */
    public List<Message> promptHistory(String sessionId) {
        if (!props.getMemory().isEnabled() || StrUtil.isBlank(sessionId)) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        String summary = store.summary(sessionId);
        if (StrUtil.isNotBlank(summary)) {
            messages.add(new SystemMessage(AigcConstants.SUMMARY_HEADER + "\n" + summary));
        }
        // 1 轮 = 1 问 + 1 答
        int limit = Math.max(1, props.getMemory().getRecentTurns()) * 2;
        List<MemoryMessage> recent = store.recent(sessionId, limit);
        for (MemoryMessage m : recent) {
            messages.add(m.isUser() ? new UserMessage(StrUtil.nullToEmpty(m.content()))
                    : new AssistantMessage(StrUtil.nullToEmpty(m.content())));
        }
        if (!messages.isEmpty()) {
            log.debug("会话记忆就绪：sessionId={}, 摘要={}, 历史 {} 条", sessionId,
                    summary == null ? "无" : summary.length() + " 字", recent.size());
        }
        return messages;
    }

    /** 按需压缩（见 {@link ContextCompressor}）。返回值用于可观测与断言 */
    public CompressOutcome compressIfNeeded(String sessionId) {
        return compressor.compressIfNeeded(sessionId);
    }

    /** 清空一个会话的记忆（供数据清理使用） */
    public void clear(String sessionId) {
        store.clear(sessionId);
    }

    /**
     * 当前上下文窗口的"体检报告"。
     *
     * <p>★ 为什么要暴露这个：<b>"AI 到底记得什么"是排查多轮问题的第一个问题。</b>
     * 没有它，只能靠"再问一句看它答得像不像记得"来猜；
     * 有了它，一行请求就能看到摘要、条数、TTL 与最近几条的预览。</p>
     *
     * <p>内容都是当前登录用户自己的会话，不涉及越权（归属校验在 SQL/会话查询里已做）。</p>
     */
    public Map<String, Object> view(String sessionId) {
        AigcProperties.Memory cfg = props.getMemory();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", cfg.isEnabled());
        map.put("recentTurns", cfg.getRecentTurns());
        map.put("maxMessages", cfg.getMaxMessages());
        map.put("ttlDays", cfg.getTtlDays());
        map.put("messageCount", store.size(sessionId));
        map.put("ttlSeconds", store.ttlSeconds(sessionId));
        String summary = store.summary(sessionId);
        map.put("summaryChars", summary == null ? 0 : summary.length());
        map.put("summaryPreview", summary == null ? null : StrUtil.brief(summary, 120));
        // 最近几条的预览：验收靠它断言"取回的是最新的那几条"
        List<Map<String, Object>> preview = new ArrayList<>();
        for (MemoryMessage m : store.recent(sessionId, Math.max(1, cfg.getRecentTurns()) * 2)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.role());
            item.put("preview", StrUtil.brief(StrUtil.nullToEmpty(m.content()), 40));
            preview.add(item);
        }
        map.put("recentPreview", preview);
        return map;
    }
}
