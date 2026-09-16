package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 保存训练动作信息（★ 健身化的核心载体）。
 *
 * <p>一次提交两件事：<b>绑定训练视频</b> + <b>设置动作参数</b>
 * （组数、次数、组间休息、建议负重）。</p>
 *
 * <p>为什么合在一起提交：这两块都是"这个动作怎么练"的信息，
 * 教练在前端也是在同一个表单里填完的。</p>
 */
@Data
@Schema(description = "保存训练动作信息")
public class CourseMediaDTO {

    @Schema(description = "训练动作的目录id（该动作所属的节）", example = "1")
    @NotNull(message = "目录id不能为空")
    private Long cataId;

    @Schema(description = "媒资id，来自 fm-media 上传接口", example = "1")
    private Long mediaId;

    @Schema(description = "视频名称（上架时会校验不能为空）", example = "哑铃卧推标准示范.mp4")
    private String videoName;

    @Schema(description = "视频时长，单位秒", example = "180")
    private Integer mediaDuration;

    @Schema(description = "是否可试看：true-可免费试看", example = "false")
    private Boolean trailer;

    // ==================== ★★ 动作参数 ====================

    @Schema(description = "组数", example = "4")
    private Integer sets;

    @Schema(description = "每组次数", example = "10")
    private Integer reps;

    @Schema(description = "组间休息，单位秒", example = "90")
    private Integer restSeconds;

    @Schema(description = "建议负重，单位kg", example = "20")
    private BigDecimal weight;

    @Schema(description = "计时类动作时长，单位秒（平板支撑等，与 reps 二选一）", example = "60")
    private Integer durationSeconds;

    @Schema(description = "动作类型：1-力量 2-有氧 3-拉伸", example = "1")
    private Integer actionType;

    @Schema(description = "该动作所需器械", example = "哑铃")
    private String equipment;

    @Schema(description = "目标肌群", example = "胸大肌,肱三头肌")
    private String targetMuscles;
}
