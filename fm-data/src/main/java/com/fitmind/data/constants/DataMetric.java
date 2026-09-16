package com.fitmind.data.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统计指标。
 *
 * <p>它是 Redis hash 的 field，也是 {@code data_daily} 的列名
 * （下划线转驼峰后同名），两边用同一份定义避免写错字符串。</p>
 */
@Getter
@AllArgsConstructor
public enum DataMetric {

    /** 签到次数 */
    SIGN_IN_COUNT("sign_in_count"),
    /** 完成训练次数 */
    LEARN_COUNT("learn_count"),
    /** 小测答对次数 */
    PRACTICE_COUNT("practice_count"),
    /** 成交订单数 */
    ORDER_COUNT("order_count"),
    /** 成交额（分） */
    ORDER_AMOUNT("order_amount"),
    /** 退款笔数 */
    REFUND_COUNT("refund_count"),
    /** 退款额（分） */
    REFUND_AMOUNT("refund_amount"),
    /** 体测次数 */
    ASSESS_COUNT("assess_count"),
    ;

    private final String field;
}
