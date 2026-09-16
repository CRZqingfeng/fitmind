package com.fitmind.media.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 对象存储配置。
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

    /** 桶名称 */
    private String bucket = "fitmind";

    /** 上传签名有效期，单位秒 */
    private int uploadExpire = 3600;

    /** 播放签名有效期，单位秒 */
    private int playExpire = 7200;

    /** 管理端预览签名有效期，单位秒（比播放更短） */
    private int previewExpire = 1800;
}
