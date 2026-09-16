package com.fitmind.api.dto.media;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 媒资信息（跨服务传输用）。
 *
 * <p>这里不复用 fm-media 的领域对象，是为了让 fm-api 保持独立：
 * 依赖方（如 fm-course）只需要引 fm-api，不必引整个 fm-media。</p>
 *
 * <p>status 用 Integer 而非枚举，同样是为了避免 fm-api 反向依赖 fm-media。</p>
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

    @Schema(description = "状态：1-上传中 2-已上传 3-已使用")
    private Integer status;
}
