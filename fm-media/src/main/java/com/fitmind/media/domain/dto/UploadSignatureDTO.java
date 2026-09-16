package com.fitmind.media.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 上传签名（前端拿到后直传 MinIO）。
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
}
