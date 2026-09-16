package com.fitmind.aigc.agent;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 专职 Agent —— 一类问题的处理者。
 *
 * <p>职责边界（很关键，决定了后面几步在哪里加东西）：
 * <b>Agent 只负责"组装 prompt + 调模型"</b>，
 * 它<b>不做</b>事件封装、不做落库、不发 STOP、不处理降级。</p>
 *
 * <p>★ 这是我们对常见实现骨架的一处调整。常见实现的 {@code Agent.processStream()}
 * 直接把 {@code Flux<ChatEventVO>} 吐出来（含 DATA/PARAM/STOP），
 * 还在里面更新会话时间、往记忆里写东西、维护打断标记 ——
 * 结果是<b>每个 Agent 都重复了一整套收尾逻辑</b>，
 * 而"哪些指标要落库"这件事散在各处（改一处要改五个地方）。</p>
 *
 * <p>所以这里把 {@code Agent} 的返回值定成 Spring AI 原生的
 * {@link Flux}&lt;{@link ChatResponse}&gt;：
 * <ul>
 *   <li><b>usage（token 数）不会丢</b> —— 它本来就在 ChatResponse 的 metadata 里，
 *       如果 Agent 自己封装成自定义事件对象，这个信息很容易被顺手丢掉</li>
 *   <li>事件封装、指标提取、落库、STOP、降级<b>集中在编排层一处</b></li>
 *   <li>Agent 之间不会出现"这个 Agent 落了库、那个忘了落"的不一致</li>
 * </ul>
 * </p>
 */
public interface Agent {

    Object[] EMPTY_TOOLS = new Object[0];

    /** 这个 Agent 处理哪一类问题 */
    AgentType type();

    /**
     * 组装 prompt 并调用模型。
     *
     * @return 模型的原生响应流（上层负责映射成 SSE 事件、提取 usage、落库）
     */
    Flux<ChatResponse> process(AgentContext context);
}
