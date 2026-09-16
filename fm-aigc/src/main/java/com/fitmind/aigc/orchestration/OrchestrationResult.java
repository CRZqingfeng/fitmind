package com.fitmind.aigc.orchestration;

import com.fitmind.aigc.agent.AgentType;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 一次编排的结果：<b>谁来回答 + 模型的响应流 + 编排本身的开销</b>。
 *
 * <p>把 {@code agentType} 单独返回（而不是让上层从流里猜），
 * 是因为它有两个必须用到的地方：
 * ① 落库到 {@code chat_message.agent_type} —— 复盘时要知道"这条回答是谁产出的"；
 * ② 验收与对比 —— 三种编排策略的效果差异，就是靠这个字段区分出来的。</p>
 *
 * <p>★ {@code extra*Tokens} 是<b>编排动作本身</b>的开销（目前只有 ROUTE 的路由调用会产生）。
 * 把它和回答的 token 分开带出来，是为了让上层能算出"这一轮总共花了多少" ——
 * 否则 ROUTE 的成本会被系统性低估一次模型调用，
 * 而"多 Agent 到底贵多少"恰恰是这个策略最需要被说清楚的地方。</p>
 *
 * @param agentType             实际处理这次提问的 Agent；{@code null} 表示没经过 Agent（SIMPLE）
 * @param responses             模型的原生响应流（上层负责映射成 SSE 事件、提 usage、落库）
 * @param extraPromptTokens     编排动作产生的额外输入 token
 * @param extraCompletionTokens 编排动作产生的额外输出 token
 */
public record OrchestrationResult(AgentType agentType, Flux<ChatResponse> responses,
                                  int extraPromptTokens, int extraCompletionTokens) {

    /** 没有额外编排开销的普通结果 */
    public static OrchestrationResult of(AgentType agentType, Flux<ChatResponse> responses) {
        return new OrchestrationResult(agentType, responses, 0, 0);
    }

    /** 落库与展示用的标签 */
    public String label() {
        return agentType == null ? "simple" : agentType.key();
    }
}
