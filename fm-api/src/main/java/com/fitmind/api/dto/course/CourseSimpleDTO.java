package com.fitmind.api.dto.course;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 课程简要信息（跨服务批量查询用）。
 *
 * <p>课表分页、计划列表这类「一批课程只展示名称/封面/进度基数」的场景用；
 * 需要目录时再用 {@link CourseFullDTO}。</p>
 */
@Data
@Schema(description = "课程简要信息")
public class CourseSimpleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称")
    private String name;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "课程类型：1-训练计划课 2-单动作教学 3-理论饮食课")
    private Integer courseType;

    @Schema(description = "是否免费：1-免费 2-付费")
    private Integer free;

    @Schema(description = "价格，单位分")
    private Integer price;

    @Schema(description = "状态：2-已上架 3-下架 4-已完结")
    private Integer status;

    @Schema(description = "训练动作总数")
    private Integer sectionNum;

    @Schema(description = "课程有效期，单位天")
    private Integer validDuration;

    @Schema(description = "训练目标：1-增肌 2-减脂 3-塑形 4-保持")
    private Integer goalType;

    @Schema(description = "适用水平")
    private Integer trainingLevel;
}
