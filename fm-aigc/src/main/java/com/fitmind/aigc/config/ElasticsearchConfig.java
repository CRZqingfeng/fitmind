package com.fitmind.aigc.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置（M6-3 知识库向量索引用）。
 *
 * <p>⭐ 和 fm-search 走同一套客户端（{@link RestHighLevelClient}，7.17 线），
 * 而不是 {@code spring-boot-starter-data-elasticsearch}。原因在
 * {@code fm-aigc/pom.xml} 里写得更细，一句话总结：
 * <b>服务端是 7.17.22，客户端必须是 7.17 线</b>。
 * Spring AI 官方的 ES 向量库实现需要 8.x 的 knn 检索，在这个服务端上跑不了。</p>
 *
 * <p>⚠️ 这里没有复用 fm-search 的 Bean —— 微服务各自的进程独立，
 * 复用只能通过"公共模块"或"Feign 调用"，而一个数据库客户端 Bean
 * 放进公共模块会把 ES 依赖传染给 14 个服务。重复这十几行是更划算的取舍。</p>
 */
@Slf4j
@Configuration
public class ElasticsearchConfig {

    @Value("${fm.ai.rag.es-host:127.0.0.1}")
    private String host;

    @Value("${fm.ai.rag.es-port:9201}")
    private int port;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient() {
        log.info("初始化 ES 客户端（知识库向量索引）：http://{}:{}", host, port);
        return new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, "http")));
    }

    /**
     * ⚠️ <b>坑位提醒：这里刻意"没有"注册第二个 ObjectMapper Bean。</b>
     *
     * <p>最初写了一个 {@code @Bean("esObjectMapper")}，结果它把
     * Spring Boot 自动配置的那个给顶掉了 —— Boot 的
     * {@code JacksonAutoConfiguration.jacksonObjectMapper()} 标的是
     * {@code @ConditionalOnMissingBean}（<b>按类型判断</b>，不看 Bean 名），
     * 于是容器里只剩我们这个"裸"的 ObjectMapper。</p>
     *
     * <p>后果非常隐蔽：MVC 的消息转换器换用了它，
     * 而它没注册 {@code JavaTimeModule} —— 凡是返回 {@code LocalDateTime} 的接口
     * 全部报 {@code InvalidDefinitionException: Java 8 date/time type not supported}。
     * <b>表现为"会话列表和消息历史接口挂了"，而根因是"加了一个 ES 用的 Bean"</b>，
     * 两者看起来毫无关系。</p>
     *
     * <p>所以 ES 需要的那个 ObjectMapper 在 {@code RagConfig} 里就地 new 一个
     * 传进去即可 —— <b>只被一个组件使用的对象，不该注册成全局 Bean。</b></p>
     */
}
