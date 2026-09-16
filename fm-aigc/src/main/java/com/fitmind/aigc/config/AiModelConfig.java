package com.fitmind.aigc.config;

import com.fitmind.aigc.constants.AigcConstants;
import com.fitmind.aigc.model.MockChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 模型通道装配 —— 决定这一轮跑真实模型还是 Mock。
 *
 * <p>三件事在这里定：
 * ① <b>通道选择</b>：{@code fm.ai.chat.provider=deepseek|mock}。
 *    deepseek 时用的是 Spring AI 自动装配出来的 {@code OpenAiChatModel}（走 OpenAI 兼容协议，
 *    所以换供应商只是改 base-url，见方案 §十五）；mock 时用本模块的 {@link MockChatModel}。
 * ② 给 Mock 加 {@code @Primary}，让"配置不一致"也不会启动失败（见下）。
 * ③ 暴露唯一的 {@link ChatClient} —— 上层（ChatService）只依赖它，
 *    <b>永远不知道自己面对的是真模型还是 Mock</b>。这是 Mock 通道能验证真实链路的前提。</p>
 *
 * <p>★ <b>关于 {@code @Primary} 与 api-key 兜底这两个防御</b>：
 * 配置项有两个（provider 与 spring.ai.openai.chat.enabled），它们必须一致
 * （mock 时得把 OpenAI 自动配置关掉，否则它的 apiKey 校验过不了）。
 * 而"两个开关必须一致"是个很容易出错的约定 —— 所以这里做了两层兜底：
 * · api-key 的默认值是 {@code not-configured}（<b>非空</b>）→ 即使开关不一致，
 *   OpenAI 的 Bean 也能创建成功，最坏是调用时 401 并走降级，<b>而不是启动就崩</b>；
 * · Mock 标 {@code @Primary} → 万一两个 Bean 同时都在，也确定用 Mock。
 * </p>
 *
 * <p>一句话：<b>配置错误的表现应该是"功能降级"，不该是"服务起不来"。</b>
 * 启动失败会把一个可排查的配置问题，变成一个影响所有人的故障。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AigcProperties.class)
public class AiModelConfig {

    /**
     * Mock 模型通道。
     *
     * <p>★ 它实现的是 Spring AI 的 {@link ChatModel} 接口本身（不是"绕过模型"），
     * 所以 Advisor 链、prompt 组装、SSE 拼接、工具调用这些真正容易出错的地方，
     * 在 Mock 通道下**全部会被真实执行到**。</p>
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "fm.ai.chat.provider", havingValue = AigcConstants.PROVIDER_MOCK)
    public ChatModel mockChatModel(AigcProperties props, StringRedisTemplate redis) {
        log.warn("★ [AI 模型通道] provider=mock —— 使用内置确定性模型。"
                + "全链路（Prompt 组装 / Advisor / SSE / 工具调用）真实执行，只有「生成」这一步是确定的。"
                + "这个通道供自动化验收与离线演示使用，不要用于生产。");
        return new MockChatModel(props, redis);
    }

    /**
     * 上层的唯一入口。
     *
     * <p>为什么不直接注入 {@code ChatModel} 而要多一层 {@link ChatClient}：
     * ChatClient 是 Spring AI 的<b>链式调用门面</b>，Advisor（RAG 检索、
     * 会话记忆、上下文压缩）都是通过它挂上去的。把这一层固定下来，
     * 后面 M6-3/M6-5 加能力时是"往链条上插一个 Advisor"，
     * 而不是在每个 Service 里手写检索代码。</p>
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        // 记下真实用的是哪个实现 —— 排查"为什么回答怪怪的"时，
        // 第一件要确认的事就是"到底走的哪个通道"，日志里直接能给答案
        log.info("[AI 模型通道] ChatClient 就绪，底层 ChatModel = {}", chatModel.getClass().getSimpleName());
        return ChatClient.builder(chatModel).build();
    }
}
