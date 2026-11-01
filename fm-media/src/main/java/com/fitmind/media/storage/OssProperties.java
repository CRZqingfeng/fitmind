package com.fitmind.media.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 配置（**只放 OSS 独有的部分**）。
 *
 * <p>与 {@link MinioProperties} 形状对称：桶名与三个签名有效期都在
 * {@link StorageProperties} 里，这里只有"怎么连"。</p>
 *
 * <p><b>为什么 OSS 也要 endpoint / public-endpoint 两个？</b>
 * 和 MinIO 的原因**不同**，别混淆：</p>
 * <ul>
 *   <li>MinIO：签名把 host 算进去，而它只能按"一个客户端一个 endpoint"来签，
 *       所以先用内网地址签、再把 host **字符串替换**成对外地址（见
 *       {@link AbstractMinioStorage#toPublicUrl}）。</li>
 *   <li>OSS：V4 签名同样把 host 算进去，但**不能靠替换**（换掉 host 签名就不对了）。
 *       正确做法是<b>用"浏览器将访问的那个域名"去建一个客户端专门签名</b> ——
 *       所以这里两个 endpoint 对应的是<b>两个 OSS 客户端</b>，不是一个客户端加替换。</li>
 * </ul>
 *
 * <p>本项目服务端也在公网（本机 / 内网穿透），所以两个值通常相同；
 * 将来若服务端迁进阿里云 VPC，把 endpoint 换成
 * {@code https://oss-cn-beijing-internal.aliyuncs.com} 走内网，public-endpoint 保持公网域名即可。</p>
 */
@Data
@ConfigurationProperties(prefix = "fm.oss")
public class OssProperties {

    /** 服务端调用地址，如 https://oss-cn-beijing.aliyuncs.com */
    private String endpoint;

    /**
     * 生成给浏览器的预签名 URL 使用的地址。
     *
     * <p>★ 它同时是**签名用的域名** —— OSS V4 把 host 算进签名，
     * 所以这个值必须与"浏览器实际请求的域名"完全一致，否则 403 SignatureDoesNotMatch。</p>
     */
    private String publicEndpoint;

    /** 地域，如 cn-beijing。★ V4 签名必须显式提供，不是冗余参数 */
    private String region;

    /**
     * OSS 的桶名（{@code fm.oss.bucket}）。
     *
     * <p>★★ <b>为什么 OSS 要有自己的桶名，而不是共用 {@code fm.storage.bucket}？</b>
     * 因为两个后端的桶名<b>天然不同</b>（MinIO 是 {@code fitmind}，OSS 是 {@code itcrz-9527}），
     * 而共用一个键就必然要共用一个<b>兜底默认值</b> —— 那个默认值只可能对一边正确。
     * 曾经的坑（2026-10-04 实测）：用 IDEA 启动时没人注入桶名，兜底成 {@code fitmind}，
     * 于是后端签出 {@code https://fitmind.oss-cn-beijing.aliyuncs.com/...}，
     * <b>桶不存在</b> —— 浏览器预检直接 404 NoSuchBucket，前端只报一句
     * "直传失败（网络中断或 MinIO 不可达）"，极难往"桶名"上想。</p>
     *
     * <p>留空则回落到 {@link StorageProperties#getBucket()}（兼容只配了一个键的环境）。</p>
     */
    private String bucket;

    /** AccessKeyId（从环境变量注入，不写死在 yml） */
    private String accessKeyId;

    /** AccessKeySecret（从环境变量注入，不写死在 yml） */
    private String accessKeySecret;
}
