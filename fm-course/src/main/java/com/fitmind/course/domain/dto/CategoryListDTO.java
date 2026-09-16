package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分类列表查询条件。
 */
@Data
@Schema(description = "分类查询条件")
public class CategoryListDTO {

    @Schema(description = "层级：1-训练目标 2-训练部位 3-器械场景", example = "1")
    private Integer level;

    @Schema(description = "父分类id", example = "0")
    private Long parentId;

    @Schema(description = "状态：1-启用 2-禁用", example = "1")
    private Integer status;

    @Schema(description = "是否返回树形结构（默认 true）", example = "true")
    private Boolean tree = true;
}
