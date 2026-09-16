package com.fitmind.media.storage;

import lombok.Data;

/**
 * 媒资上传结果（由存储层返回）。
 *
 * <p>常见实现里还有个 {@code requestId} 字段，那是腾讯云点播专属的"本次上传请求 id"，
 * MinIO 没有这个概念，故去掉。</p>
 */
@Data
public class MediaUploadResult {

    /** 对象 key（对应 media 表的 file_id） */
    private String fileId;

    /** 不带签名的播放地址 */
    private String mediaUrl;

    /** 封面地址 */
    private String coverUrl;

    /** 原始文件名 */
    private String filename;
}
