package com.fitmind.learning.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 某门课程的整体学习进度（断点续播入口）。
 */
@Data
@Schema(description = "课程学习进度")
public class LearningProgressVO {

    @Schema(description = "课表id")
    private Long lessonId;

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "课程状态：0-未学习 1-学习中 2-已学完 3-已过期")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "最近学习的动作id（播放器直接跳到这里）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long latestSectionId;

    @Schema(description = "每个训练动作的进度")
    private List<LearningRecordVO> records;
}
