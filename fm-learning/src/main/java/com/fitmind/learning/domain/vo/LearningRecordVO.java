package com.fitmind.learning.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单个训练动作的学习进度。
 */
@Data
@Schema(description = "动作学习进度")
public class LearningRecordVO {

    @Schema(description = "训练动作id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sectionId;

    @Schema(description = "断点秒数")
    private Integer moment;

    @Schema(description = "是否已完成")
    private Boolean finished;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;
}
