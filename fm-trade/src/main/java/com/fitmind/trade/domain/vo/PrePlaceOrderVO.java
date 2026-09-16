package com.fitmind.trade.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.Accessors;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预下单结果（不落库，纯预览）。
 *
 * <p>前端点"去结算"时先调它，拿到三样东西：</p>
 * <ol>
 *   <li>{@code orderId} —— 提交订单时要带回来（天然幂等的关键）</li>
 *   <li>金额明细 —— 让用户在付钱前看到"总共多少、优惠多少"</li>
 *   <li>{@code payOutTime} —— 这次下单的支付截止时间</li>
 * </ol>
 */
@Data
@Accessors(chain = true)
@Schema(description = "预下单结果")
public class PrePlaceOrderVO {

    @Schema(description = "订单id（提交订单时带回）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @Schema(description = "商品总额（分）")
    private Integer totalAmount;

    @Schema(description = "优惠金额（分）")
    private Integer discountAmount;

    @Schema(description = "实付金额（分）")
    private Integer realAmount;

    @Schema(description = "支付截止时间（超过这个时间订单会被自动关闭）")
    private LocalDateTime payOutTime;

    @Schema(description = "购买的课程预览")
    private List<Item> courses;

    /** 预览用的课程条目 */
    @Data
    @Schema(description = "课程预览项")
    public static class Item {

        @Schema(description = "课程id")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long courseId;

        @Schema(description = "课程名称")
        private String courseName;

        @Schema(description = "封面")
        private String coverUrl;

        @Schema(description = "价格（分）")
        private Integer price;

        @Schema(description = "课程有效期（天）")
        private Integer validDuration;
    }
}
