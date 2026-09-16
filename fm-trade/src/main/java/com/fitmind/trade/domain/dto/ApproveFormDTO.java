package com.fitmind.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 退款审批表单（管理端）。
 */
@Data
@Schema(description = "退款审批")
public class ApproveFormDTO {

    @Schema(description = "退款申请id")
    @NotNull(message = "退款申请id不能为空")
    private Long id;

    @Schema(description = "审批结果：1-同意 2-拒绝")
    @NotNull(message = "请选择审批结果")
    @Min(value = 1, message = "审批结果只能是 1-同意 或 2-拒绝")
    @Max(value = 2, message = "审批结果只能是 1-同意 或 2-拒绝")
    private Integer approveType;

    @Schema(description = "审批意见")
    private String approveOpinion;

    @Schema(description = "备注")
    private String remark;
}
