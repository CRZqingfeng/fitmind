package com.fitmind.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 加入购物车请求。
 */
@Data
@Schema(description = "加入购物车")
public class CartAddDTO {

    @Schema(description = "课程id", example = "1")
    @NotNull(message = "课程id不能为空")
    private Long courseId;
}
