package com.fitmind.message.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额显示工具。
 *
 * <p>系统内部金额一律用<b>分</b>（int）存储与传输 —— 这是 M0 就定下的约定，
 * 原因是浮点数存钱会出现 {@code 0.1 + 0.2 != 0.3} 这种经典问题。
 * 只有在"给人看"的那一刻才转成元。</p>
 *
 * <p>★ 用 {@link BigDecimal} 而不是 {@code fen / 100.0}：
 * 后者又回到浮点数上了，转出来可能是 {@code 199.00000000000003}，
 * 直接拼进通知文案会很难看。</p>
 */
public final class AmountUtil {

    private AmountUtil() {
    }

    /**
     * 分 → 元的字符串（保留 2 位小数，去掉多余的 0）。
     *
     * <pre>
     *   19900 → "199.00"
     *   19950 → "199.50"
     *   199   → "1.99"
     *   null  → "0"
     * </pre>
     */
    public static String fenToYuan(Integer fen) {
        if (fen == null) {
            return "0";
        }
        return BigDecimal.valueOf(fen)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
