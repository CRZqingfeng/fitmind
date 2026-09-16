package com.fitmind.aigc.agent;

import com.fitmind.aigc.prompt.PromptManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 购买引导 Agent —— 帮用户完成下单。

 * <p>★ 这是唯一会<b>产生业务动作</b>的 Agent，所以它的安全要求最高：
 * 它只能调 {@code OrderTools} 生成"预下单"（一个待用户确认的订单），
 * <b>不能自己完成支付</b>；金额一律由 fm-trade 从库里算，不接受模型给的价格。
 * M6-4 会把这几条约束落在工具方法的签名上（工具不接收 userId、不接收金额）。</p>
 */
@Component
public class BuyAgent extends AbstractAgent {

    public BuyAgent(ChatClient chatClient, PromptManager promptManager) {
        super(chatClient, promptManager);
    }

    @Override
    public AgentType type() {
        return AgentType.BUY;
    }
}
