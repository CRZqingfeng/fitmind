package com.fitmind.pay.constants;

import lombok.Getter;

/**
 * 退款单状态。
 *
 * <p><b>★ 为什么"退款中"是一个必须存在的状态</b>：真实支付渠道的退款几乎都是
 * <b>异步</b>的。调微信的退款接口，它只会告诉你"已受理"，钱到底退没退出去
 * 要等它后续回调，或者我们主动去查。如果只有"成功/失败"两个状态，
 * 就必然要在"等待"这段时间里把订单当成成功或失败来处理 —— 两种都是错的。</p>
 *
 * <p>有了"退款中"，就有一件事变得必须做：<b>补偿任务</b>
 * （见 {@code RefundOrderCheckJob}）。因为回调可能丢（网络抖动、服务重启、
 * 渠道故障），如果只依赖回调，丢了就是永久悬空 —— 用户的钱退了，
 * 我们的单子还写着"退款中"，两边对不上账。</p>
 */
@Getter
public enum RefundStatus {

    /** 渠道已受理，钱还没确认退回去 */
    REFUNDING(1, "退款中"),
    /** 钱已经退回用户 */
    SUCCESS(2, "退款成功"),
    /** 退款被渠道拒绝 / 执行失败 */
    FAILED(3, "退款失败");

    private final int value;
    private final String desc;

    RefundStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public static RefundStatus of(Integer value) {
        if (value == null) {
            return null;
        }
        for (RefundStatus s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        return null;
    }

    /** 是否还在途中（补偿任务要捞的就是这些） */
    public static boolean inProgress(Integer value) {
        return value != null && value == REFUNDING.value;
    }

    /** 是否已结束（成功或失败） */
    public static boolean finished(Integer value) {
        return value != null && (value == SUCCESS.value || value == FAILED.value);
    }
}
