package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目详情（教练 / 管理端）——<b>含答案与解析</b>。
 *
 * <p>⚠️ 与给学员用的 {@link SubjectSimpleVO} 是<b>两个类</b>，不是同一个类少填几个字段。
 * 用两个类才能让「答案不下发」变成编译器能保证的事——
 * 只要写题接口的返回类型是 SubjectSimpleVO，就不可能顺手把 answer 带出去。</p>
 */
@Data
@Schema(description = "题目详情")
public class SubjectVO {

    @Schema(description = "题目id")
    private Long id;

    @Schema(description = "所属课程id")
    private Long courseId;

    @Schema(description = "题干")
    private String name;

    @Schema(description = "题型：1-单选 2-多选 3-不定向 4-判断")
    private Integer subjectType;

    @Schema(description = "难易度：1-简单 2-中等 3-困难")
    private Integer difficulty;

    @Schema(description = "选项数组（判断题为空）")
    private List<String> options;

    @Schema(description = "正确答案原文", example = "1,3")
    private String answer;

    @Schema(description = "正确答案解析成序号列表", example = "[1, 3]")
    private List<Integer> answers;

    @Schema(description = "答案解析")
    private String analysis;

    @Schema(description = "题目分值")
    private Integer score;

    @Schema(description = "被作答次数")
    private Integer answerTimes;

    @Schema(description = "答对次数")
    private Integer correctTimes;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
