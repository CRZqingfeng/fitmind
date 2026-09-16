package com.fitmind.learning.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提问 / 修改问题请求。
 */
@Data
@Schema(description = "互动问题请求")
public class QuestionFormDTO {

    @Schema(description = "课程id", example = "1")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @Schema(description = "训练日id", example = "1")
    @NotNull(message = "训练日id不能为空")
    private Long chapterId;

    @Schema(description = "训练动作id", example = "1")
    @NotNull(message = "训练动作id不能为空")
    private Long sectionId;

    @Schema(description = "标题", example = "哑铃卧推手腕疼怎么办")
    @NotBlank(message = "标题不能为空")
    private String title;

    @Schema(description = "问题描述", example = "做到第三组时手腕内侧发酸，是握法问题吗")
    @NotBlank(message = "问题描述不能为空")
    private String description;

    @Schema(description = "是否匿名提问", example = "false")
    private Boolean anonymity;
}
