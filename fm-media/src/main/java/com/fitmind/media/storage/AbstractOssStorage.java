package com.fitmind.media.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URL;
import java.util.Date;

/**
 * OSS 存储实现基类：持有两个客户端，并封装 OSS 特有的「地址拼装 / 反解 / 预签名」。
 *
 * <p><b>★ 与 {@link AbstractMinioStorage} 的两个关键差异（不要互相套用）</b></p>
 *
 * <ol>
 *   <li><b>URL 形态不同</b>：MinIO 是 <b>path-style</b>
 *       （{@code http://host:9000/fitmind/{key}}），
 *       OSS 是 <b>virtual-hosted</b>（{@code https://{bucket}.{host}/{key}}）。
 *       → 拼法与解法各写一套，且**必须成对**，否则会"能上传、显示不出来"。</li>
 *   <li><b>预签名的做法不同</b>：MinIO 用内网地址签完再替换 host；
 *       OSS <b>不能替换</b>（V4 把 host 算进签名），必须用
 *       {@link #presignClient}（它的 endpoint 就是浏览器看的域名）来签。</li>
 * </ol>
 *
 * <p><b>关于 Content-Type</b>：OSS 的 V4 签名把 {@code Content-Type} 纳入签名，
 * 而浏览器用 XHR 直传 {@code File} 时**一定会带** Content-Type（且会自动补 MIME），
 * 所以服务端必须按同一个值来签，并把该值回传给前端让它原样带上 ——
 * 这就是 {@code UploadSignatureDTO.contentType} 存在的原因。</p>
 */
@Slf4j
public abstract class AbstractOssStorage {

    /** 服务端操作用（putObject / getObjectMetadata / deleteObject） */
    protected final OSS ossClient;

    /** 生成预签名 URL 用 —— endpoint 必须与浏览器访问的域名一致 */
    protected final OSS presignClient;

    /** OSS 专属配置（endpoint / public-endpoint / region / 凭据） */
    protected final OssProperties properties;

    /** 通用配置（bucket / 三个有效期） */
    protected final StorageProperties storage;

    protected AbstractOssStorage(OSS ossClient, OSS presignClient,
                                OssProperties properties, StorageProperties storage) {
        this.ossClient = ossClient;
        this.presignClient = presignClient;
        this.properties = properties;
        this.storage = storage;
    }

    /**
     * 拼出不带签名的展示地址。
     *
     * <p>★ <b>virtual-hosted</b>：{@code https://{bucket}.{host}/{key}} —— 与 MinIO 的
     * path-style 正好相反。写成 {@code {endpoint}/{bucket}/{key}} 会得到一个**不存在的地址**
     * （OSS 默认不认 path-style）。</p>
     */
    protected String buildRawUrl(String key) {
        return String.format("https://%s.%s/%s", bucket(), hostOf(publicEndpoint()), key);
    }

    /**
     * 从原始地址里反解出对象 key（**容错**）。
     *
     * <p>为什么要容错：库里存了一批**历史 rawUrl**（迁到 OSS 之前写的
     * {@code http://localhost:9000/fitmind/...}，path-style）。这些行不会自动改写，
     * 而 {@code getAccessUrlByRawUrl()} 要拿它们去签名 —— 解析必须认得出老格式，
     * 否则老封面会"签名不出来"。</p>
     */
    protected String extractKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        // 去掉 query（签名参数），只留路径
        String noQuery = rawUrl.split("\\?", 2)[0];
        String bucketPrefix = "/" + bucket() + "/";

        // ① virtual-hosted：https://{bucket}.{host}/{key} —— 取 path 去掉前导斜杠
        try {
            URI uri = URI.create(noQuery);
            String path = uri.getPath();
            if (path != null && path.length() > 1 && path.startsWith("/")) {
                return path.substring(1);
            }
        } catch (IllegalArgumentException ignore) {
            // 不是合法 URI，走下面的字符串兜底
        }

        // ② path-style 兜底：找到 "/{bucket}/" 之后的部分
        int idx = noQuery.indexOf(bucketPrefix);
        if (idx >= 0) {
            return noQuery.substring(idx + bucketPrefix.length());
        }

        // ③ 认不出来（外部链接等），原样返回，上层再做判断
        return rawUrl;
    }

    /**
     * 生成预签名 URL。
     *
     * @param contentType 需要参与签名的 Content-Type；为 null/空则不纳入签名。
     *                    ⚠️ 传了就要求调用方**原样**带上，否则 SignatureDoesNotMatch。
     */
    protected String presign(String key, HttpMethod method, int expireSeconds, String contentType) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(bucket(), key, method);
        request.setExpiration(new Date(System.currentTimeMillis() + expireSeconds * 1000L));
        if (contentType != null && !contentType.isBlank()) {
            request.setContentType(contentType);
        }
        URL url = presignClient.generatePresignedUrl(request);
        return url.toString();
    }

    /**
     * 当前生效的桶名。
     *
     * <p>★ 优先取 {@code fm.oss.bucket}（OSS 自己的桶），为空才回落到通用的
     * {@code fm.storage.bucket} —— 原因见 {@link OssProperties#getBucket()}。</p>
     *
     * <p>⚠️ 全类都用这个方法取桶名，**不要再直接写 {@code storage.getBucket()}**：
     * 那样会把 MinIO 的桶名带到 OSS 上（正是 2026-10-04 那次预检 404 的成因）。</p>
     */
    protected String bucket() {
        String b = properties.getBucket();
        return (b == null || b.isBlank()) ? storage.getBucket() : b;
    }

    /** 对外域名（public-endpoint 为空时回退到 endpoint） */
    protected String publicEndpoint() {
        String ep = properties.getPublicEndpoint();
        return (ep == null || ep.isBlank()) ? properties.getEndpoint() : ep;
    }

    /** 从 endpoint 里取出 host（去掉协议与结尾斜杠） */
    protected static String hostOf(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String s = endpoint.trim();
        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 由对象 key / 文件名猜 Content-Type。
     *
     * <p>★ 统一走 {@link com.fitmind.media.utils.ContentTypeUtil} —— 签名用的值
     * 与 {@code applyUploadSignature} 回传给前端的值必须是同一个来源。</p>
     */
    protected static String guessContentType(String name) {
        return com.fitmind.media.utils.ContentTypeUtil.ofName(name);
    }
}
