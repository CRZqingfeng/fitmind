package com.fitmind.course.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 「某个节点挂了哪些题」（编辑页回显用）。
 *
 * <p>对应常见实现的 {@code CataSimpleSubjectVO}：按 cataId 分组返回，
 * 前端拿到的就是「每个动作 → 题目列表」的结构。</p>
 */
@Data
@Schema(description = "节点下的题目")
public class CataSubjectVO {

    @Schema(description = "目录节点id（字符串形式，避免前端精度丢失）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long cataId;

    @Schema(description = "节点名称（训练动作名 / 测评名）")
    private String cataName;

    @Schema(description = "该节点下的题目")
    private List<SubjectBriefVO> subjects;

    /** 题目简要信息（编辑页只需要 id 与题干就能渲染） */
    @Data
    @Schema(description = "题目简要信息")
    public static class SubjectBriefVO {

        @Schema(description = "题目id")
        private Long id;

        @Schema(description = "题干")
        private String name;

        @Schema(description = "题型")
        private Integer subjectType;

        @Schema(description = "难易度")
        private Integer difficulty;
    }
}
