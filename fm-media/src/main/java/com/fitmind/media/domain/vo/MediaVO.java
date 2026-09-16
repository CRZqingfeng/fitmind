package com.fitmind.media.domain.vo;

import com.fitmind.media.enums.FileStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒资列表项。
 *
 * <p>{@code coverUrl} 是<b>带签名的可直接显示</b>的地址（每次查询临时生成），
 * 这样管理端列表页拿去就能渲染缩略图，不用再调一次接口换签名。</p>
 */
@Data
@Schema(description = "媒资列表项")
public class MediaVO {

    @Schema(description = "媒资id")
    private Long id;

    @Schema(description = "视频名称")
    private String filename;

    @Schema(description = "封面地址（带签名，可直接显示）")
    private String coverUrl;

    @Schema(description = "时长，单位秒")
    private Integer duration;

    @Schema(description = "文件大小，单位字节")
    private Long size;

    @Schema(description = "被课程引用次数")
    private Integer useTimes;

    @Schema(description = "状态")
    private FileStatus status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "创建者")
    private Long creater;
}
