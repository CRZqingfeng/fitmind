package com.fitmind.aigc.orchestration;

import cn.hutool.core.date.DateUtil;
import com.fitmind.aigc.agent.Agent;
import com.fitmind.aigc.agent.AgentContext;
import com.fitmind.aigc.agent.AgentType;
import com.fitmind.aigc.agent.RouteAgent;
import com.fitmind.aigc.agent.RouteResult;
import com.fitmind.aigc.constants.AigcConstants;
import com.fitmind.aigc.prompt.PromptManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 编排器 —— 决定"这次提问交给谁"。
 *
 * <p>它是三种编排策略的唯一落点：上层（{@code ChatServiceImpl}）
 * 只管记录与收尾，不关心这次是谁回答的。</p>
 *
 * <p>★ <b>与常见实现的一处结构差异：用"运行时分派"而不是"启动时条件装配"。</b>
 * 常见实现用 {@code @ConditionalOnProperty(chat-type=...)} 给三种策略各写一个
 * {@code ChatService} 实现，同一时刻只有一个生效 —— 这个做法很 Spring，
 * 但意味着<b>换策略要重启服务</b>，而且三份实现之间会慢慢发生实现漂移
 * （改了一处忘了另一处，正是 M4-3 那个"两个入口不同实现"的翻版）。</p>
 *
 * <p>这里改成：一个编排器 + 配置决定默认策略 + <b>请求可以临时覆盖</b>。
 * 好处有三个：
 * ① 换策略不用重启（改配置即生效）；
 * ② 能<b>在同一个进程里横向对比三种策略</b> —— 这是方案里说的"可以直接做成一个演示"，
 * 也是验收脚本能覆盖三条路径的前提；
 * ③ 只有一条主链路，不会出现"实现漂移"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatOrchestrator {

    private final ChatClient chatClient;
    private final RouteAgent routeAgent;
    private final PromptManager promptManager;
    /** Spring 会把所有 {@link Agent} 实现都注入进来 —— 新增 Agent 不用改这里的注册代码 */
    private final List<Agent> agents;

    private final Map<AgentType, Agent> registry = new EnumMap<>(AgentType.class);

    @PostConstruct
    void buildRegistry() {
        for (Agent agent : agents) {
            Agent previous = registry.put(agent.type(), agent);
            if (previous != null) {
                // 两个 Agent 报同一个类型 = 其中一个永远不会被调用，
                // 而这种问题不会有任何别的症状（不报错、不影响启动），
                // 只能靠这里主动喊出来
                log.error("★ 发现重复的 Agent 类型 {}：{} 与 {} 冲突，后者被忽略",
                        agent.type().key(), previous.getClass().getSimpleName(),
                        agent.getClass().getSimpleName());
            }
        }
        log.info("Agent 注册完成：{}", registry.keySet().stream().map(AgentType::key).toList());
    }

    /**
     * 按策略分派。
     *
     * @param chatType 本次使用的策略（配置默认值 + 请求级覆盖，由上层解析）
     */
    public OrchestrationResult dispatch(ChatType chatType, AgentContext context) {
        return switch (chatType) {
            case ROUTE -> route(context);
            case ENHANCE -> enhance(context);
            case SIMPLE -> simple(context);
        };
    }

    /**
     * ROUTE：先路由，再分派给专职 Agent。
     *
     * <p>⚠️ 路由这一步是<b>同步阻塞</b>的（编排层需要先知道类型才能决定交给谁），
     * 所以它会直接体现在首字延迟上 —— 这是 ROUTE 相比 ENHANCE 的主要成本，
     * 验收里可以量化对比（同一问题，两种策略的首字延迟差多少）。</p>
     */
    private OrchestrationResult route(AgentContext context) {
        RouteResult routed = routeAgent.route(context);
        AgentType target = routed.type();
        if (target == AgentType.ROUTE) {
            // parse() 已经排除了 ROUTE，这里再挡一次：
            // 路由到路由会造成自我循环，而"多一层防御"的成本是零
            target = AgentType.KNOWLEDGE;
        }
        Agent agent = registry.get(target);
        if (agent == null) {
            log.warn("没有找到类型 {} 对应的 Agent，退回知识问答", target.key());
            agent = registry.get(AgentType.KNOWLEDGE);
        }
        log.info("编排[ROUTE]：sessionId={} → Agent[{}]（路由开销 {}/{} tokens）",
                context.sessionId(), agent.type().key(),
                routed.promptTokens(), routed.completionTokens());
        // ★ 把路由那次调用的 token 一起带出去：ROUTE 策略的成本 = 路由 + 回答，
        //   只记回答会让这个策略的成本被系统性低估
        return new OrchestrationResult(agent.type(), agent.process(context),
                routed.promptTokens(), routed.completionTokens());
    }

    /**
     * ENHANCE：不路由，直接交给带增强（RAG + 工具）的知识问答 Agent。
     *
     * <p>链路最短 —— 只有一次模型调用。代价是这一次调用要同时承担
     * "理解问题 + 检索知识 + 组织答案"，提示词更长、准确率略低于分派模式。</p>
     */
    private OrchestrationResult enhance(AgentContext context) {
        Agent agent = registry.get(AgentType.KNOWLEDGE);
        log.info("编排[ENHANCE]：sessionId={} → Agent[{}]", context.sessionId(), agent.type().key());
        return OrchestrationResult.of(agent.type(), agent.process(context));
    }

    /**
     * SIMPLE：纯模型对话，作为对比实验的基线。
     *
     * <p>不做 Agent 分派、不检索、不调工具，只有一段人设提示词。
     * 它的价值在于<b>提供一个"什么都不加"的对照组</b>：
     * 有了它，"多花的那些模型调用与检索到底带来了多少提升"才是可测量的；
     * 它也是排查问题的第一站（SIMPLE 也不对 → 问题在模型或提示词，
     * 不用去怀疑检索与工具）。</p>
     */
    private OrchestrationResult simple(AgentContext context) {
        String system = promptManager.get(PromptManager.SIMPLE_KEY);
        Flux<ChatResponse> responses = chatClient.prompt()
                .system(s -> s.text(system + AigcConstants.COMMON_GUARD)
                        .params(Map.of("now", DateUtil.now())))
                .user(context.question())
                .stream()
                .chatResponse();
        log.info("编排[SIMPLE]：sessionId={} → 纯模型对话（基线）", context.sessionId());
        return OrchestrationResult.of(null, responses);
    }
}
