package com.fitmind.media.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 对象存储配置（**只保留 MinIO 独有的部分**）。
 *
 * <p>桶名与三个签名有效期已挪到 {@link StorageProperties} —— 它们与厂商无关。
 * 将来加 OSS 时，这里对应的就是 {@code OssProperties}，两者形状对称。</p>
 *
 * <p><b>为什么 endpoint 要配两个？</b></p>
 *
 * <p>MinIO 的 SigV4 签名会把请求的 {@code Host} 头一起算进签名里。
 * 拿 A 地址签出来的预签名 URL，去 B 地址访问，会得到
 * {@code 403 SignatureDoesNotMatch}。已实测：用 {@code localhost:9000} 签的 URL
 * 换成 {@code 127.0.0.1:9000} 访问即失败。</p>
 *
 * <p>而"服务端调用 SDK 的地址"和"浏览器访问的地址"在真实部署里几乎必然不同
 * （容器网络里是 {@code fm-minio:9000}，浏览器看到的是 {@code localhost:9000} 或正式域名），
 * 所以必须拆成两个配置项：用 endpoint 签名，再把 host 替换成 publicEndpoint 返回给前端。</p>
 *
 * <p>⚠️ <b>这层「换 host」是 MinIO 专属，OSS 侧不能照抄</b> ——
 * OSS 的 V4 签名同样把 host 算进签名，但 OSS 不存在"内网/外网地址不一致"的问题
 * （它本身就是公网服务），所以 OSS 实现是**直接用 publicEndpoint 建客户端签名**，
 * 不做字符串替换。见 {@link AbstractMinioStorage#toPublicUrl} 的说明。</p>
 */
@Data
@ConfigurationProperties(prefix = "fm.minio")
public class MinioProperties {

    /** SDK 内部调用地址，如 http://localhost:9000 */
    private String endpoint;

    /** 生成给浏览器的预签名 URL 使用的地址 */
    private String publicEndpoint;

    /** Access Key（从环境变量注入，不写死在 yml） */
    private String accessKey;

    /** Secret Key（从环境变量注入，不写死在 yml） */
    private String secretKey;
}
