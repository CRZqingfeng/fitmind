package com.fitmind.learning.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提问 / 修改问题请求。
 *
 * <p>★ 关于 {@code title}：原先是 {@code @NotBlank}（必填）。M10-1 第 2 期起改为<b>可空</b> ——
 * 播放页的评论区是"边看边问"的形态，让用户先想个标题反人性（B 站的评论也没有标题）。
 * 不填时由服务端取 {@code description} 前 20 字，**老调用方（社区页）照常传 title，行为不变**。</p>
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

    @Schema(
            description = "标题 —— ★ 可空：不填时由服务端自动取「问题描述」前 20 字",
            example = "哑铃卧推手腕疼怎么办")
    @Size(max = 60, message = "标题最多 60 字")
    private String title;

    @Schema(description = "问题描述", example = "做到第三组时手腕内侧发酸，是握法问题吗")
    @NotBlank(message = "问题描述不能为空")
    private String description;

    @Schema(description = "是否匿名提问", example = "false")
    private Boolean anonymity;
}
