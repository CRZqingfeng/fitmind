package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 只带课程 id 的请求（上架 / 下架等操作用）。
 */
@Data
@Schema(description = "课程id请求")
public class CourseIdDTO {

    @Schema(description = "课程id", example = "1")
    @NotNull(message = "课程id不能为空")
    private Long id;
}
