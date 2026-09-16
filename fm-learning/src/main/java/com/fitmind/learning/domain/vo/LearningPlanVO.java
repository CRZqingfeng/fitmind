package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 学习计划条目。
 */
@Data
@Schema(description = "学习计划")
public class LearningPlanVO {

    @Schema(description = "课表id")
    private Long id;

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "课程名称")
    private String courseName;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "每周训练频率")
    private Integer weekFreq;

    @Schema(description = "已完成的训练动作数")
    private Integer learnedSections;

    @Schema(description = "课程总动作数")
    private Integer sections;

    @Schema(description = "课程状态")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;
}
