package com.fitmind.aigc.agent;

import com.fitmind.aigc.client.OrderClient;
import com.fitmind.aigc.client.SearchClient;
import com.fitmind.aigc.prompt.PromptManager;
import com.fitmind.aigc.tool.CourseTools;
import com.fitmind.aigc.tool.OrderTools;
import com.fitmind.api.client.course.CourseClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
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

    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final OrderClient orderClient;

    public BuyAgent(ChatClient chatClient, PromptManager promptManager,
                    SearchClient searchClient, CourseClient courseClient, OrderClient orderClient,
                    ToolCallingManager toolCallingManager) {
        super(chatClient, promptManager, toolCallingManager);
        this.searchClient = searchClient;
        this.courseClient = courseClient;
        this.orderClient = orderClient;
    }

    @Override
    public AgentType type() {
        return AgentType.BUY;
    }

    /**
     * ★ 引导下单的两个工具顺序很关键：<b>先搜课拿到真实 id，才能下单</b>。
     * 模型如果跳过第一步直接编一个 id 去下单，工具会返回"没找到"并明确告诉它
     * 要先用 searchCourses —— 这条兜底在 {@code OrderTools} 里。
     */
    @Override
    protected Object[] tools(AgentContext context) {
        return new Object[]{
                new CourseTools(searchClient, courseClient, context.toolTrace(), context.requestId(),
                        context.userId(), context.authToken()),
                new OrderTools(orderClient, courseClient, context.toolTrace(), context.requestId(),
                        context.userId(), context.authToken()),
        };
    }
}
