package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑计划入参（P0 补：此前计划名建完就改不了）。
 *
 * <p>语义与 {@code PlanDayRenameDTO} 保持一致：<b>不传 / 传空串 = 保持原值</b>，
 * 不是"清空"。理由是前端最常见的操作就是"点开编辑、只改其中一项、原样保存"，
 * 如果把"没传"当成"要清空"，用户一保存就会把描述抹掉。</p>
 *
 * <p>唯一例外是 {@code summary}：传空串表示<b>真的要清空描述</b>（描述本来就允许没有）。</p>
 *
 * <p>★ 官方计划一律不能走这个接口（{@code ownerId=0}），要改请先「执行本计划」复制一份。</p>
 */
@Data
@Schema(description = "编辑计划入参（都可空；空 = 不改）")
public class PlanUpdateDTO {

    @Schema(description = "计划名；不传 = 不改", example = "周一练胸 + 周三练背")
    @Size(max = 64, message = "计划名最多 64 个字")
    private String title;

    @Schema(description = "一句话描述；传空串 = 清空描述")
    @Size(max = 512, message = "描述最多 512 个字")
    private String summary;

    @Schema(description = "训练目标：增肌 / 减脂；不传 = 不改", example = "增肌")
    private String goal;

    @Schema(description = "难度：0-新手 1-进阶 2-专业；不传 = 不改", example = "0")
    @Min(value = 0, message = "难度只有 0/1/2")
    @Max(value = 2, message = "难度只有 0/1/2")
    private Integer difficulty;
}
