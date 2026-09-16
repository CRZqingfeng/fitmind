package com.fitmind.aigc.agent;

import cn.hutool.core.date.DateUtil;
import com.fitmind.aigc.prompt.PromptManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Agent 模板基类 —— 把"组装一次模型调用"的公共部分固定下来。
 *
 * <p>子类只需要回答三个问题：<b>我是谁（type）/ 我的提示词怎么来（systemTemplate）/
 * 我有哪些工具与增强（tools / advisors）</b>。
 * "怎么发起调用、怎么把几个部件拼起来"由基类负责，不让每个 Agent 各写一遍。</p>
 */
@Slf4j
public abstract class AbstractAgent implements Agent {

    protected final ChatClient chatClient;
    protected final PromptManager promptManager;

    protected AbstractAgent(ChatClient chatClient, PromptManager promptManager) {
        this.chatClient = chatClient;
        this.promptManager = promptManager;
    }

    @Override
    public Flux<ChatResponse> process(AgentContext context) {
        String template = systemTemplate();
        log.debug("Agent[{}] 处理提问：sessionId={}, 提示词 {} 字",
                type().key(), context.sessionId(), template == null ? 0 : template.length());
        return request(context).stream().chatResponse();
    }

    /**
     * 组装一次模型请求。
     *
     * <p>★ 每一步都是<b>有条件地</b>加：Spring AI 的 {@code tools()} 与
     * {@code advisors()} 在收到空集合时的行为跟版本相关（有的版本会直接断言失败），
     * 而"这个 Agent 没有工具"是完全正常的状态。所以宁可多写两行 if，
     * 也不要依赖"空集合应该没事"这个假设 —— 那类问题会在换版本时突然冒出来。</p>
     */
    protected ChatClient.ChatClientRequestSpec request(AgentContext context) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                // system 里用 {now} 之类的占位符由 Spring AI 的模板引擎替换，
                // 所以提示词里能写"今天是 {now}"这种需要实时信息的话
                .system(s -> s.text(systemTemplate()).params(systemParams(context)))
                .user(context.question());

        Object[] tools = tools(context);
        if (tools.length > 0) {
            spec = spec.tools(tools);
        }
        List<Advisor> advisors = advisors(context);
        if (!advisors.isEmpty()) {
            spec = spec.advisors(advisors);
        }
        return spec;
    }

    /**
     * 本 Agent 的 system 提示词。
     *
     * <p>默认从 {@link PromptManager} 取（Nacos 外置 → 本地兜底 → 代码兜底，
     * 三级保证不会拿到空串 —— 见 {@code PromptManager} 的说明）。</p>
     */
    protected String systemTemplate() {
        return promptManager.get(type());
    }

    /**
     * 提示词模板参数。
     *
     * <p>默认给一个 {@code now}：健身场景里"本周还剩几天能练""今天该练什么"
     * 这类问题很常见，而模型不知道今天几号 —— 不给它这个信息，
     * 它就会凭空编一个日期出来（而且编得很自然，很难发现）。</p>
     */
    protected Map<String, Object> systemParams(AgentContext context) {
        return Map.of("now", DateUtil.now());
    }

    /** 本 Agent 可用的工具（M6-4 接） */
    protected Object[] tools(AgentContext context) {
        return EMPTY_TOOLS;
    }

    /** 本 Agent 的 Advisor（M6-3 给知识问答 Agent 挂 RAG 检索） */
    protected List<Advisor> advisors(AgentContext context) {
        return List.of();
    }
}
