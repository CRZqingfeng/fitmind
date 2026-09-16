package com.fitmind.remark.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 点赞 / 取消点赞请求。
 */
@Data
@Schema(description = "点赞请求")
public class LikeRecordFormDTO {

    @Schema(description = "业务id（回答id / 笔记id）", example = "1")
    @NotNull(message = "业务id不能为空")
    private Long bizId;

    @Schema(description = "业务类型：QA-问答回答 NOTE-笔记", example = "QA")
    @NotNull(message = "业务类型不能为空")
    private String bizType;

    @Schema(description = "true-点赞 false-取消点赞", example = "true")
    @NotNull(message = "点赞状态不能为空")
    private Boolean liked;
}
