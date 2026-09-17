package com.fitmind.aigc.agent;

import cn.hutool.core.util.StrUtil;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.prompt.PromptManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 意图路由 Agent —— 判断用户问的是哪一类，本身不回答业务问题。
 *
 * <p>它的输出只有一个词（{@code CONSULT} / {@code RECOMMEND} / {@code BUY} / {@code KNOWLEDGE}），
 * 由 {@link AgentType#parse} 解析成枚举。</p>
 *
 * <p>★ <b>两处与常见实现不同的处理，都是围绕"路由是链路的第一步，它出问题会毁掉整轮对话"：</b></p>
 *
 * <p><b>① 路由用独立的、更短的超时</b>。路由的任务很小（输出一个词），
 * 但它处在链路最前面 —— 如果沿用主回答的超时（60 秒），
 * 一旦模型侧抖动，用户要<b>干等 60 秒才看到第一个字</b>，
 * 而且这 60 秒里我们还什么都没做。给它 10 秒足够了：超了就兜底，
 * 用户几乎无感地继续拿到回答。</p>
 *
 * <p><b>② 路由失败必须兜底，不能把错误抛给用户</b>。兜底目标是知识问答 ——
 * 它是最"泛用"的一个（答不了具体课程，但至少能给健身建议），
 * 而且<b>不会替用户执行任何业务动作</b>（不像兜底到 BUY 那样可能引导出一个错误的下单请求）。
 * 注意 ROUTE 自己不在候选项里：模型输出 ROUTE 说明它没理解任务，兜底比照做安全。</p>
 */
@Slf4j
@Component
public class RouteAgent extends AbstractAgent {

    private final AigcProperties props;

    public RouteAgent(ChatClient chatClient, PromptManager promptManager, AigcProperties props,
                          ToolCallingManager toolCallingManager) {
        super(chatClient, promptManager, toolCallingManager);
        this.props = props;
    }

    @Override
    public AgentType type() {
        return AgentType.ROUTE;
    }

    /**
     * 同步路由：给一个问题，返回该由哪个 Agent 处理。
     *
     * <p>★ 为什么是同步的（而其他 Agent 都返回流）：编排层需要<b>先知道类型</b>
     * 才能决定把这次提问交给谁。这是"路由 → 分派"这个结构的必然结果。
     * 代价是用户要多等一次模型调用的时间（首字延迟变大），
     * 这也是 ROUTE 策略与 SIMPLE/ENHANCE 相比的主要成本，
     * 验收里可以量化对比。</p>
     *
     * <p>⚠️ 这个方法<b>在任何情况下都不抛异常</b>：调用方（编排层）在流式请求的
     * 前段同步调用它，一旦抛出就会让整个请求失败 —— 而路由只是"第一步的加速器"，
     * 它不该有让请求失败的能力。</p>
     */
    public RouteResult route(AgentContext context) {
        long start = System.currentTimeMillis();
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        String output = null;
        try {
            // 用 stream() 收集而不是 call()：这样能挂上 Reactor 的超时。
            // call() 是阻塞的，用 Mono.fromCallable 包它也中断不了底层请求，
            // 那个线程会一直占着直到模型自己返回。
            output = request(context)
                    .stream()
                    .chatResponse()
                    .map(resp -> {
                        // 边收边记 usage：路由这次调用花的 token 也要算进本轮成本
                        Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
                        if (usage != null) {
                            usageRef.set(usage);
                        }
                        return resp.getResult() == null || resp.getResult().getOutput() == null
                                ? "" : StrUtil.nullToEmpty(resp.getResult().getOutput().getText());
                    })
                    .reduce("", String::concat)
                    .timeout(Duration.ofMillis(props.getChat().getRouteTimeoutMs()))
                    .block();
        } catch (Exception e) {
            // 超时 / 限流 / 网络问题都会走到这里。用 warn 而不是 error：
            // 这是一条**有明确兜底路径**的分支，不是故障
            log.warn("意图路由失败（{}ms），兜底到知识问答：{}",
                    System.currentTimeMillis() - start, e.getMessage());
            return RouteResult.fallback();
        }

        AgentType type = AgentType.parse(output);
        Usage usage = usageRef.get();
        int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null
                ? 0 : usage.getCompletionTokens();
        log.info("意图路由完成：判定={}，耗时={}ms，路由消耗 tokens={}/{}，模型输出=[{}]",
                type.key(), System.currentTimeMillis() - start, promptTokens, completionTokens,
                StrUtil.maxLength(output, 40));
        return new RouteResult(type, promptTokens, completionTokens);
    }
}
