package com.fitmind.media.config;

import com.fitmind.media.storage.MinioProperties;
import com.fitmind.media.storage.StorageProperties;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置。
 *
 * <p>★ 仅在 {@code fm.storage.type=minio}（或不配）时生效。切到 OSS 后本类不装配，
 * 由 {@code OssConfig} 接管 —— 两个配置类形状对称，互斥且不重叠。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fm.storage.type", havingValue = "minio", matchIfMissing = true)
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    private final MinioProperties properties;
    private final StorageProperties storage;

    @Bean
    public MinioClient minioClient() {
        log.info("初始化 MinIO 客户端：endpoint={}, publicEndpoint={}, bucket={}",
                properties.getEndpoint(), properties.getPublicEndpoint(), storage.getBucket());
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
