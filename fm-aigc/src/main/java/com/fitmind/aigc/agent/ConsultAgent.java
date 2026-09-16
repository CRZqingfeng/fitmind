package com.fitmind.aigc.agent;

import com.fitmind.aigc.prompt.PromptManager;
import org.springframework.ai.chat.client.ChatClient;
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

    public ConsultAgent(ChatClient chatClient, PromptManager promptManager) {
        super(chatClient, promptManager);
    }

    @Override
    public AgentType type() {
        return AgentType.CONSULT;
    }
}
