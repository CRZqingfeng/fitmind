package com.fitmind.exam.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交动作考核。
 *
 * <p>视频走 fm-media 的签名直传（与课程视频同一套），这里只收 mediaId ——
 * 让"文件怎么传"和"考核怎么记"分开。</p>
 */
@Data
@Schema(description = "提交动作考核")
public class ActionSubmitDTO {

    @Schema(description = "所属课程id（选填）")
    private Long courseId;

    @Schema(description = "所属训练动作id（选填）")
    private Long cataId;

    @Schema(description = "★ 动作视频的媒资id")
    @NotNull(message = "请先上传动作视频")
    private Long mediaId;

    @Schema(description = "自述（选填，例如说一下做这个动作时的感受）")
    private String remark;
}
