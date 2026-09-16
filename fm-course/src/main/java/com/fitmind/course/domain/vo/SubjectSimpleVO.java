package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 题目（学员端拉题）——<b>不含答案、不含解析</b>。
 *
 * <p>为什么单开一个类而不是给 {@link SubjectVO} 的 answer 字段加
 * {@code @JsonIgnore}：一个是「把字段藏起来」，一个是<b>结构上就没有这个字段</b>。
 * 后者在改代码时不会因为有人复制粘贴而漏掉。</p>
 *
 * <p>答案与解析要等 {@code POST /practices/submit} 判分之后才随结果返回——
 * 否则打开控制台就能看到答案，小测就失去意义了。</p>
 */
@Data
@Schema(description = "题目（学员作答用）")
public class SubjectSimpleVO {

    @Schema(description = "题目id")
    private Long id;

    @Schema(description = "题干")
    private String name;

    @Schema(description = "题型：1-单选 2-多选 3-不定向 4-判断")
    private Integer subjectType;

    @Schema(description = "难易度：1-简单 2-中等 3-困难")
    private Integer difficulty;

    @Schema(description = "选项数组（判断题为空）")
    private List<String> options;

    @Schema(description = "题目分值")
    private Integer score;
}
