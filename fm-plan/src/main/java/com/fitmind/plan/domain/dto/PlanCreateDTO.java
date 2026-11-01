package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建计划入参。
 *
 * <p>所有字段都可空 —— "创建空计划"是主路径（用户先建壳，再挑训练日、再挑动作）。
 * 不传 title 时由后端填 {@code 未命名计划}，<b>前端不要自己拼这个字符串</b>：
 * 一旦前后端各写一份默认名，用户看到的是"未命名计划"，库里存的是"新计划"。</p>
 */
@Data
@Schema(description = "创建计划入参")
public class PlanCreateDTO {

    @Schema(description = "计划名；不传 = 未命名计划", example = "周一练胸")
    @Size(max = 64, message = "计划名最多 64 个字")
    private String title;

    @Schema(description = "一句话描述")
    @Size(max = 512, message = "描述最多 512 个字")
    private String summary;

    @Schema(description = "训练目标：增肌 / 减脂", example = "增肌")
    private String goal;

    @Schema(description = "难度：0-新手 1-进阶 2-专业", example = "0")
    @Min(value = 0, message = "难度只有 0/1/2")
    @Max(value = 2, message = "难度只有 0/1/2")
    private Integer difficulty;
}
