package com.fitmind.aigc.rag;

import com.fitmind.aigc.agent.AgentContext;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识检索 Advisor —— RAG 的"增强"那一半。
 *
 * <p>它挂在 {@code ChatClient} 的 Advisor 链上：<b>请求发出去之前</b>检索知识库、
 * 把命中的内容拼进 prompt；检索不到就换一句"没有资料"的说明进去。
 * 上层（Agent / 编排器 / Service）完全不需要知道检索发生过。</p>
 *
 * <p>★ <b>为什么做成 Advisor 而不是在 Agent 里写几行检索代码</b>：
 * ① 检索这件事对"哪一次模型调用"是透明的，工具调用、多轮、流式都不用再写一遍；
 * ② 顺序有保证（Advisor 链是有序的），将来加"会话记忆""上下文压缩"时
 *    不用手工安排先后；③ 换掉检索策略只改这一处。
 * 这也正是 Spring AI 把 RAG 做成 Advisor 的原因 —— 保留它的形态，只是
 * 把官方 {@code QuestionAnswerAdvisor} 换成本实现，因为我们需要它多给一样东西：
 * <b>把命中的来源带出来给前端</b>（官方实现只把内容拼进 prompt，不给结构化来源）。</p>
 *
 * <p><b>两个刻意的安全/正确性选择</b>：
 * <ul>
 *   <li><b>检索到的内容拼进【用户消息】而不是 system 提示词</b>。资料是"外部输入"，
 *       而 system 是"我方指令" —— 把外部内容放进 system，
 *       等于给它比用户指令更高的权威级别；一旦语料被污染（比如将来支持用户投稿），
 *       这里面就能藏指令。<b>按信任级分层，别按方便程度分层。</b></li>
 *   <li><b>资料块带显式标记</b>（{@code 【参考资料】/【用户问题】}），
 *       模型能清楚区分"依据"和"提问"；同时验收脚本可以据此断言
 *       "检索到的内容确实进了 prompt" —— 这是直接证据。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class KnowledgeRetrievalAdvisor implements BaseAdvisor {

    private final KnowledgeRetriever retriever;
    private final AgentContext.RetrievalOptions options;
    private final RagTrace trace;
    private final int order;

    public KnowledgeRetrievalAdvisor(KnowledgeRetriever retriever, AgentContext.RetrievalOptions options,
                                     RagTrace trace, int order) {
        this.retriever = retriever;
        this.options = options;
        this.trace = trace;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        String question = lastUserText(prompt);
        if (question == null || question.isBlank()) {
            trace.skip("没有可用的用户提问");
            return request;
        }
        // ★ 这一步是**同步阻塞**的（要拿到检索结果才能组装 prompt），
        //   所以它会体现在首字延迟上 —— 与 ROUTE 的路由调用是同一类成本，
        //   验收里"接 RAG 之后首字延迟多了多少"就有据可查。
        //
        // ⚠️ 且它**每轮只做一次**：工具调用会让 Advisor 链被多次穿过，
        //   不缓存的话同一个问题会被检索 3~4 遍（浪费 embedding、覆盖溯源）。
        //   问题没变 → 结果必然相同 → 缓存安全。
        List<Document> docs;
        if (trace.isRetrieved()) {
            docs = trace.cachedDocs();
        } else {
            docs = retriever.retrieve(question, options, trace);
            trace.cacheDocs(docs);
        }
        Prompt augmented = augment(prompt, docs, question);
        return request.mutate().prompt(augmented).build();
    }

    /**
     * 后置处理：这里不做改写。
     *
     * <p>★ 引用来源<b>没有</b>在回答正文里硬拼一段"依据：《XX》"。
     * 理由：那要靠模型自己生成，它可能漏、可能写错标题 ——
     * 而"引用"这件事一旦不准，比没有引用更糟（用户会相信错的出处）。
     * 所以来源走<b>结构化的 PARAM 事件</b>，由前端渲染成"参考资料"卡片，
     * 数据直接来自检索结果本身，不经过模型的转述。</p>
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return "KnowledgeRetrievalAdvisor";
    }

    // ==================== 内部 ====================

    private Prompt augment(Prompt prompt, List<Document> docs, String question) {
        StringBuilder sb = new StringBuilder();
        if (docs.isEmpty()) {
            // ★★ 关键分流：**检索失败**和**检索到空**都是 0 条，但要说完全不同的话。
            //   · 失败（ES 不通 / 索引挂了）→ 照常回答，只说"这次没能查证"
            //   · 为空（查过了、确实没有）→ 拒答，不凭记忆编
            //   混为一谈的后果是双向的：要么在会的地方装不会，要么在没有依据时张口就来。
            sb.append(trace.isFailed()
                    ? AigcConstants.RAG_UNAVAILABLE_NOTICE
                    : AigcConstants.RAG_EMPTY_NOTICE);
        } else {
            sb.append(AigcConstants.RAG_CONTEXT_HEADER).append('\n');
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                sb.append('[').append(i + 1).append("] 《")
                        .append(meta(doc, AigcConstants.META_TITLE)).append('》');
                String section = meta(doc, AigcConstants.META_SECTION);
                if (!section.isBlank()) {
                    sb.append(" > ").append(section);
                }
                sb.append('\n').append(doc.getText()).append("\n\n");
            }
            sb.append("以上资料来自技能知识库，请只依据它们作答；"
                    + "资料没有覆盖的部分，直接说明知识库里没有，不要自行补充。\n\n");
        }
        sb.append(AigcConstants.RAG_CONTEXT_FOOTER).append('\n').append(question);

        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) {
            messages.add(new UserMessage(sb.toString()));
        } else {
            messages.set(lastUser, new UserMessage(sb.toString()));
        }
        return new Prompt(messages, prompt.getOptions());
    }

    private String lastUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.getText();
            }
        }
        return "";
    }

    private String meta(Document doc, String key) {
        Object value = doc.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
