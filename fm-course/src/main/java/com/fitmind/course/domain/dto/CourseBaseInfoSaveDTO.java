package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保存课程基本信息 + 正文。
 *
 * <p>常见实现把基本信息和正文放在一个表单里提交（前端就是一个「基本信息」页），
 * 所以这里也合成一个 DTO，一次保存两张表。</p>
 */
@Data
@Schema(description = "保存课程基本信息")
public class CourseBaseInfoSaveDTO {

    @Schema(description = "课程id；不传表示新建", example = "1")
    private Long id;

    @Schema(description = "课程名称", example = "21天增肌训练营")
    @NotBlank(message = "课程名称不能为空")
    private String name;

    @Schema(description = "课程类型：1-训练计划课 2-单动作教学 3-理论饮食课", example = "1")
    private Integer courseType = 1;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "一级分类（训练目标）", example = "1")
    private Long firstCateId;

    @Schema(description = "二级分类（训练部位）", example = "2")
    private Long secondCateId;

    @Schema(description = "三级分类（器械场景）", example = "3")
    private Long thirdCateId;

    @Schema(description = "是否免费：1-免费 2-付费", example = "1")
    private Integer free = 1;

    @Schema(description = "价格，单位分", example = "0")
    private Integer price = 0;

    @Schema(description = "课程有效期，单位天", example = "90")
    private Integer validDuration;

    @Schema(description = "训练目标：1-增肌 2-减脂 3-塑形 4-保持", example = "1")
    private Integer goalType;

    @Schema(description = "适用水平：1-零基础 2-初级 3-中级 4-高级", example = "2")
    private Integer trainingLevel;

    @Schema(description = "训练周期总天数", example = "21")
    private Integer totalDays;

    @Schema(description = "建议每周训练次数", example = "4")
    private Integer weeklyFrequency;

    @Schema(description = "所需器械，逗号分隔", example = "哑铃,单杠")
    private String equipment;

    @Schema(description = "单次训练预计消耗，单位千卡", example = "300")
    private Integer estimatedCalories;

    @Schema(description = "报名开始时间")
    private LocalDateTime enrollStartTime;

    @Schema(description = "报名结束时间")
    private LocalDateTime enrollEndTime;

    // ==================== 课程正文 ====================

    @Schema(description = "课程介绍")
    private String courseIntroduce;

    @Schema(description = "适合人群", example = "零基础、想增肌的上班族")
    private String usePeople;

    @Schema(description = "课程详情")
    private String courseDetail;

    @Schema(description = "训练注意事项（伤病提醒）")
    private String precautions;

    @Schema(description = "所需器械清单")
    private String requiredEquipment;
}
