package com.fitmind.learning.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 笔记保存 / 修改请求。
 */
@Data
@Schema(description = "笔记请求")
public class NoteFormDTO {

    @Schema(description = "笔记id（修改时必填）")
    private Long id;

    @Schema(description = "课程id", example = "1")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @Schema(description = "训练日id", example = "1")
    @NotNull(message = "训练日id不能为空")
    private Long chapterId;

    @Schema(description = "训练动作id", example = "1")
    @NotNull(message = "训练动作id不能为空")
    private Long sectionId;

    @Schema(description = "笔记对应的视频时间点（秒）", example = "75")
    @NotNull(message = "笔记时间点不能为空")
    private Integer noteMoment;

    @Schema(description = "笔记内容", example = "肩胛骨收紧再推，感觉发力点立刻不一样")
    @NotBlank(message = "笔记内容不能为空")
    private String content;

    @Schema(description = "是否隐私笔记", example = "false")
    @NotNull(message = "隐私标记不能为空")
    private Boolean isPrivate;
}
