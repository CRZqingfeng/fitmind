package com.fitmind.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 退款申请表单（学员提交）。
 *
 * <p><b>★ 两个"故意没有"的字段</b>：</p>
 * <ol>
 *   <li><b>没有退款金额</b>：金额由服务端从订单明细的 {@code real_pay_amount} 取。
 *       常见实现也是这么做的，理由与"申请支付金额由交易侧给出"完全一样 ——
 *       <b>钱的事只有一个数据源</b>。接了前端传值，改个请求体就能退 1 万块。</li>
 *   <li><b>没有 userId</b>：申请人固定是当前登录用户。</li>
 * </ol>
 *
 * <p><b>★ 为什么用 courseId 而不是 orderDetailId</b>：
 * 用户看到的是"我要退这门课"，他手里根本没有订单明细 id ——
 * 订单详情接口返回的明细里也不含 id（那是内部标识）。
 * 让用户传一个拿不到的值，接口设计上就是走不通的。
 * 服务端拿 (userId, courseId) 去查"最近一笔已支付的明细"，
 * 这个转换本来就该在服务端做。</p>
 */
@Data
@Schema(description = "退款申请")
public class RefundFormDTO {

    @Schema(description = "要退款的课程id")
    @NotNull(message = "请选择要退款的课程")
    private Long courseId;

    @Schema(description = "退款原因")
    @NotBlank(message = "请选择退款原因")
    private String refundReason;

    @Schema(description = "问题说明（选填，补充描述）")
    private String questionDesc;
}
