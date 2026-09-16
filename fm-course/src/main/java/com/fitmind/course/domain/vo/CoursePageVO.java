package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程列表项。
 */
@Data
@Schema(description = "课程列表项")
public class CoursePageVO {

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称")
    private String name;

    @Schema(description = "课程类型")
    private Integer courseType;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "三级分类id")
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

    @Schema(description = "训练动作数量")
    private Integer sectionNum;

    @Schema(description = "视频总时长，单位秒")
    private Integer mediaDuration;

    @Schema(description = "发布次数")
    private Integer publishTimes;

    @Schema(description = "最近上架时间")
    private LocalDateTime publishTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
