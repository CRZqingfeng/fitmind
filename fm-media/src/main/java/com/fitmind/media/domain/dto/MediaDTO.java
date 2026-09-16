package com.fitmind.media.domain.dto;

import com.fitmind.media.enums.FileStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 媒资信息（内部调用也用这个，不包 R）。
 */
@Data
@Schema(description = "媒资信息")
public class MediaDTO {

    @Schema(description = "媒资id")
    private Long id;

    @Schema(description = "视频原始文件名")
    private String filename;

    @Schema(description = "对象存储key")
    private String fileId;

    @Schema(description = "时长，单位秒")
    private Integer duration;

    @Schema(description = "文件大小，单位字节")
    private Long size;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "状态")
    private FileStatus status;
}
