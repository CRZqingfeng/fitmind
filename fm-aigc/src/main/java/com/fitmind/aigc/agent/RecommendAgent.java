package com.fitmind.aigc.agent;

import com.fitmind.aigc.client.SearchClient;
import com.fitmind.aigc.prompt.PromptManager;
import com.fitmind.aigc.service.BodyProfileService;
import com.fitmind.aigc.tool.BodyTools;
import com.fitmind.aigc.tool.CourseTools;
import com.fitmind.api.client.course.CourseClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
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

    private final BodyProfileService bodyProfileService;
    private final SearchClient searchClient;
    private final CourseClient courseClient;

    public RecommendAgent(ChatClient chatClient, PromptManager promptManager,
                          BodyProfileService bodyProfileService,
                          SearchClient searchClient, CourseClient courseClient,
                          ToolCallingManager toolCallingManager) {
        super(chatClient, promptManager, toolCallingManager);
        this.bodyProfileService = bodyProfileService;
        this.searchClient = searchClient;
        this.courseClient = courseClient;
    }

    @Override
    public AgentType type() {
        return AgentType.RECOMMEND;
    }

    /**
     * ★ 推荐 Agent 是工具最多、也最能体现"跨模块协同"的一个：
     * 先读用户档案（M5 的体测数据）→ 再按目标搜课程（fm-search 的 ES 索引）→
     * 最后由模型把两边拼成一句人话。
     */
    @Override
    protected Object[] tools(AgentContext context) {
        return new Object[]{
                new BodyTools(bodyProfileService, context.bodyProfile(), context.toolTrace(), context.requestId()),
                new CourseTools(searchClient, courseClient, context.toolTrace(), context.requestId(),
                        context.userId(), context.authToken()),
        };
    }
}
