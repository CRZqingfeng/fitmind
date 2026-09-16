package com.fitmind.media.config;

import com.fitmind.media.storage.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 bucket 存在。
 *
 * <p>放在代码里而不是"人肉去控制台点一下"，是为了让环境可重建：
 * 换台机器 {@code docker-compose up} 之后直接启动服务就自带有 bucket 了。</p>
 *
 * <p><b>关于 CORS</b>：方案里原本计划在这里一并设置 CORS 规则，实测后取消——
 * 两点原因：</p>
 * <ol>
 *   <li>MinIO 服务端默认已开启全局 CORS（{@code MINIO_API_CORS_ALLOW_ORIGIN} 默认为 {@code *}），
 *       实测 OPTIONS 预检会返回 {@code 204 + Access-Control-Allow-Origin}，前端直传不受阻</li>
 *   <li>minio-java 8.5.12 的 {@code MinioClient} 根本没有暴露设置 CORS 的 API（javap 已确认）</li>
 * </ol>
 * <p>将来部署到生产要收紧来源时，用 {@code mc cors set} 配置即可，不用改代码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioInitializer {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @PostConstruct
    public void init() {
        String bucket = properties.getBucket();
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (exists) {
                log.info("MinIO bucket 已存在：{}", bucket);
                return;
            }
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("MinIO bucket 创建成功：{}", bucket);
        } catch (Exception e) {
            // 不阻断启动：bucket 可以事后补建，但要在日志里明确告警
            log.error("MinIO bucket 初始化失败：{}，媒资功能将不可用，请检查 MinIO 是否启动、凭据是否正确", bucket, e);
        }
    }
}
