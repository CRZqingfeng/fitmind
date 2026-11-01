package com.fitmind.media.utils;

import java.util.Map;

/**
 * 由文件名 / 对象 key 推断 Content-Type。
 *
 * <p><b>★ 为什么它必须是一个共享的单一实现，而不是各写一份？</b></p>
 *
 * <p>阿里云 OSS 的 V4 签名把 {@code Content-Type} 纳入签名，而浏览器直传时
 * **一定会带** Content-Type。于是出现一条硬约束：</p>
 *
 * <pre>
 *   服务端"签名时用的 Content-Type"  必须等于  前端"上传时带的 Content-Type"
 * </pre>
 *
 * <p>一旦不等 → {@code 403 SignatureDoesNotMatch}，且报错完全看不出是 Content-Type 的问题。
 * 所以这个值只能有一个来源：{@code applyUploadSignature} 用它算出并随响应回传，
 * 前端原样带上（见 {@code UploadSignatureDTO.contentType}）。</p>
 *
 * <p>★ 本表与前端 {@code utils/media-upload.ts} / {@code utils/upload.ts} 的
 * {@code guessMime} **保持一致**：MinIO 模式下后端不回传 contentType，
 * 前端会回退到自己猜的值 —— 两边不同就会在 MinIO 上留下"对象 Content-Type 不对"
 * 的历史坑（M7-7 撞过，509 个对象里 51 个受影响）。</p>
 */
public final class ContentTypeUtil {

    private ContentTypeUtil() {
    }

    public static final String DEFAULT = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("webm", "video/webm"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml")
    );

    /** 按扩展名推断；认不出返回 {@link #DEFAULT} */
    public static String ofName(String name) {
        if (name == null) {
            return DEFAULT;
        }
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return DEFAULT;
        }
        return TYPES.getOrDefault(lower.substring(dot + 1), DEFAULT);
    }
}
