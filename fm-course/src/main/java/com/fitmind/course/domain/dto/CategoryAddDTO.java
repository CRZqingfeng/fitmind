package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增分类请求。
 */
@Data
@Schema(description = "新增分类请求")
public class CategoryAddDTO {

    @Schema(description = "分类名称", example = "增肌")
    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Schema(description = "父分类id，一级分类传 0", example = "0")
    @NotNull(message = "父分类id不能为空")
    private Long parentId;

    @Schema(description = "排序优先级，越大越靠前", example = "1")
    private Integer priority = 0;
}
