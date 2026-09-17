package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * AI 对话完成事件（M6-6）—— fm-aigc 发给数据中心（fm-data）。
 *
 * <p>★ <b>它带的是"指标"，不是"内容"</b>：token、首字延迟、工具调用次数、
 * 是否降级。对话正文留在 fm-aigc 自己的库里（那是产品的数据），
 * 而数据中心要的是<b>"今天 AI 花了多少钱、快不快、稳不稳"</b>这类可聚合的量。
 * 把正文也发过去会让消息体积无谓地变大，而且数据中心根本没有地方放它。</p>
 *
 * <p>★ 为什么需要 {@code requestId}：MQ 是 at-least-once，同一条消息可能被投递两次，
 * 而统计动作是<b>累加</b>（INCR）—— 累加对重复投递零抵抗，重复一次就多算一轮对话。
 * 所以消费端要用它做去重键（与项目里所有"累加型"事件同一套手法）。</p>
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class AiChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本轮请求 id（幂等键） */
    private String requestId;

    private Long userId;

    private String sessionId;

    /** 由哪个 Agent 产出（route / recommend / consult / buy / knowledge / simple） */
    private String agentType;

    /** 实际使用的编排策略（ROUTE / ENHANCE / SIMPLE） */
    private String chatType;

    /** 模型通道：deepseek / mock */
    private String provider;

    private Integer promptTokens;

    private Integer completionTokens;

    /** 首字延迟（毫秒）—— AI 产品体验的核心指标 */
    private Integer firstTokenMs;

    private Integer totalMs;

    /** 本轮工具调用次数 */
    private Integer toolCallCount;

    /** 本轮检索命中条数 */
    private Integer ragHits;

    /** 送进 prompt 的历史消息条数（多轮是否生效的观测） */
    private Integer historyMessages;

    /** 是否走了降级（模型不可用时的兜底回答） */
    private Boolean degraded;

    /** 本轮是否触发了上下文压缩 */
    private Boolean compressed;

    /**
     * 业务发生时间（毫秒时间戳）。
     *
     * <p>★ 带上它是为了<b>跨零点的正确性</b>：对话在 23:59:59 结束、
     * 消息在 00:00:01 被消费，用"到达时间"会把昨天的使用记到今天头上。
     * （M5 里体测、退款事件都有同样的问题，所以那里也是传业务时间。）</p>
     */
    private Long timestamp;
}
