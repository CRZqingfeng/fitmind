package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 小测判分结果。
 *
 * <p>只有提交之后才会返回正确答案与解析（{@code rightAnswer} / {@code analysis}）——
 * 拉题接口里没有这两个字段。</p>
 */
@Data
@Schema(description = "小测判分结果")
public class PracticeResultVO {

    @Schema(description = "本次得分（答对题目的分值之和）")
    private Integer score;

    @Schema(description = "本节点满分")
    private Integer totalScore;

    @Schema(description = "答对题数")
    private Integer correctCount;

    @Schema(description = "题目总数")
    private Integer totalCount;

    @Schema(description = "逐题结果")
    private List<Detail> details;

    /** 单题判分结果 */
    @Data
    @Schema(description = "单题判分结果")
    public static class Detail {

        @Schema(description = "题目id")
        private Long subjectId;

        @Schema(description = "题干（判分后回显，前端不用再去对照）")
        private String name;

        @Schema(description = "是否答对")
        private Boolean correct;

        @Schema(description = "学员提交的答案（规范化后的，多选已排序）")
        private String yourAnswer;

        @Schema(description = "正确答案")
        private String rightAnswer;

        @Schema(description = "答案解析")
        private String analysis;
    }
}
