package com.fitmind.aigc.agent;

import com.fitmind.aigc.prompt.PromptManager;
import com.fitmind.aigc.rag.KnowledgeRetrievalAdvisor;
import com.fitmind.aigc.rag.KnowledgeRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 健身知识问答 Agent —— ★ 真正的 RAG 落点。
 *
 * <p>处理"训练/营养/康复"这类<b>知识性</b>问题（"增肌要不要做有氧"
 * "深蹲膝盖疼怎么办"），答案来自自建的健身知识库，不是某门课的字段。</p>
 *
 * <p>★ 与常见实现的差异：常见实现的 {@code KnowledgeAgent} <b>没有接向量库</b>
 * （RAG 反而挂在咨询/推荐上），这里的职责归属才是对的 ——
 * 需要"从知识库里找依据"的是知识问答，需要"从业务库查精确字段"的是咨询。
 * 把 RAG 挂在咨询上会造成一个很隐蔽的后果：
 * <b>课程价格这类"必须查实时数据"的问题，会变成"从知识库里捞一段看起来相关的文字"</b>，
 * 而知识库里的价格是过期的快照 —— 用户会拿到一个自信但错误的答案。</p>
 *
 * <p><b>检索参数</b>（真实问题 / topK=4 / 相似度阈值 / 按目标过滤 / 关键词+向量混合）
 * 都在 {@link KnowledgeRetriever} 里，全部可配 —— 这一层的职责只有一句：
 * <b>把这个 Advisor 挂上去</b>。</p>
 */
@Component
public class KnowledgeAgent extends AbstractAgent {

    private final KnowledgeRetriever retriever;

    public KnowledgeAgent(ChatClient chatClient, PromptManager promptManager, KnowledgeRetriever retriever,
                        ToolCallingManager toolCallingManager) {
        super(chatClient, promptManager, toolCallingManager);
        this.retriever = retriever;
    }

    @Override
    public AgentType type() {
        return AgentType.KNOWLEDGE;
    }

    /**
     * 挂上知识检索 Advisor。
     *
     * <p>★ 每次请求 new 一个 Advisor 实例（而不是做成单例 Bean）：因为要把它
     * <b>这一轮</b>的检索轨迹（{@link com.fitmind.aigc.rag.RagTrace}）传进去。
     * 做成单例就得把 per-request 状态藏进 ThreadLocal 或 context Map ——
     * 那两种做法在下游并发或线程切换时都会出错，而"每请求一个小对象"没有成本。</p>
     */
    @Override
    protected List<Advisor> advisors(AgentContext context) {
        return List.of(new KnowledgeRetrievalAdvisor(
                retriever, context.retrieval(), context.ragTrace(), 0));
    }
}
