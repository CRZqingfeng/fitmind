package com.fitmind.aigc.agent;

import com.fitmind.aigc.prompt.PromptManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 健身知识问答 Agent —— ★ 真正的 RAG 落点。

 * <p>处理"训练/营养/康复"这类<b>知识性</b>问题（"增肌要不要做有氧"
 * "深蹲膝盖疼怎么办"），答案来自自建的健身知识库，不是某门课的字段。</p>

 * <p>★ 与常见实现的差异：常见实现的 {@code KnowledgeAgent} <b>没有接向量库</b>
 * （RAG 反而挂在咨询/推荐上），这里的职责归属才是对的 ——
 * 需要"从知识库里找依据"的是知识问答，需要"从业务库查精确字段"的是咨询。</p>

 * <p>M6-3 会给它挂上 {@code QuestionAnswerAdvisor}：真实的检索 query
 * （不是空串）+ topK + 相似度阈值 + 按用户目标过滤 metadata，
 * 并在回答里带上引用来源。</p>
 */
@Component
public class KnowledgeAgent extends AbstractAgent {

    public KnowledgeAgent(ChatClient chatClient, PromptManager promptManager) {
        super(chatClient, promptManager);
    }

    @Override
    public AgentType type() {
        return AgentType.KNOWLEDGE;
    }
}
