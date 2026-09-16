package com.fitmind.pay.service.impl;

import com.fitmind.pay.constants.PayConstants;
import com.fitmind.pay.constants.RefundStatus;
import com.fitmind.pay.domain.po.PayOrder;
import com.fitmind.pay.domain.po.RefundOrder;
import com.fitmind.pay.domain.vo.RefundResponse;
import com.fitmind.pay.mapper.PayOrderMapper;
import com.fitmind.pay.mapper.RefundOrderMapper;
import com.fitmind.pay.service.IPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * ★ 模拟支付渠道。
 *
 * <p><b>它解决什么问题</b>：常见实现的 支付服务 只接了真实的微信 / 支付宝
 * （微信要真商户号，支付宝靠改沙箱域名"假装"）。结果是 ——
 * 本地没有对应账号时，<b>整条支付链路跑不起来</b>，
 * 号称做过支付的人其实没验证过"支付成功之后会怎样"。</p>
 *
 * <p>我们的做法：把"第三方"换成自己。</p>
 * <ul>
 *   <li>申请支付 → 返回一个指向本地模拟收银台的链接</li>
 *   <li>"用户付款" → 调 {@code POST /ps/mock/pay/{payOrderNo}}，
 *       它<b>走的是和真实第三方回调完全相同的代码路径</b>
 *       （{@code NotifyController} → 验签 → 条件更新 → 发 MQ）</li>
 * </ul>
 *
 * <p>于是验签、幂等、MQ、订单更新、加课表<b>全都被真实地跑了一遍</b>，
 * 只是触发方从"微信服务器"变成了"我们的测试脚本"。
 * 代价是简历上写不了"对接微信支付"，换来的是<b>这条链路可以自动化验收</b> ——
 * 而常见实现 7 个坑里有 3 个恰好就在这条链路上。</p>
 *
 * <p><b>Bean 名就是渠道码</b>（{@value PayConstants#MOCK_CHANNEL_CODE}），
 * 与 {@code pay_channel} 表里的那一行对应。</p>
 */
@Slf4j
@Service(PayConstants.MOCK_CHANNEL_CODE)
@RequiredArgsConstructor
public class MockPayService implements IPayService {

    private final PayOrderMapper payOrderMapper;
    private final RefundOrderMapper refundOrderMapper;

    /**
     * Mock 渠道的"异步退款耗时"（秒）。
     *
     * <p><b>★ 为什么故意引入一个延迟，而不是立即返回成功</b>：
     * 真实渠道的退款是异步的（微信要等它处理，通常几秒到几分钟）。
     * 如果 Mock 渠道当场返回成功，会有两个后果：
     * ① <b>补偿任务永远没活干</b> —— 那段"对账纠状态"的代码上线前一次都没被执行过；
     * ② 交易侧会误以为"退款是同步的"，写出依赖这个假设的代码，
     *    接真实渠道时立刻崩（真实渠道只会回"已受理"）。</p>
     *
     * <p>有了这个延迟，"退款中 → 补偿任务纠状态"这条路径<b>可被自动化验收</b>：
     * 脚本触发退款 → 断言状态是"退款中" → 等几秒 → 触发 job → 断言变成"成功"。</p>
     */
    @Value("${pay.mock.refund-delay-seconds:3}")
    private int refundDelaySeconds;

    @Override
    public void createPayOrder(PayOrder payOrder) {
        // 真实渠道这里要调第三方 SDK 拿二维码；我们只拼一个本地收银台地址。
        // 页面上放两个按钮「支付成功 / 支付失败」，点了就回调自己的 notify 接口。
        payOrder.setQrCodeUrl(PayConstants.MOCK_CASHIER_URL + payOrder.getPayOrderNo());
        log.info("[Mock渠道] 生成支付链接：payOrderNo={}, url={}",
                payOrder.getPayOrderNo(), payOrder.getQrCodeUrl());
    }

    @Override
    public boolean queryPayResult(String payOrderNo) {
        // Mock 渠道没有"第三方"可问，直接读本地支付单 —— 状态由回调驱动
        PayOrder po = payOrderMapper.getByPayOrderNo(payOrderNo);
        return po != null && po.getStatus() != null
                && po.getStatus() == PayConstants.PAY_STATUS_PAYED;
    }

    // ==================== 退款（M4-4） ====================

    /**
     * 发起退款：<b>返回"退款中"，而不是"成功"</b>。
     *
     * <p>这一行返回值的选择很关键。它刻意模仿真实渠道的行为：
     * 渠道只承诺"我受理了"，钱什么时候到账是它的事。
     * 于是调用方<b>被迫</b>去处理"结果待定"这种情况 ——
     * 而这恰恰是真实支付系统里最容易写错的地方。</p>
     *
     * <p>那最终怎么变成成功？两条路，互为备份：</p>
     * <ol>
     *   <li><b>回调</b>（主路径）：测试脚本调
     *       {@code POST /ps/mock/refund/{refundOrderNo}}，它代表"渠道稍后通知我们"，
     *       内部 HTTP 调真实的 {@code /ps/notify/refund/mockPay} 接口</li>
     *   <li><b>补偿任务</b>（兜底）：{@code RefundOrderCheckJob} 调本类的
     *       {@link #queryRefundStatus} 主动问渠道</li>
     * </ol>
     */
    @Override
    public RefundResponse refundOrder(String payOrderNo, String refundOrderNo,
                                      Integer refundAmount, Integer totalAmount) {
        log.info("[Mock渠道] 受理退款：payOrderNo={}, refundOrderNo={}, 金额={}分（原单 {}分）",
                payOrderNo, refundOrderNo, refundAmount, totalAmount);
        return RefundResponse.running("Mock 渠道已受理退款，结果待回调");
    }

    /**
     * 查询退款结果（补偿任务调用）。
     *
     * <p>Mock 渠道没有真第三方可问，于是用一条规则模拟"异步处理需要时间"：
     * <b>退款单创建满 {@code pay.mock.refund-delay-seconds} 秒后，视为渠道已退款到账</b>。
     * 这个延迟让补偿路径变得<b>可断言</b>（脚本等几秒就能看到状态被纠正）。</p>
     *
     * <p>注意它<b>只返回结论，不写库</b> —— 写库是调用方（Service）的事。
     * 渠道实现类的职责边界只到"翻译渠道的方言"为止，
     * 这样它可以被单独替换而不影响状态流转逻辑。</p>
     */
    @Override
    public RefundResponse queryRefundStatus(String payOrderNo, String refundOrderNo) {
        RefundOrder ro = refundOrderMapper.getByRefundOrderNo(refundOrderNo);
        if (ro == null) {
            return RefundResponse.fail("退款单不存在：" + refundOrderNo);
        }
        // 本地已是终态：直接把结论回给调用方（真实渠道也会返回它自己记录的结果）
        if (RefundStatus.finished(ro.getStatus())) {
            return ro.success()
                    ? RefundResponse.success("退款已成功")
                    : RefundResponse.fail(ro.getResultMsg());
        }
        // 仍在途：判断是否已超过"渠道处理耗时"
        LocalDateTime readyAt = ro.getCreateTime() == null
                ? LocalDateTime.now()
                : ro.getCreateTime().plusSeconds(refundDelaySeconds);
        if (LocalDateTime.now().isBefore(readyAt)) {
            return RefundResponse.running("渠道处理中，请稍后再查");
        }
        log.info("[Mock渠道] 退款已完成（模拟处理耗时 {}s）：refundOrderNo={}",
                refundDelaySeconds, refundOrderNo);
        return RefundResponse.success("Mock 渠道退款成功")
                .setAmount(ro.getRefundAmount())
                .setChannel(PayConstants.MOCK_CHANNEL_CODE)
                .setCode("SUCCESS");
    }
}
