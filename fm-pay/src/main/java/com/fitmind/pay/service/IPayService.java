package com.fitmind.pay.service;

import com.fitmind.pay.constants.PayConstants;
import com.fitmind.pay.domain.po.PayOrder;
import com.fitmind.pay.domain.vo.RefundResponse;

/**
 * 支付渠道统一接口。
 *
 * <p><b>★ 路由方式：实现类用渠道码当 Spring Bean 名</b></p>
 * <pre>
 *   &#64;Service(PayConstants.MOCK_CHANNEL_CODE)   // "mockPay"
 *   public class MockPayService implements IPayService { ... }
 *
 *   // 业务侧
 *   private final Map&lt;String, IPayService&gt; channels;   // Spring 会自动按 Bean 名装填
 *   channels.get(payOrder.getPayChannelCode()).createPayOrder(payOrder);
 * </pre>
 *
 * <p>这样新增一个渠道 = 写一个实现类 + 往 {@code pay_channel} 插一行，
 * <b>不用改任何业务代码</b>。比"if-else 判断渠道码"或"策略工厂注册表"都干净 ——
 * 依赖注入容器本身就是那个注册表。</p>
 */
public interface IPayService {

    /**
     * 向渠道申请支付（生成二维码 / 跳转链接）。
     *
     * <p>实现方负责把返回的支付链接写回 {@code payOrder.qrCodeUrl}。</p>
     */
    void createPayOrder(PayOrder payOrder);

    /**
     * 主动向渠道查询结果（供交易模块轮询；Mock 渠道直接读本地库即可）。
     *
     * @return 是否已支付成功
     */
    boolean queryPayResult(String payOrderNo);

    // ==================== 退款（M4-4） ====================

    /**
     * 向渠道发起退款。
     *
     * <p><b>★ 它的返回值不是终态</b>：真实渠道的退款多为异步，返回"退款中"是常态。
     * 调用方（{@code RefundOrderServiceImpl}）必须把返回值当"<b>这一刻</b>的状态"
     * 落库，最终结果等回调或补偿任务。</p>
     *
     * @param payOrderNo    原支付单号（渠道侧靠它找到原交易）
     * @param refundOrderNo 本次退款单号（渠道会把它记在回执里）
     * @param refundAmount  退款金额（分）
     * @param totalAmount   原支付总额（分）—— 部分退款时渠道要求同时传，用于一致性校验
     */
    RefundResponse refundOrder(String payOrderNo, String refundOrderNo,
                               Integer refundAmount, Integer totalAmount);

    /**
     * 主动查询退款结果（供<b>补偿任务</b>对账用）。
     *
     * <p>为什么必须有这个方法：回调可能丢（网络抖动、服务重启、渠道故障）。
     * 只依赖回调的系统会出现"渠道退了钱、我们的单子还写着退款中"的对账差异，
     * 而且<b>永远不会自愈</b>。有了主动查询，补偿任务就能把状态纠回来。</p>
     */
    RefundResponse queryRefundStatus(String payOrderNo, String refundOrderNo);
}
