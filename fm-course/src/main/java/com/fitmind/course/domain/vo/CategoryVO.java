package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 课程分类（树形结构）。
 *
 * <p>{@code children} 只在树形查询时填充；平铺列表查询时为 null。</p>
 */
@Data
@Schema(description = "课程分类")
public class CategoryVO {

    @Schema(description = "分类id")
    private Long id;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "父分类id，一级为0")
    private Long parentId;

    @Schema(description = "层级：1-训练目标 2-训练部位 3-器械场景")
    private Integer level;

    @Schema(description = "排序优先级")
    private Integer priority;

    @Schema(description = "状态：1-启用 2-禁用")
    private Integer status;

    @Schema(description = "状态名称")
    private String statusDesc;

    @Schema(description = "该分类下的课程数量")
    private Integer courseNum;

    @Schema(description = "子分类")
    private List<CategoryVO> children;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
