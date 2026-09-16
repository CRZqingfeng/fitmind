package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 提交小测作答的请求。
 */
@Data
@Schema(description = "提交小测作答")
public class PracticeSubmitDTO {

    @Schema(description = "目录节点id（训练动作 / 测评）", example = "1001")
    @NotNull(message = "目录节点id不能为空")
    private Long cataId;

    @Schema(description = "逐题作答")
    @NotNull(message = "作答内容不能为空")
    private List<AnswerItem> answers;

    /** 单题作答 */
    @Data
    @Schema(description = "单题作答")
    public static class AnswerItem {

        @Schema(description = "题目id", example = "1")
        @NotNull(message = "题目id不能为空")
        private Long subjectId;

        /**
         * 学员提交的答案，格式与题目存储的 answer 一致：
         * 选项序号（从 1 开始），多选逗号分隔（如 "1,3"）；判断题 1-正确 2-错误。
         */
        @Schema(description = "答案：选项序号，多选逗号分隔", example = "1,3")
        @NotBlank(message = "答案不能为空")
        private String answer;
    }
}
