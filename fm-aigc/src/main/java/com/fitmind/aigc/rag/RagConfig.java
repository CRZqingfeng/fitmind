package com.fitmind.aigc.rag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmind.aigc.config.AigcProperties;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 装配。
 *
 * <p>★ 这个类只有十几行，但它是整个 RAG 部分<b>最值得讲</b>的地方：
 * 一个 Bean 声明，决定了"底下用哪个向量库"。换成 Milvus 或 pgvector，
 * 只需要在这里换一个实现类 —— 检索器、Advisor、Agent、编排器、Service
 * <b>一行都不用改</b>，因为它们依赖的是 Spring AI 的 {@code VectorStore} 抽象。</p>
 */
@Configuration
@RequiredArgsConstructor
public class RagConfig {

    @Bean
    public ElasticsearchVectorStore knowledgeVectorStore(RestHighLevelClient restHighLevelClient,
                                                         EmbeddingModel embeddingModel,
                                                         AigcProperties props) {
        // ⚠️ 这里的 ObjectMapper 是**就地 new 的，不注册成 Bean**。
        //    注册第二个 ObjectMapper 会顶掉 Spring Boot 自动配置的那个
        //    （它的条件是 @ConditionalOnMissingBean，按类型判断），
        //    导致全项目的接口都失去 JavaTimeModule、LocalDateTime 序列化全挂。
        //    详见 ElasticsearchConfig 里的说明。
        ObjectMapper esObjectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return new ElasticsearchVectorStore(restHighLevelClient, embeddingModel,
                props.getRag(), esObjectMapper);
    }
}
