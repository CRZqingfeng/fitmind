package com.fitmind.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 动作考核视图。
 */
@Data
@Accessors(chain = true)
@Schema(description = "动作考核")
public class ActionAssessmentVO {

    @Schema(description = "记录id")
    private Long id;

    private Long userId;

    @Schema(description = "学员名（管理端列表用）")
    private String userName;

    private Long courseId;

    @Schema(description = "训练动作名（管理端列表用）")
    private String cataName;

    private Long cataId;

    private String videoUrl;

    private String remark;

    @Schema(description = "状态：1-待评分 2-已评分")
    private Integer status;

    @Schema(description = "状态文案")
    private String statusDesc;

    @Schema(description = "教练评分 0-100")
    private Integer score;

    private String comment;

    private Long coachId;

    private LocalDateTime submitTime;

    private LocalDateTime reviewTime;
}
