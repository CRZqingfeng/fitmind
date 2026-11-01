package com.fitmind.aigc.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 生成计划骨架的入参（全部可空）。
 *
 * <p>★ <b>为什么这里会有"体重/身高/目标体重"</b>：它们是<b>可选</b>的兜底。
 * 正常路径下 aigc 自己去 fm-user 读档案；但本机 fm-user 没起时档案永远是 null，
 * 于是"没有档案"和"读不到档案"会变成同一件事 —— 骨架接口就永远出不来，
 * 而前端手里其实已经有 fm-plan 刚算出来的真实体重/目标。
 * 允许前端把<u>服务端自己算过的数</u>带过来，比"编一个 3 次/周"诚实得多。</p>
 *
 * <p>⚠️ 这三项只用于<b>个性化措辞与骨架选择</b>，不参与任何鉴权判断，
 * 也不落库到"身体档案"（它只是本次请求的上下文）。</p>
 */
@Data
@Schema(description = "生成 AI 计划骨架的入参（全部可空）")
public class PlanSkeletonDTO {

    @Schema(description = "训练目标：1增肌 2减脂 3塑形 4保持（与 fm_user.goal_type 同口径）", example = "1")
    @Min(value = 1, message = "目标只能是 1~4")
    @Max(value = 4, message = "目标只能是 1~4")
    private Integer goalType;

    @Schema(description = "每周可训练次数 1~7；不传则取档案里的值", example = "4")
    @Min(value = 1, message = "每周可训练次数只能是 1~7")
    @Max(value = 7, message = "每周可训练次数只能是 1~7")
    private Integer weeklyFrequency;

    @Schema(description = "训练水平：1零基础 2初级 3中级 4高级", example = "2")
    @Min(value = 1, message = "训练水平只能是 1~4")
    @Max(value = 4, message = "训练水平只能是 1~4")
    private Integer trainingLevel;

    @Schema(description = "备注（用户想重点练什么）", example = "想练上半身")
    @Size(max = 120, message = "备注最多 120 字")
    private String note;

    // ---- 档案读不到时的兜底（由 fm-plan 算出来的真实数） ----

    @Schema(description = "当前体重（kg）；档案读不到时可传，不传 = 无个性化", example = "65.0")
    @DecimalMin(value = "20.0", message = "体重不在合理范围")
    @DecimalMax(value = "300.0", message = "体重不在合理范围")
    private BigDecimal weightKg;

    @Schema(description = "目标体重（kg）", example = "75.0")
    @DecimalMin(value = "20.0", message = "目标体重不在合理范围")
    @DecimalMax(value = "300.0", message = "目标体重不在合理范围")
    private BigDecimal targetWeightKg;

    @Schema(description = "身高（cm）", example = "180.0")
    @DecimalMin(value = "100.0", message = "身高不在合理范围")
    @DecimalMax(value = "250.0", message = "身高不在合理范围")
    private BigDecimal heightCm;
}
