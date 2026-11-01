package com.fitmind.trade.constants;

import com.fitmind.common.exceptions.BizIllegalException;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态。
 *
 * <p><b>★ 为什么把「允许的流转」写进枚举，而不是散在各个方法里</b>：
 * 常见实现的状态流转散落在 6 个方法中（{@code placeOrder} / {@code handlePaySuccess} /
 * {@code cancelOrder} / {@code enrolledFreeCourse} / {@code applyRefund} …），
 * 没有集中的合法性校验，于是留下了两个后果：
 * {@code FINISHED(4)} 这个状态<b>没有任何代码写入</b>（悬空状态），
 * 退款被拒之后状态<b>不回滚</b>（订单永远卡在"退款中"）。</p>
 *
 * <p>我们把"谁能变成谁"声明成一张表，任何状态变更都先过 {@link #checkTransfer}，
 * 让非法流转在<b>代码审查阶段就看得见</b>，而不是等它跑出脏数据。</p>
 *
 * <p>注意：这里<b>只是声明</b>，真正的并发安全靠 {@code changeStatus} 的
 * 条件更新（{@code WHERE status = 我预期的旧状态}）—— 两者缺一不可：
 * 枚举防的是"写错代码"，条件更新防的是"两个请求同时改"。</p>
 */
@Getter
public enum OrderStatus {

    /** 下单成功，等着付钱 */
    NO_PAY(1, "待支付"),
    /** 钱到账了 */
    PAYED(2, "已支付"),
    /** 超时未支付 / 用户主动取消 */
    CLOSED(3, "已关闭"),
    /** 免费课直接报名（不产生支付），保留该状态是为了让"已购"判定统一 */
    ENROLLED(5, "已报名"),
    /** 申请退款中（M4-4）：钱还没退出去，可能被拒绝/失败而回滚 */
    REFUNDED(6, "退款中"),
    /**
     * ★ 已退款（M4-4 新增终态）：钱确实退回到用户手里了。
     *
     * <p><b>为什么必须与"退款中"分开</b>：常见实现用 {@code REFUNDED(6)}
     * 同时表示"申请中"和"已完成"，语义坍塌成"退款过的订单"。
     * 对用户来说，这两个状态的区别恰恰是他最关心的 ——
     * "钱还在路上"和"钱已经到账"是两件事，前端也没法区分该显示哪个文案。</p>
     */
    REFUND_SUCCESS(7, "已退款"),
    ;

    private final int value;
    private final String desc;

    OrderStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * 允许的流转关系：key = 目标状态，value = 允许的来源状态集合。
     *
     * <p>读法：{@code PAYED} 只能由 {@code NO_PAY} 变过来 —— 换句话说，
     * 一个已经关闭的订单不可能被"支付"，这是支付回调幂等的前提。</p>
     */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS;

    static {
        Map<OrderStatus, Set<OrderStatus>> m = new EnumMap<>(OrderStatus.class);
        // ★ PAYED 有两个来源，第二个（REFUNDED）是 M4-4 加进来的"回滚"路径：
        //   退款申请被拒绝、渠道退款失败、学员撤销申请 —— 这三种情况下
        //   订单必须回到"已支付"，否则它就永远卡在"退款中"，
        //   用户再也不能对这门课发起退款（常见实现正是这样：REFUNDED 是单向黑洞）。
        //   回滚是**合法的业务流转**而不是错误，所以它必须显式出现在这张表里，
        //   而不是某个 Service 里偷偷 setStatus(PAYED) —— 后者无法审计、无法测试。
        m.put(PAYED, EnumSet.of(NO_PAY, REFUNDED));     // "已支付"可以由"待支付"或"退款中"变过来
        m.put(CLOSED, EnumSet.of(NO_PAY));              // "已关闭"只能由"待支付"变过来
        m.put(ENROLLED, EnumSet.of(NO_PAY));            // "已报名"只能由"待支付"变过来
        m.put(REFUNDED, EnumSet.of(PAYED));             // "退款中"只能由"已支付"变过来
        m.put(REFUND_SUCCESS, EnumSet.of(REFUNDED));    // "已退款"只能由"退款中"变过来
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    public static OrderStatus of(Integer value) {
        if (value == null) {
            return null;
        }
        for (OrderStatus s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        return null;
    }

    public static boolean canTransfer(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(to, Set.of()).contains(from);
    }

    /** 非法流转直接抛业务异常：这属于代码写错了，不该静默放过 */
    public static void checkTransfer(OrderStatus from, OrderStatus to) {
        if (!canTransfer(from, to)) {
            throw new BizIllegalException(String.format(
                    com.fitmind.trade.constants.TradeErrorInfo.Msg.ORDER_STATUS_TRANSFER_ILLEGAL,
                    desc(from), desc(to)));
        }
    }

    public static String desc(OrderStatus status) {
        return status == null ? "无" : status.desc;
    }
}
