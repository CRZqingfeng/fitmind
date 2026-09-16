package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 题目保存请求（新增 / 修改共用）。
 *
 * <p>交叉字段的校验（选项个数、答案是否越界）放在 Service 里——
 * Bean Validation 做不了「答案必须落在选项范围内」这种约束。</p>
 */
@Data
@Schema(description = "题目保存请求")
public class SubjectFormDTO {

    @Schema(description = "题目id；不传表示新增", example = "1")
    private Long id;

    @Schema(description = "所属课程id", example = "1")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @Schema(description = "题干", example = "哑铃卧推时，肩胛骨应该怎么做？")
    @NotBlank(message = "题干不能为空")
    private String name;

    @Schema(description = "题型：1-单选 2-多选 3-不定向 4-判断", example = "1")
    @NotNull(message = "题型不能为空")
    private Integer subjectType;

    @Schema(description = "难易度：1-简单 2-中等 3-困难", example = "1")
    @NotNull(message = "难度不能为空")
    private Integer difficulty;

    @Schema(description = "选项数组（选择题 2~10 个；判断题不传）",
            example = "[\"后缩并下沉\", \"放松前伸\", \"耸起\"]")
    private List<String> options;

    @Schema(description = "正确答案：选项序号（从 1 开始），多个用逗号分隔；判断题 1-正确 2-错误",
            example = "1")
    @NotBlank(message = "正确答案不能为空")
    private String answer;

    @Schema(description = "答案解析（提交作答后才下发给学员）",
            example = "肩胛后缩下沉能给卧推提供稳定的发力平台，避免肩关节代偿。")
    private String analysis;

    @Schema(description = "题目分值（小测计分用），不传默认 5", example = "5")
    private Integer score;
}
