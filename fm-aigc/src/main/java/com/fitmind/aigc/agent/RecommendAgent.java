package com.fitmind.aigc.agent;

import com.fitmind.aigc.prompt.PromptManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 课程推荐 Agent —— 按身体档案与训练目标推荐课程。

 * <p>★ 这个 Agent 是 M5 与 M6 的接合点：它需要读<b>体测数据</b>
 * （体脂率、BMR/TDEE、训练目标、伤病史、每周训练次数）才能给出
 * "适合你"的推荐，而不是一份通用榜单。M6-4 会通过 {@code BodyTools} 接上 ——
 * 那个工具从 {@code UserContext} 取 userId，不接受模型传参。</p>
 */
@Component
public class RecommendAgent extends AbstractAgent {

    public RecommendAgent(ChatClient chatClient, PromptManager promptManager) {
        super(chatClient, promptManager);
    }

    @Override
    public AgentType type() {
        return AgentType.RECOMMEND;
    }
}
