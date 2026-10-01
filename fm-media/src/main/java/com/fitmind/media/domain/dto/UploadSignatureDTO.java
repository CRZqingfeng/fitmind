package com.fitmind.media.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 上传签名（前端拿到后直传对象存储）。
 *
 * <p>拿到 {@code uploadUrl} 后，前端直接用 PUT 把文件传上去，
 * <b>不经过我们的服务器</b>——训练视频动辄几十 MB，走中转太浪费带宽和内存。</p>
 *
 * <p>传完必须回调确认接口，否则这条记录会一直是「上传中」状态。</p>
 */
@Data
@Schema(description = "上传签名")
public class UploadSignatureDTO {

    @Schema(description = "预先创建的媒资id，确认上传时要带回来")
    private Long mediaId;

    @Schema(description = "对象存储key")
    private String key;

    @Schema(description = "预签名 PUT 地址，前端直传用")
    private String uploadUrl;

    @Schema(description = "签名有效期，单位秒")
    private int expire;

    /**
     * ★★ 直传时**必须原样带上的** Content-Type。
     *
     * <p><b>为什么需要它</b>：阿里云 OSS 的 V4 签名把 {@code Content-Type} 纳入签名，
     * 而浏览器用 XHR 传 {@code File} 时<b>一定会带</b> Content-Type（还会自动补 MIME）。
     * 于是"服务端签名时用的值"必须等于"前端上传时带的值的" —— 差一个字符就是
     * {@code 403 SignatureDoesNotMatch}，而且报错完全看不出是这个原因。</p>
     *
     * <p>所以这个值由<b>服务端算、随响应回传</b>，前端原样使用 —— 只有一个来源，不会不一致。
     * （前端此前自己猜 MIME，且 {@code CourseEditView} 还优先用 {@code file.type}，
     * 这两个值在多环境下的可能不同，属于隐患。）</p>
     *
     * <p>⚠️ MinIO 模式（{@code fm.storage.type=minio}）下本字段为 {@code null} ——
     * MinIO 不把 Content-Type 纳入签名，前端沿用自己猜的值，行为与改造前完全一致。</p>
     */
    @Schema(description = "直传时必须原样带上的 Content-Type；为空表示后端不要求（MinIO 模式）")
    private String contentType;
}
