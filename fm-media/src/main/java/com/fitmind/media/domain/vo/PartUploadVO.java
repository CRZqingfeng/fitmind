package com.fitmind.media.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 单片上传完成后的进度回执。
 */
@Data
@Accessors(chain = true)
@Schema(description = "分片上传进度")
public class PartUploadVO {

    @Schema(description = "本次上传的分片序号")
    private Integer partNumber;

    @Schema(description = "分片总数")
    private Integer chunkTotal;

    @Schema(description = "已成功上传的分片序号")
    private List<Integer> uploadedChunks;

    @Schema(description = "已上传字节数")
    private Long uploadedBytes;

    @Schema(description = "已上传百分比（0-100）")
    private Integer uploadedPercent;
}
