package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课表条目（我的课程列表项）。
 */
@Data
@Schema(description = "我的课程")
public class LearningLessonVO {

    @Schema(description = "课表id")
    private Long id;

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "课程名称")
    private String courseName;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "课程状态：0-未学习 1-学习中 2-已学完 3-已过期")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "每周训练频率")
    private Integer weekFreq;

    @Schema(description = "计划状态：0-没有计划 1-计划进行中")
    private Integer planStatus;

    @Schema(description = "已完成的训练动作数")
    private Integer learnedSections;

    @Schema(description = "课程总动作数")
    private Integer sections;

    @Schema(description = "最近学习时间")
    private LocalDateTime latestLearnTime;

    @Schema(description = "到期时间")
    private LocalDateTime expireTime;

    @Schema(description = "报名时间")
    private LocalDateTime createTime;
}
