package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新分类请求。
 */
@Data
@Schema(description = "更新分类请求")
public class CategoryUpdateDTO {

    @Schema(description = "分类id", example = "1")
    @NotNull(message = "分类id不能为空")
    private Long id;

    @Schema(description = "分类名称", example = "增肌")
    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Schema(description = "排序优先级", example = "1")
    private Integer priority;
}
