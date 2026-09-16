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
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractMinioStorage {

    protected final MinioClient minioClient;
    protected final MinioProperties properties;

    /**
     * 把 SDK 用内部地址签出来的 URL，换成对外可访问的地址。
     *
     * <p>若两个 endpoint 配置相同（本地开发常见），则原样返回，不做任何替换。</p>
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

    /** 生成不带签名的展示地址（仅用于管理端列表展示，不能直接访问） */
    protected String buildRawUrl(String key) {
        return trimEndSlash(properties.getPublicEndpoint()) + "/" + properties.getBucket() + "/" + key;
    }

    /**
     * 从原始地址里反解出对象 key。
     *
     * <p>封面的地址是前端从 {@code /files} 接口拿到的完整 URL，
     * 而生成签名需要的是对象 key，所以要反解一下。</p>
     */
    protected String extractKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String prefix = trimEndSlash(properties.getPublicEndpoint()) + "/" + properties.getBucket() + "/";
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
