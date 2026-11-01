package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 训练日重命名入参。
 *
 * <p><b>为什么 name 不加 @NotBlank</b>：按设计稿，"不传 = 保持原值"。
 * 加 @NotBlank 会把"没改名字"变成一个 400，前端每次保存都得把原值带回来。</p>
 */
@Data
@Schema(description = "训练日重命名入参")
public class PlanDayRenameDTO {

    @Schema(description = "新的训练日名；不传或空串 = 保持原值", example = "周一练胸")
    @Size(max = 32, message = "训练日名字最多 32 个字")
    private String name;
}
