package com.fitmind.aigc.rag;

import com.fitmind.aigc.config.AigcProperties;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 通道装配。
 *
 * <p>★ <b>为什么要单独建一套，而不是复用 chat 的 {@code spring.ai.openai} 配置</b>：
 * 那边的 base-url/api-key 是给 DeepSeek 的，而 <b>DeepSeek 官方没有 embeddings 接口</b>
 * （只有 chat completions）—— 拿它去调 embedding 必然 404。
 * 所以这里是第二个 {@code OpenAiApi}：<b>同一套代码、换一个地址和 key</b>。
 * 这件事本身就是"两家都走 OpenAI 兼容协议"这个决策的价值 ——
 * 不需要引阿里的专用 starter，也不需要写第二套调用代码。</p>
 *
 * <p>⚠️ {@code spring.ai.openai.embedding.enabled} 必须保持 false，
 * 否则自动装配会再建一个指向 DeepSeek 的 EmbeddingModel，
 * 于是"到底用的哪个"变成靠运气（Bean 冲突时甚至可能静默选错）。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AigcProperties.class)
public class EmbeddingConfig {

    private static final String NOT_CONFIGURED = "not-configured";

    /**
     * Embedding 模型。
     *
     * <p>★ <b>选通道的逻辑刻意做成"能降级，不能崩"</b>：
     * provider 配了 bailian 但 key 没配（或还是占位符）时，
     * <b>不抛异常、不让服务起不来</b>，而是带着一条很响的 WARN 退到确定性向量。
     * 理由和 {@code MockChatModel} 的 api-key 兜底完全一致：
     * 一个配置问题不该升级成"整个服务挂了"，它的正确表现是"功能降级 + 日志说清楚"。
     * （如果没有这条兜底，演示时最尴尬的场景就是：key 忘了填，服务直接起不来。）</p>
     */
    @Bean
    public EmbeddingModel embeddingModel(AigcProperties props) {
        AigcProperties.Rag rag = props.getRag();
        boolean wantBailian = "bailian".equalsIgnoreCase(rag.getProvider());
        boolean keyUsable = StrUtil.isNotBlank(rag.getApiKey())
                && !NOT_CONFIGURED.equals(rag.getApiKey());

        if (wantBailian && !keyUsable) {
            log.warn("★★ [Embedding 通道] provider=bailian 但没有可用的 DASHSCOPE_API_KEY "
                    + "→ 自动退到内置确定性向量。\\n"
                    + "    此时 RAG 链路**可以跑通**（分块/入索引/检索/进 prompt 都真实执行），"
                    + "但向量**没有语义能力**，检索质量不可用于评估。\\n"
                    + "    修法：在 docker/.env 里填 DASHSCOPE_API_KEY，然后重启 fm-aigc。");
            return new DeterministicEmbeddingModel(rag.getDimensions());
        }

        if (!wantBailian) {
            log.warn("★ [Embedding 通道] provider={} —— 使用内置确定性向量。"
                    + "链路可跑通、可离线验收，但不具备语义检索能力，不要用于质量评估。", rag.getProvider());
            return new DeterministicEmbeddingModel(rag.getDimensions());
        }

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(rag.getBaseUrl())
                .apiKey(rag.getApiKey())
                .embeddingsPath("/embeddings")
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(rag.getModel())
                // ★ 显式指定维度：text-embedding-v4 支持多档维度，
                //   不指定就用默认值 —— 而"默认值是多少"是供应商说了算的，
                //   一旦它变了，我们建好的索引就写不进去了（dense_vector 的 dims 不可变）
                .dimensions(rag.getDimensions())
                .build();

        EmbeddingModel model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
        log.info("★ [Embedding 通道] 已启用真实 embedding：{} / {} / {} 维"
                        + "（索引 {}）",
                rag.getBaseUrl(), rag.getModel(), rag.getDimensions(), rag.indexName());
        return model;
    }
}
