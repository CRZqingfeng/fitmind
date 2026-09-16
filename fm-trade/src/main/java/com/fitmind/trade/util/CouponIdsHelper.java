package com.fitmind.trade.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 读取订单使用的优惠券id。
 *
 * <p>券id 存在 {@code trade_order.coupon_ids}（JSON 列）里，
 * 但 JSON 列在 MyBatis 里需要实体类配合 typeHandler 才能读；
 * 这里单独查一次 varchar，避免在监听器里加载整个实体。</p>
 */
@Component
@RequiredArgsConstructor
public class CouponIdsHelper {

    private final com.fitmind.trade.mapper.TradeOrderMapper orderMapper;

    public List<Long> read(Long orderId) {
        String raw = orderMapper.queryCouponIds(orderId);
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return List.of();
        }
        // 不用正则：JSON 数组文本只需去掉 [ ] " 和空格，简单替换最清楚
        String cleaned = raw.replace("[", "").replace("]", "")
                .replace("\"", "").replace(" ", "");
        return java.util.Arrays.stream(cleaned.split(","))
                .filter(s -> !s.isBlank())
                .map(Long::valueOf)
                .toList();
    }
}
