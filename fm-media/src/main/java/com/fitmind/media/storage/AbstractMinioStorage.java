package com.fitmind.media.storage;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MinIO 存储实现基类：持有客户端，并封装「签名地址 → 对外地址」的替换逻辑。
 *
 * <p><b>核心方法 {@link #toPublicUrl}</b>：SDK 用内部 endpoint 签名，
 * 而签名绑定了 Host，所以返回给浏览器之前必须把 host 段换成 publicEndpoint。
 * 少了这一步，前端拿到的 URL 一定会 403（已实测验证）。</p>
 *
 * <p>★★ <b>这个基类是 MinIO 专属，不要给别的厂商复用。</b>
 * 下面三个方法都建立在一个前提上：<b>MinIO 用 path-style URL</b>
 * （{@code {endpoint}/{bucket}/{key}}）。而阿里云 OSS 是 <b>virtual-hosted</b>
 * （{@code https://{bucket}.{endpoint}/{key}}），并且它的 V4 签名同样绑定 host ——
 * 所以 OSS 侧既不能复用 {@link #buildRawUrl}/{@link #extractKey}（URL 形态不同），
 * 也不能复用 {@link #toPublicUrl}（OSS 不存在"内外网地址不一致"的问题，
 * 硬做字符串替换反而会破坏签名）。见 {@code OssFileStorage} 的对照说明。</p>
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractMinioStorage {

    protected final MinioClient minioClient;

    /** MinIO 专属配置（endpoint / public-endpoint / 凭据） */
    protected final MinioProperties properties;

    /** 通用配置（bucket / 三个有效期）—— 与厂商无关，见 {@link StorageProperties} */
    protected final StorageProperties storage;

    /**
     * 把 SDK 用内部地址签出来的 URL，换成对外可访问的地址。
     *
     * <p>若两个 endpoint 配置相同（本地开发常见），则原样返回，不做任何替换。</p>
     *
     * <p>⚠️ 这是 <b>MinIO 专属</b>的处理方式（原因见类注释）。</p>
     */
    protected String toPublicUrl(String signedUrl) {
        String internal = trimEndSlash(properties.getEndpoint());
        String publicEp = trimEndSlash(properties.getPublicEndpoint());
        if (internal == null || publicEp == null || internal.equals(publicEp)) {
            return signedUrl;
        }
        String result = signedUrl.replace(internal, publicEp);
        log.debug("预签名地址替换：{} -> {}", internal, publicEp);
        return result;
    }

    /**
     * 生成不带签名的展示地址（仅用于展示，不能直接访问）。
     *
     * <p>★ <b>path-style</b>：{@code {publicEndpoint}/{bucket}/{key}} —— MinIO 的形态。
     * OSS 是 virtual-hosted，它的实现里是另一套拼法。</p>
     */
    protected String buildRawUrl(String key) {
        return trimEndSlash(properties.getPublicEndpoint()) + "/" + storage.getBucket() + "/" + key;
    }

    /**
     * 从原始地址里反解出对象 key。
     *
     * <p>封面的地址是前端从 {@code /files} 接口拿到的完整 URL，
     * 而生成签名需要的是对象 key，所以要反解一下。</p>
     *
     * <p>★ 与 {@link #buildRawUrl} 成对：<b>拼法与解法必须一致</b>，
     * 否则会出现"能上传、但显示不出来"（拼对了解错，或反之）。</p>
     */
    protected String extractKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String prefix = trimEndSlash(properties.getPublicEndpoint()) + "/" + storage.getBucket() + "/";
        if (rawUrl.startsWith(prefix)) {
            return rawUrl.substring(prefix.length());
        }
        // 不是本服务的地址（比如外部链接），原样返回，上层再做判断
        return rawUrl;
    }

    private static String trimEndSlash(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
