package com.fitmind.aigc.memory;

import cn.hutool.core.util.StrUtil;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import com.fitmind.aigc.prompt.PromptManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文压缩 —— 把"很久以前的对话"变成一段摘要。
 *
 * <p>★★ <b>先回答一个必然会被问到的问题：既然送进 prompt 的只有最近 N 轮，
 * 那压缩到底有什么用？</b></p>
 *
 * <p>答案是：<b>没有压缩，AI 的"记忆长度"就等于"窗口大小"</b>。
 * 只送最近 6 轮的话，聊到第 30 轮时它对第 1 轮聊过什么一无所知 ——
 * 而"我上次说我膝盖不舒服"这种信息恰恰是最该被记住的。
 * 压缩把窗口外的内容<b>折成一段话塞回窗口内</b>，
 * 于是"窗口"是 6 轮，但"记忆"可以覆盖整个会话。</p>
 *
 * <p>★★ <b>与常见实现的关键差异（修正它的 A3 缺陷）：</b>
 * 它靠"模型输出里出现了 agentName"作为压缩触发条件，而这个条件在
 * <b>流式路径下永远不成立</b>（流式输出是逐片的，收尾那段带 agentName 的文本压根不存在），
 * 于是"开了压缩"和"没开"在线上的行为完全一样，还不报错。</p>
 *
 * <p>本项目按<b>可观测的数量指标</b>触发（消息条数 / 字符数），
 * 与"模型输出了什么"没有关系 —— 于是同步与流式必然都生效。
 * 而本项目实际上只有一条路径：非流式接口就是流式的事件消费者
 * （见 {@code ChatServiceImpl.chat}），它天然复用了这里的一切。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    /** 待压缩对话块的起始标记（Mock 通道据此抽取内容生成确定性摘要） */
    private static final String TRANSCRIPT_HEADER = AigcConstants.COMPRESS_TRANSCRIPT_HEADER;

    private final ChatMemoryStore store;
    private final ChatClient chatClient;
    private final PromptManager promptManager;
    private final AigcProperties props;

    /**
     * 按需压缩。绝大多数轮次会直接返回 {@link CompressOutcome#none()}。
     *
     * <p>⚠️ 压缩是<b>同步</b>发生在首字之前的一次额外模型调用，
     * 所以它直接加在用户的首字等待上。因此：
     * ① 只在真的超阈值时才做；② 给它一个比主回答短得多的独立超时；
     * ③ 失败只记日志（下轮再试），绝不因为它把这一轮对话打挂。</p>
     */
    public CompressOutcome compressIfNeeded(String sessionId) {
        AigcProperties.Memory.Compress cfg = props.getMemory().getCompress();
        if (!props.getMemory().isEnabled() || !cfg.isEnabled() || StrUtil.isBlank(sessionId)) {
            return CompressOutcome.none();
        }
        int keep = Math.max(2, cfg.getKeepMessages());// 至少保留 2 条（1 轮对话）
        long total = store.size(sessionId);
        if (total <= keep) {
            return CompressOutcome.none();
        }

        // 两个触发条件是"或"的关系：
        // · 条数超了 → 对话够长，早期内容该进摘要（这是主要条件）；
        // · 字符数超了 → 条数没到但每条都很长（长回答），窗口照样会被撑爆。
        //   只判条数的话，"问 5 个问题、每个都得到 2000 字回答"就会绕开压缩。
        List<MemoryMessage> all = store.oldest(sessionId, (int) total);
        if (all.isEmpty()) {
            return CompressOutcome.none();
        }
        int totalChars = CompressOutcome.charsOf(all);
        boolean byCount = total > cfg.getTriggerMessages(); // 超过16条，就压缩
        boolean byChars = totalChars > cfg.getTriggerChars(); // 超过3000字，就压缩
        // 两个条件都未触发，就不压缩
        if (!byCount && !byChars) {
            return CompressOutcome.none();
        }

        int compressCount = all.size() - keep;
        if (compressCount <= 0) {
            return CompressOutcome.none();
        }
        //前12条消息，压缩成摘要
        List<MemoryMessage> outdated = all.subList(0, compressCount);
        long start = System.currentTimeMillis();
        try {
            String previous = store.summary(sessionId);
            //调一次模型做摘要
            SummaryResult result = summarize(previous, outdated);
            if (result == null || StrUtil.isBlank(result.text())) {
                return CompressOutcome.none();
            }
            //旧摘要 + 本次摘要 → 新摘要
            String summary = merge(previous, result.text());
            store.saveSummary(sessionId, summary);
            // ★ 顺序很重要：**先存摘要、再删原文**。
            //   反过来的话，如果写摘要失败，那批原文已经被删了 —— 信息就真的丢了。
            //   （"先写后删"是所有"迁移 + 清理"类操作的通用顺序）
            store.keepLast(sessionId, keep);
            long cost = System.currentTimeMillis() - start;
            log.info("★ 上下文压缩完成：sessionId={}, 压缩 {} 条（触发：条数={} 字符={}），"
                            + "摘要 {} 字，剩余 {} 条，压缩调用 {}/{} tokens，耗时 {}ms",
                    sessionId, compressCount, byCount, byChars, summary.length(), store.size(sessionId),
                    result.promptTokens(), result.completionTokens(), cost);
            return new CompressOutcome(true, compressCount, result.promptTokens(),
                    result.completionTokens(), summary.length(), cost);
        } catch (Exception e) {
            // 压缩失败不是错误，只是"这次没压成" —— 下一轮还会再试。
            // 抛出去的话，用户会看到一个跟他的问题毫无关系的报错
            log.warn("上下文压缩失败（本轮跳过，下一轮会重试）：sessionId={}, 原因={}", sessionId, e.getMessage());
            return CompressOutcome.none();
        }
    }

    /**
     * 调一次模型做摘要。
     *
     * <p>★ 用 {@code stream().collectList().block(timeout)} 而不是 {@code call()}：
     * 同步的 {@code call()} 没有超时控制，模型侧挂住时这个线程会一直等 ——
     * 而它占的正是"用户请求线程"，等于把 Tomcat 线程池也一起拖住。
     * 这里显式 block 一个短超时，最坏情况是"这轮不压缩了"。</p>
     */
    private SummaryResult summarize(String previous, List<MemoryMessage> outdated) {
        StringBuilder user = new StringBuilder();
        if (StrUtil.isNotBlank(previous)) {
            user.append("【已有摘要】\n").append(previous).append("\n\n");
        }
        user.append(TRANSCRIPT_HEADER).append('\n');
        for (MemoryMessage m : outdated) {
            user.append(m.role()).append(": ")
                    .append(StrUtil.brief(m.content(), 400)).append('\n');
        }

        List<ChatResponse> parts = chatClient.prompt()
                // ★ 摘要提示词也是三级来源（Nacos → 本地文件 → 代码兜底），
                //   和其他提示词走同一套机制 —— 调摘要风格不用改代码
                .system(promptManager.get(AigcConstants.COMPRESS_PROMPT_KEY))
                .user(user.toString())
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofMillis(props.getMemory().getCompress().getTimeoutMs()));

        if (parts == null || parts.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (ChatResponse r : parts) {
            if (r.getResult() != null && r.getResult().getOutput() != null) {
                text.append(StrUtil.nullToEmpty(r.getResult().getOutput().getText()));
            }
        }
        int[] usage = usageOf(parts);
        return new SummaryResult(text.toString().trim(), usage[0], usage[1]);
    }

    /**
     * 旧摘要 + 本次摘要 → 新摘要。
     *
     * <p>★ 超长时<b>截断尾部、保留前部</b>：摘要越靠前的内容越早、越无法从别处找回
     * （近期对话还有原文可看，早期对话删掉就只剩摘要了）。
     * 这也是"截断策略要跟着数据语义走"的一个例子 —— 和时间序列正好相反。</p>
     */
    private String merge(String previous, String current) {
        int max = props.getMemory().getCompress().getMaxSummaryChars();
        String merged = StrUtil.isBlank(previous)
                ? current
                : previous + "\n（后续）" + current;
        return merged.length() <= max ? merged : merged.substring(0, max);
    }

    /** 一次摘要调用的产物（正文 + 它自己的 token 开销） */
    private record SummaryResult(String text, int promptTokens, int completionTokens) {
    }

    /** 调试用：把一批消息渲染成可读文本 */
    static String render(List<MemoryMessage> messages) {
        return messages.stream()
                .map(m -> m.role() + ": " + StrUtil.brief(m.content(), 60))
                .collect(Collectors.joining("\n"));
    }

    /** 从 ChatResponse 里取 usage（Mock 与真实模型都把 usage 放在 metadata 里） */
    private static int[] usageOf(List<ChatResponse> parts) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            ChatResponse r = parts.get(i);
            if (r.getMetadata() != null && r.getMetadata().getUsage() != null) {
                Usage u = r.getMetadata().getUsage();
                return new int[]{u.getPromptTokens(), u.getCompletionTokens()};
            }
        }
        return new int[]{0, 0};
    }
}
