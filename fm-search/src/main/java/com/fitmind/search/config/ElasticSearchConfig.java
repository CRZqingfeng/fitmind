package com.fitmind.search.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置。
 *
 * <p>用 {@link RestHighLevelClient}（ES 7 的经典客户端）而不是 Spring Data ES：
 * 后者把 Spring Data 的版本和 ES 客户端版本绑在一起，容易出现
 * 「服务端 7.17 而客户端被 Boot 的依赖管理升到 8.x」的错配。</p>
 */
@Slf4j
@Configuration
public class ElasticSearchConfig {

    @Value("${elasticsearch.host:127.0.0.1}")
    private String host;

    @Value("${elasticsearch.port:9201}")
    private int port;

    @Bean
    public RestHighLevelClient restHighLevelClient() {
        log.info("初始化 ES 客户端：http://{}:{}", host, port);
        return new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, "http")));
    }

    /**
     * 专门用于 ES 文档读写的 ObjectMapper。
     *
     * <p>必须注册 {@link JavaTimeModule}，否则序列化 {@code LocalDateTime}
     * 会变成数组（如 {@code [2026,9,14,1,0]}），ES 的 date 类型解析不了。</p>
     */
    @Bean
    public ObjectMapper esObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
