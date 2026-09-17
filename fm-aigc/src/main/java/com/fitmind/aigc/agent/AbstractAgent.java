package com.fitmind.aigc.agent;

import cn.hutool.core.date.DateUtil;
import com.fitmind.aigc.constants.AigcConstants;
import com.fitmind.aigc.prompt.PromptManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
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

    /**
     * ★ 工具循环 Advisor 的执行顺序。
     *
     * <p>数字越大越靠内。这里刻意让它<b>比检索 Advisor 更靠内</b>（检索是 0）：
     * 于是「检索 → 工具循环（可能跑好几轮）→ 组装回答」的语义是清晰的 ——
     * 检索只发生一次，而"调工具再问模型"这件事在里层反复发生。
     * 反过来的话，每一轮工具调用都会再触发一次检索，白花 embedding 的钱。</p>
     */
    protected static final int TOOL_ADVISOR_ORDER = 100;

    protected final ChatClient chatClient;
    protected final PromptManager promptManager;

    /**
     * 工具循环 Advisor。
     *
     * <p>★★ <b>为什么必须显式挂它</b>：工具循环（"模型返回 tool_call → 执行工具 →
     * 把结果喂回模型 → 再问一轮"）的默认实现在 {@code ChatModel} 里面
     * （{@code OpenAiChatModel} 有 internalCall/internalStream）。
     * 而我们的 {@code MockChatModel} 是自己实现的 ChatModel —— <b>它不会执行工具</b>，
     * 于是"Mock 通道下的工具调用"会表现成"模型说要调工具，然后就没有然后了"。</p>
     *
     * <p>{@code ToolCallAdvisor} 把这条循环提到了 ChatClient 的 Advisor 层，
     * 对<b>任何</b> ChatModel 都生效 —— 这才让工具链路在 Mock 通道下也能被自动验收。
     * （Spring AI 1.1 提供了它，但不会自动加：实测 {@code DefaultChatClient} 的字节码里
     * 根本没有引用它。）</p>
     *
     * <p>⚠️ 它只在<b>挂了工具的 Agent</b> 上生效，不能做成全局默认 —— 见 {@code AiModelConfig}。</p>
     */
    private final ToolCallAdvisor toolAdvisor;

    protected AbstractAgent(ChatClient chatClient, PromptManager promptManager,
                            ToolCallingManager toolCallingManager) {
        this.chatClient = chatClient;
        this.promptManager = promptManager;
        // 无状态、可复用：一个 Agent 实例建一个就够，不必每轮 new
        this.toolAdvisor = ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .advisorOrder(TOOL_ADVISOR_ORDER)
                .build();
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
                // ★ 通用安全边界拼在每个 Agent 的 system 提示词末尾（只有一处定义，
                //   不会出现"改了 4 个提示词漏了 1 个"——见 AigcConstants.COMMON_GUARD）
                .system(s -> s.text(systemTemplate() + AigcConstants.COMMON_GUARD)
                        .params(systemParams(context)))
                .user(context.question());

        Object[] tools = tools(context);
        List<Advisor> advisors = new ArrayList<>(advisors(context));
        if (tools.length > 0) {
            spec = spec.tools(tools);
            // ★ 「挂了工具才挂工具循环」：没有工具的请求连 ToolCallingChatOptions
            //   都不会构造，硬挂上去会直接抛 IllegalArgumentException（M6-4 实测）
            advisors.add(toolAdvisor);
        }
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
