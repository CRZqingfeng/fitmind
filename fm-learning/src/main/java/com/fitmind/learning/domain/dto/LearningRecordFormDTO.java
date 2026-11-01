package com.fitmind.learning.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 学习进度上报请求。
 *
 * <p>播放器在「暂停 / 切换动作 / 离开页面 / 标记完成」等时机上报；
 * 未过半的进度经 Redis ZSet 合并落库，过半的直接完成。</p>
 */
@Data
@Schema(description = "学习进度上报")
public class LearningRecordFormDTO {

    @Schema(description = "课表id", example = "1")
    @NotNull(message = "课表id不能为空")
    private Long lessonId;

    @Schema(description = "训练动作id", example = "1")
    @NotNull(message = "训练动作id不能为空")
    private Long sectionId;

    @Schema(description = "当前播放进度，单位秒", example = "120")
    @NotNull(message = "播放进度不能为空")
    private Integer moment;

    @Schema(description = "视频总时长，单位秒", example = "180")
    @NotNull(message = "视频时长不能为空")
    private Integer duration;
}
