package com.fitmind.aigc.agent;

/**
 * 一次意图路由的结果。
 *
 * <p>★ 除了"路由到哪"，还要带上<b>这次路由本身消耗的 token</b>。
 * 理由：ROUTE 策略相比 ENHANCE 多了一次模型调用，而这笔开销必须能被看见 ——
 * 否则"多 Agent 多花的是什么"就只能靠嘴说。
 * 把它记进本轮的总 token 里，"ROUTE 比 ENHANCE 贵多少"就是一个可以直接报出来的数字。</p>
 *
 * @param type             路由到的 Agent 类型
 * @param promptTokens     本次路由调用的输入 token
 * @param completionTokens 本次路由调用的输出 token
 */
public record RouteResult(AgentType type, int promptTokens, int completionTokens) {

    /** 路由失败时的兜底结果（token 记 0：这次调用没成功，不该算进成本） */
    public static RouteResult fallback() {
        return new RouteResult(AgentType.KNOWLEDGE, 0, 0);
    }
}
