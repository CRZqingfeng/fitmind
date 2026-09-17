package com.fitmind.aigc.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 一轮对话的完整结果（非流式）。
 *
 * <p>★ 为什么要有一个非流式接口，而不只做 SSE：
 * <b>验收脚本需要能"一次拿到完整回答 + 本轮指标"来断言。</b>
 * 让脚本去拼 SSE 分片虽然也能做到，但"脚本自己实现一遍前端逻辑"
 * 意味着脚本的 bug 会被误判成服务的 bug。非流式接口让脚本可以直接断言
 * "回答内容"和"首字延迟 / token 数"这些本来就要落库的指标。</p>
 *
 * <p>顺带它也是给"不方便用 SSE 的调用方"（比如定时任务、其他服务）的入口。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "对话结果")
public class ChatResultVO {

    @Schema(description = "会话id")
    private String sessionId;

    @Schema(description = "本轮请求id")
    private String requestId;

    @Schema(description = "完整回答")
    private String content;

    @Schema(description = "由哪个 Agent 产出（simple 表示未经过 Agent，如 SIMPLE 策略）")
    private String agentType;

    @Schema(description = "本次使用的编排策略：ROUTE / ENHANCE / SIMPLE")
    private String chatType;

    @Schema(description = "模型通道：deepseek / mock")
    private String provider;

    @Schema(description = "输入 token")
    private Integer promptTokens;

    @Schema(description = "输出 token")
    private Integer completionTokens;

    @Schema(description = "首字延迟（毫秒）")
    private Integer firstTokenMs;

    @Schema(description = "总耗时（毫秒）")
    private Integer totalMs;

    /**
     * 编排动作（路由）额外消耗的输入 token。
     *
     * <p>★ 单独给出来，因为它是<b>ROUTE 策略相比 ENHANCE 多花的那部分</b>。
     * 非流式返回的 {@code promptTokens} 已经是"回答 + 路由"的<b>总量</b>，
     * 这个字段是明细 —— 做三种策略的成本对比时，看的就是它。</p>
     */
    @Schema(description = "其中路由调用消耗的输入 token（仅 ROUTE 策略非 0）")
    private Integer routePromptTokens;

    /**
     * 是否发生了降级（模型超时/不可用 → 返回了兜底文案而不是 500）。
     *
     * <p>★ 把它显式返回给前端，而不是"让前端自己猜这段文案是不是错误"：
     * 降级时前端该显示"重试"按钮，正常回答时不该显示。
     * 靠文案内容判断是脆弱的（改一句文案就失效了）。</p>
     */
    @Schema(description = "是否降级（模型不可用时的兜底回答）")
    private Boolean degraded;

    // ==================== RAG（M6-3） ====================

    /**
     * 引用来源（结构化）。
     *
     * <p>★ 它<b>不来自模型输出</b>，而是直接来自检索结果 ——
     * 这一点很重要：如果靠模型自己说"依据是《XX》"，它可能漏、可能把标题写错，
     * 而<b>错的引用比没有引用更糟</b>（用户会相信一个错误的出处）。
     * 结构：{@code {mode, hybrid, candidateCount, injectedCount, searchMs, sources:[{title, section, score, preview}]}}</p>
     */
    @Schema(description = "检索引用来源与统计（结构化，非模型生成）")
    private Map<String, Object> rag;

    @Schema(description = "检索模式：rag / degraded / skipped / off")
    private String ragMode;

    @Schema(description = "实际注入 prompt 的依据条数")
    private Integer ragHits;

    @Schema(description = "检索候选条数（向量 + 关键词，融合前）")
    private Integer ragCandidates;

    @Schema(description = "检索耗时（毫秒）")
    private Integer ragSearchMs;

    // ==================== Tool Calling（M6-4） ====================

    /**
     * 前端卡片数据（课程列表 / 待支付订单）。
     *
     * <p>★ 与 {@link #rag} 一样，它<b>不来自模型输出</b> —— 是工具执行时直接攒下来的原始数据。
     * 靠模型"复述"课程价格和订单号，等于把展示数据的正确性交给一次生成，
     * 而这类数字一旦错一个，用户就会照着错的去付款。</p>
     */
    @Schema(description = "工具产生的卡片数据（前端渲染用，非模型生成）")
    private Object cards;

    /**
     * 工具调用记录。
     *
     * <p>★ 排查"AI 为什么答非所问"时，第一件要看的事就是<b>它到底调了什么工具、参数是什么</b>。
     * 没有这个字段，就只能靠回答内容反推。</p>
     */
    @Schema(description = "工具调用记录（名称/参数/结果摘要/耗时）")
    private Map<String, Object> toolCalls;

    @Schema(description = "本轮调用的工具名列表")
    private java.util.List<String> toolNames;

    @Schema(description = "本轮工具调用次数")
    private Integer toolCallCount;

    // ==================== 会话记忆与打断（M6-5） ====================

    /**
     * 本轮是否被停止。
     *
     * <p>★ 前端据此区分"回答完整"与"回答被停止" —— 前者不该提示重试，
     * 后者应该给一个"继续生成"的入口。这与 STOP 事件存在的理由一脉相承：
     * <b>让客户端能区分"正常结束"和"出事了"。</b></p>
     */
    @Schema(description = "本轮是否被停止（用户主动停止 / 被新一轮接管）")
    private Boolean stopped;

    /**
     * 本轮带上了几条历史消息（含摘要那一条）。
     *
     * <p>★ 把它暴露出来是因为<b>多轮对话最容易"看起来生效、其实没生效"</b>：
     * 模型即使没有历史也能答得像记得（它会根据当前提问猜上下文）。
     * 有了这个数字，"这轮到底带了几条历史"就不再需要靠猜。</p>
     */
    @Schema(description = "本轮送进 prompt 的历史消息条数（含摘要）")
    private Integer memoryHistory;

    /** 本轮是否触发了上下文压缩（压缩会额外消耗一次模型调用与一段延迟） */
    @Schema(description = "本轮是否触发上下文压缩")
    private Boolean compressed;

    /** 本轮被压缩掉的消息条数 */
    @Schema(description = "本轮被压缩进摘要的消息条数")
    private Integer compressedCount;

    /**
     * 本轮压缩的耗时（毫秒）。
     *
     * <p>★ 为什么要单独暴露它：压缩是<b>同步发生</b>在首字之前的一次额外模型调用，
     * 所以它直接加在用户的首字等待上。有了这个数字，"这一轮为什么慢"就能被拆开看
     * （是模型慢、还是我们在首字之前又多调了一次模型）。</p>
     */
    @Schema(description = "本轮上下文压缩耗时（毫秒）")
    private Integer compressMs;

    // ==================== 限流（M6-6） ====================

    /**
     * 本轮是否被限流拒绝。
     *
     * <p>★ 被限流时 {@code content} 是一段<b>说人话的助手消息</b>（不是 500、不是空），
     * 前端可以像正常回答一样渲染它，只是标注一下"额度用完了"。</p>
     */
    @Schema(description = "本轮是否被限流拒绝")
    private Boolean limited;

    /** 限流原因：CHAT_LIMIT（今日轮数用完）/ TOKEN_LIMIT / CONCURRENCY（上一条还在生成） */
    @Schema(description = "限流原因")
    private String limitReason;
}
