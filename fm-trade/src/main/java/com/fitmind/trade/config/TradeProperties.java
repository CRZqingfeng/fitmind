package com.fitmind.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 交易业务参数。
 *
 * <p>为什么用 {@code @ConfigurationProperties} 而不是散落的 {@code @Value}：
 * 这些参数是<b>成组</b>的（订单一组、购物车一组），集中成一个类以后
 * 改配置时要动哪些、默认值是多少一目了然，IDE 也能跳转。
 * {@code @Value} 适合"整个应用只有一两个"的孤立参数。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "trade")
public class TradeProperties {

    private Cart cart = new Cart();

    private Order order = new Order();

    /** 购物车参数 */
    @Data
    public static class Cart {
        /** 购物车最多放几门课（防刷） */
        private int maxCourseAmount = 10;
    }

    /** 订单参数 */
    @Data
    public static class Order {
        /** 下单后多少分钟未支付自动关闭 */
        private int payOverMinutes = 30;

        /**
         * 兜底扫描的宽限分钟数。
         *
         * <p>兜底只扫「创建时间早于 now - (payOver + grace)」的订单 ——
         * 比正常超时多留一截，避免正常到期的订单被延迟队列和兜底扫描同时处理
         * （虽然条件更新能保证不出错，但会白跑一次更新）。</p>
         */
        private int timeoutScanGraceMinutes = 5;

        /** 单次任务最多处理多少笔 */
        private int timeoutCancelBatch = 100;
    }
}
