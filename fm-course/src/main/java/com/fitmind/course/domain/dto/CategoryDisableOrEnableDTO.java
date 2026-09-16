package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分类启用/停用请求。
 */
@Data
@Schema(description = "分类启用停用请求")
public class CategoryDisableOrEnableDTO {

    @Schema(description = "分类id", example = "1")
    @NotNull(message = "分类id不能为空")
    private Long id;

    @Schema(description = "状态：1-启用 2-禁用", example = "2")
    @NotNull(message = "状态不能为空")
    private Integer status;
}
