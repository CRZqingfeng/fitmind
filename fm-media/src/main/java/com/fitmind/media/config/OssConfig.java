package com.fitmind.media.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.fitmind.media.storage.OssProperties;
import com.fitmind.media.storage.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端配置。
 *
 * <p>★ 仅在 {@code fm.storage.type=oss} 时生效，与 {@link MinioConfig} 互斥 ——
 * 两者形状对称，不会同时装配。</p>
 *
 * <p><b>为什么要两个客户端？</b></p>
 * <p>OSS 的 V4 签名把请求的 {@code Host} 算进签名，所以"用来签名的客户端"必须
 * 恰好指向"浏览器实际访问的域名"；而"服务端做 putObject / getObjectMetadata 的客户端"
 * 指向服务端可达的地址（通常相同，将来可能是内网）。
 * 两者职责不同，拆开比事后替换字符串可靠 —— 后者在 OSS 上会直接把签名弄坏。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "fm.storage.type", havingValue = "oss")
@EnableConfigurationProperties(OssProperties.class)
public class OssConfig {

    /**
     * MinIO 的默认桶名。
     *
     * <p>★ 拿它当"OSS 桶名没配"的烟雾报警器：OSS 的桶名**全局唯一**，
     * 所以一个 OSS 桶叫 {@code fitmind} 的概率极低 —— 真出现，八成是
     * {@code fm.oss.bucket} / {@code OSS_BUCKET} 没注入，落到了 MinIO 的默认值上。
     * 2026-10-04 就踩过这条：签出 {@code https://fitmind.oss-cn-beijing.aliyuncs.com/...}，
     * 桶不存在 → 浏览器预检 <b>404 NoSuchBucket</b>。</p>
     */
    private static final String MINIO_DEFAULT_BUCKET = "fitmind";

    private final OssProperties properties;
    private final StorageProperties storage;

    /**
     * ★ 构造器里就把桶名验证掉（fail-fast）。
     *
     * <p>为什么不在某个 {@code @Bean} 方法里验：那样"哪个 Bean 先建"就决定了
     * 报错时机，而配置类本身一定会被实例化 —— 在这里抛出，等于**启动即失败**，
     * 不会出现"服务起来了、上传全都失败"这种最坏状态。</p>
     */
    public OssConfig(OssProperties properties, StorageProperties storage) {
        this.properties = properties;
        this.storage = storage;
        String bucket = resolveBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "OSS 桶名为空：请设置环境变量 OSS_BUCKET 或 FM_STORAGE_BUCKET "
                    + "（对应 docker/.env，见 docs/M9-1-对象存储可插拔开发方案.md）");
        }
        if (MINIO_DEFAULT_BUCKET.equals(bucket)) {
            log.warn("""
                    
                    ⚠️⚠️  OSS 桶名 = `fitmind` —— 这是 **MinIO 的默认桶名**，几乎可以肯定是没配 OSS 的桶。
                          继续跑会签出 https://fitmind.oss-cn-beijing.aliyuncs.com/... 这种**不存在的地址**
                          （表现：浏览器直传的 OPTIONS 预检返回 404 NoSuchBucket，前端只报"网络中断"）。
                          正确做法：OSS_BUCKET=itcrz-9527（或 FM_STORAGE_BUCKET=itcrz-9527）。
                          ★ 若你确实要连一个真叫 fitmind 的 OSS 桶，忽略本条即可。
                    """);
        }
        log.info("""
                
                ==================== 对象存储：阿里云 OSS ====================
                  bucket         : {}
                  publicEndpoint : {}
                  region         : {}
                  → 浏览器直传的地址形如 https://{}.{}/media/2026/10/<uuid>.mp4
                ==============================================================
                """, bucket, publicEndpoint(), properties.getRegion(),
                bucket, hostOf(publicEndpoint()));
    }

    /** 与 AbstractOssStorage#bucket() 同一套取值逻辑（oss.bucket 优先，回落通用桶名） */
    private String resolveBucket() {
        String b = properties.getBucket();
        return (b == null || b.isBlank()) ? storage.getBucket() : b;
    }

    private String publicEndpoint() {
        String ep = properties.getPublicEndpoint();
        return (ep == null || ep.isBlank()) ? properties.getEndpoint() : ep;
    }

    private static String hostOf(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String s = endpoint.trim();
        int i = s.indexOf("://");
        if (i >= 0) {
            s = s.substring(i + 3);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 服务端操作（上传 / 下载 / stat / 删除）用 */
    @Bean(name = "ossClient", destroyMethod = "shutdown")
    public OSS ossClient() {
        return build(properties.getEndpoint());
    }

    /**
     * 生成预签名 URL 用 —— 它的 endpoint 就是**浏览器看到的域名**。
     *
     * <p>★ 不要改成拿 ossClient 去签名：那会用服务端地址签，host 与浏览器请求的不一致，
     * 结果是 403 SignatureDoesNotMatch。</p>
     */
    @Bean(name = "ossPresignClient", destroyMethod = "shutdown")
    public OSS ossPresignClient() {
        return build(publicEndpoint());
    }

    private OSS build(String endpoint) {
        // ★ V4 签名必须显式声明 —— SDK 默认走 V1，而 V1 已不推荐
        com.aliyun.oss.ClientBuilderConfiguration conf = new com.aliyun.oss.ClientBuilderConfiguration();
        conf.setSignatureVersion(SignVersion.V4);
        return OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(new DefaultCredentialProvider(
                        properties.getAccessKeyId(), properties.getAccessKeySecret()))
                .clientConfiguration(conf)
                .region(properties.getRegion())   // ★ V4 必须给 region
                .build();
    }
}
