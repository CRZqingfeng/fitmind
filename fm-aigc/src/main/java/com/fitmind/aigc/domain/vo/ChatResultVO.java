package com.fitmind.aigc.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

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

    @Schema(description = "由哪个 Agent 产出（M6-2 起有值）")
    private String agentType;

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
     * 是否发生了降级（模型超时/不可用 → 返回了兜底文案而不是 500）。
     *
     * <p>★ 把它显式返回给前端，而不是"让前端自己猜这段文案是不是错误"：
     * 降级时前端该显示"重试"按钮，正常回答时不该显示。
     * 靠文案内容判断是脆弱的（改一句文案就失效了）。</p>
     */
    @Schema(description = "是否降级（模型不可用时的兜底回答）")
    private Boolean degraded;
}
