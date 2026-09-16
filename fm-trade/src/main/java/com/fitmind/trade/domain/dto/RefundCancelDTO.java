package com.fitmind.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 撤销退款申请（学员自己撤回）。
 *
 * <p>两个字段二选一：按申请 id 撤，或按订单明细撤（后者用于
 * "我的课程"页面直接点撤回，那时前端手里只有明细 id）。</p>
 */
@Data
@Schema(description = "撤销退款申请")
public class RefundCancelDTO {

    @Schema(description = "退款申请id")
    private Long id;

    @Schema(description = "订单明细id（与 id 二选一）")
    private Long orderDetailId;
}
