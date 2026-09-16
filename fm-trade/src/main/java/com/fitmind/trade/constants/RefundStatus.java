package com.fitmind.trade.constants;

import com.fitmind.common.exceptions.BizIllegalException;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 退款申请状态。
 *
 * <p><b>★ 与 {@link OrderStatus} 同一套设计</b>：把"允许的流转"声明成表，
 * 任何状态变更先过 {@link #checkTransfer}。理由也一样 ——
 * 常见实现的退款状态流转散在 5 个方法里（申请 / 审批 / 撤销 / 回调 / 补偿），
 * 没有任何集中的合法性校验，于是"已拒绝的申请被回调改成成功"
 * 这种矛盾状态在代码里是<b>查不出来</b>的。</p>
 *
 * <pre>
 *                    ┌── 审批拒绝 ──▶ REJECT(4)   ┐
 *   UN_APPROVE(1) ───┼── 学员撤销 ──▶ CANCEL(2)   ├── 这些都会让**订单回滚到已支付**
 *                    └── 审批同意 ──▶ AGREE(3)    ┘
 *                                       │
 *                          ┌────────────┴────────────┐
 *                       渠道成功                  渠道失败
 *                          ▼                        ▼
 *                    SUCCESS(5) 终态          FAILED(6) ──▶ 订单也回滚
 * </pre>
 *
 * <p>注意最下面那个分叉：<b>失败也要回滚订单</b>。
 * 常见实现在这里同时踩了两个坑：① 失败不回滚订单；② 失败后不允许重新申请。
 * 结果就是用户的钱没退成、订单也废了。</p>
 */
@Getter
public enum RefundStatus {

    /** 提交了申请，等着管理员审批 */
    UN_APPROVE(1, "待审批"),
    /** 学员自己撤销了申请（钱没动） */
    CANCEL(2, "已取消"),
    /** 管理员同意，等待渠道退款 */
    AGREE(3, "同意退款"),
    /** 管理员拒绝（钱没动） */
    REJECT(4, "已拒绝"),
    /** 渠道退款到账（终态） */
    SUCCESS(5, "退款成功"),
    /** 渠道退款失败（终态，可重新申请） */
    FAILED(6, "退款失败");

    private final int value;
    private final String desc;

    RefundStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 允许的流转：key = 目标状态，value = 允许的来源状态 */
    private static final Map<RefundStatus, Set<RefundStatus>> TRANSITIONS;

    static {
        Map<RefundStatus, Set<RefundStatus>> m = new EnumMap<>(RefundStatus.class);
        m.put(AGREE, EnumSet.of(UN_APPROVE));
        m.put(REJECT, EnumSet.of(UN_APPROVE));
        m.put(CANCEL, EnumSet.of(UN_APPROVE));
        m.put(SUCCESS, EnumSet.of(AGREE));
        m.put(FAILED, EnumSet.of(AGREE));
        TRANSITIONS = Collections.unmodifiableMap(m);
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

    /** 进行中（这两个状态下不允许对同一明细再发起申请；审批页也要捞它们） */
    public static boolean inProgress(Integer value) {
        return value != null && (value == UN_APPROVE.value || value == AGREE.value);
    }

    /** 已终结（取消 / 拒绝 / 成功 / 失败） */
    public static boolean finished(Integer value) {
        return value != null && !inProgress(value);
    }

    /** 是否"钱真的退出去了" */
    public static boolean isSuccess(Integer value) {
        return value != null && value == SUCCESS.value;
    }

    public static boolean canTransfer(RefundStatus from, RefundStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(to, Set.of()).contains(from);
    }

    /** 非法流转直接抛：这属于代码写错了，不该静默放过 */
    public static void checkTransfer(RefundStatus from, RefundStatus to) {
        if (!canTransfer(from, to)) {
            throw new BizIllegalException(String.format(
                    "退款状态不允许从【%s】变更为【%s】", desc(from), desc(to)));
        }
    }

    public static String desc(RefundStatus status) {
        return status == null ? "无" : status.desc;
    }
}
