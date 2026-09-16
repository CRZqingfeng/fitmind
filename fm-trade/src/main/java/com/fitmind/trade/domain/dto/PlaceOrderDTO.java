package com.fitmind.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 下单请求。
 *
 * <p><b>★ 为什么请求里要带 orderId</b>：它由前端调预下单接口拿到（雪花 id），
 * 提交时带回来。这样下单接口<b>天然幂等</b> —— 用户手抖点两次、
 * 网络超时后重试，第二次会被主键挡住（{@code DuplicateKeyException}），
 * 服务端捕获后返回"订单已存在"而不是又造一笔单。</p>
 *
 * <p>如果让服务端生成 id，"重复提交"就变成了一道需要额外去重的题目
 * （查最近 N 分钟有没有相同课程组合的订单？不可靠）。</p>
 */
@Data
@Schema(description = "下单请求")
public class PlaceOrderDTO {

    @Schema(description = "订单id（由预下单接口生成）", example = "1757000000000000000")
    @NotNull(message = "订单id不能为空，请先调用预下单接口")
    private Long orderId;

    @Schema(description = "要购买的课程id列表", example = "[1, 2]")
    @NotEmpty(message = "请选择要购买的课程")
    private List<Long> courseIds;

    /**
     * 本次要用的用户券id（不传 = 不用券）。
     *
     * <p>前端从「叠加推荐」接口拿到方案后，把用户选中的券id带回来。
     * <b>金额一律由服务端重算</b> —— 前端传的只用来确定"用哪几张券"。</p>
     */
    @Schema(description = "使用的用户券id（不传表示不用券）")
    private List<Long> userCouponIds;
}
