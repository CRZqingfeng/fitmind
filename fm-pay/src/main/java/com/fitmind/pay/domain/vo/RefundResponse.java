package com.fitmind.pay.domain.vo;

import com.fitmind.pay.constants.RefundStatus;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * ★ 渠道退款的原始返回（<b>防腐层</b>）。
 *
 * <p><b>为什么要有这一层，而不是让渠道直接返回我们的 {@link RefundResultVO}</b>：
 * 不同渠道的表达方式完全不同 —— 微信用 {@code SUCCESS/CLOSED/ABNORMAL} 字符串，
 * 支付宝用 {@code 10000/40004} 数字码，有的渠道"受理成功"和"退款成功"是两回事。
 * 如果让业务代码直接读渠道的字段，那么<b>每接一个渠道就要在业务逻辑里加一个 if</b>。</p>
 *
 * <p>加一层 {@code RefundResponse} 之后，每个渠道实现类负责把"自己的方言"翻译成
 * 这套统一结构（{@link #success} 表示调用是否成功、{@link #status} 表示资金状态），
 * 业务侧只认这一套。这就是防腐层（Anti-Corruption Layer）的作用：
 * <b>把外部系统的不一致挡在边界上</b>，而不是让它渗透进来。</p>
 */
@Data
@Accessors(chain = true)
public class RefundResponse {

    /** 调用渠道是否成功（网络通不通、参数对不对）—— 与"钱退没退"是两件事 */
    private Boolean success;

    /** 资金状态，取值见 {@link RefundStatus} */
    private Integer status;

    /** 退款金额（分）；渠道可能返回实际退款额，与请求值略有差异时以它为准 */
    private Integer amount;

    /** 渠道标识（回填给业务侧做对账） */
    private String channel;

    /** 渠道返回码 */
    private String code;

    /** 渠道返回描述 / 失败原因 */
    private String msg;

    /** 退款是否已经成功到账 */
    public boolean refundSuccess() {
        return Boolean.TRUE.equals(success) && status != null
                && status == RefundStatus.SUCCESS.getValue();
    }

    /** 钱还没退出去（渠道已受理） */
    public boolean refundRunning() {
        return Boolean.TRUE.equals(success) && status != null
                && status == RefundStatus.REFUNDING.getValue();
    }

    // ==================== 工厂方法：让渠道实现类的返回意图一眼可见 ====================

    public static RefundResponse running(String msg) {
        return new RefundResponse().setSuccess(true).setStatus(RefundStatus.REFUNDING.getValue()).setMsg(msg);
    }

    public static RefundResponse success(String msg) {
        return new RefundResponse().setSuccess(true).setStatus(RefundStatus.SUCCESS.getValue()).setMsg(msg);
    }

    public static RefundResponse fail(String msg) {
        return new RefundResponse().setSuccess(false).setStatus(RefundStatus.FAILED.getValue()).setMsg(msg);
    }
}
