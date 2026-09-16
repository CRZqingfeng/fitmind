package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程基本信息（含正文），供编辑页回显。
 */
@Data
@Schema(description = "课程基本信息")
public class CourseBaseInfoVO {

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称")
    private String name;

    @Schema(description = "课程类型")
    private Integer courseType;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "一级分类")
    private Long firstCateId;

    @Schema(description = "二级分类")
    private Long secondCateId;

    @Schema(description = "三级分类")
    private Long thirdCateId;

    @Schema(description = "是否免费：1-免费 2-付费")
    private Integer free;

    @Schema(description = "价格，单位分")
    private Integer price;

    @Schema(description = "状态：1-待上架 2-已上架 3-下架 4-已完结")
    private Integer status;

    @Schema(description = "状态名称")
    private String statusDesc;

    @Schema(description = "填写进度")
    private Integer step;

    @Schema(description = "课程有效期，单位天")
    private Integer validDuration;

    @Schema(description = "训练目标")
    private Integer goalType;

    @Schema(description = "适用水平")
    private Integer trainingLevel;

    @Schema(description = "训练周期总天数")
    private Integer totalDays;

    @Schema(description = "建议每周训练次数")
    private Integer weeklyFrequency;

    @Schema(description = "所需器械")
    private String equipment;

    @Schema(description = "单次训练预计消耗，单位千卡")
    private Integer estimatedCalories;

    @Schema(description = "报名开始时间")
    private LocalDateTime enrollStartTime;

    @Schema(description = "报名结束时间")
    private LocalDateTime enrollEndTime;

    @Schema(description = "是否来自草稿（true 表示课程在编辑态，未上架）")
    private Boolean fromDraft;

    // ==================== 正文 ====================

    @Schema(description = "课程介绍")
    private String courseIntroduce;

    @Schema(description = "适合人群")
    private String usePeople;

    @Schema(description = "课程详情")
    private String courseDetail;

    @Schema(description = "训练注意事项")
    private String precautions;

    @Schema(description = "所需器械清单")
    private String requiredEquipment;

    @Schema(description = "最近上架时间")
    private LocalDateTime publishTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
