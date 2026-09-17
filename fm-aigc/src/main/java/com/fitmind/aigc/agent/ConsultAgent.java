package com.fitmind.aigc.agent;

import com.fitmind.aigc.client.SearchClient;
import com.fitmind.aigc.prompt.PromptManager;
import com.fitmind.aigc.tool.CourseTools;
import com.fitmind.api.client.course.CourseClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;

/**
 * 课程咨询 Agent —— 课程详情、训练安排、注意事项。

 * <p><b>★ 与常见实现的差异：这个 Agent 不该带 RAG。</b>
 * 常见实现把向量检索挂在了咨询与推荐上，但"这门课有几节、适合什么水平、
 * 需要什么器械"是<b>实时业务数据</b>（在 fm-course 里），不是知识库里的静态知识。
 * 用向量检索去回答业务数据问题，等于拿模糊匹配去做精确查询 ——
 * 检索到的东西看起来相关，但是过期的、或者是另一门课的。
 * 这里改成走 CourseTools 查实时数据（M6-4 接），RAG 归知识问答 Agent。</p>
 */
@Component
public class ConsultAgent extends AbstractAgent {

    private final SearchClient searchClient;
    private final CourseClient courseClient;

    public ConsultAgent(ChatClient chatClient, PromptManager promptManager,
                        SearchClient searchClient, CourseClient courseClient,
                        ToolCallingManager toolCallingManager) {
        super(chatClient, promptManager, toolCallingManager);
        this.searchClient = searchClient;
        this.courseClient = courseClient;
    }

    @Override
    public AgentType type() {
        return AgentType.CONSULT;
    }

    /**
     * ★ 课程咨询走工具查<b>实时数据</b>，不走 RAG。
     *
     * <p>这是对常见实现的一处修正：它的 RAG 恰好挂在咨询/推荐上，
     * 于是"这门课多少钱"会变成"从知识库里捞一段看起来相关的文字"——
     * 而知识库里的价格是<b>过期快照</b>，用户会拿到一个自信但错误的答案。
     * 该查库的查库，该查知识库的查知识库，两件事不能混。</p>
     */
    @Override
    protected Object[] tools(AgentContext context) {
        return new Object[]{
                new CourseTools(searchClient, courseClient, context.toolTrace(), context.requestId(),
                        context.userId(), context.authToken()),
        };
    }
}
