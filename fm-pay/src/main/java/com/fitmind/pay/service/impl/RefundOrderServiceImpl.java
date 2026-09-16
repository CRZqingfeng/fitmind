package com.fitmind.pay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.core.util.IdUtil;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.mq.message.RefundResultMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.pay.constants.PayConstants;
import com.fitmind.pay.constants.PayErrorInfo;
import com.fitmind.pay.constants.RefundStatus;
import com.fitmind.pay.domain.dto.RefundApplyDTO;
import com.fitmind.pay.domain.po.PayOrder;
import com.fitmind.pay.domain.po.RefundOrder;
import com.fitmind.pay.domain.vo.RefundResponse;
import com.fitmind.pay.domain.vo.RefundResultVO;
import com.fitmind.pay.mapper.PayOrderMapper;
import com.fitmind.pay.mapper.RefundOrderMapper;
import com.fitmind.pay.service.IPayService;
import com.fitmind.pay.service.IRefundOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 退款单服务实现。
 *
 * <p><b>★ 本类的三段流程，每一段都有对应的正确性保障</b>：</p>
 *
 * <pre>
 *   ① 申请  applyRefund        —— 幂等：唯一索引 uk_biz_refund_order_no
 *   ② 结清  settle（私有）      —— 幂等：状态条件更新 WHERE status = 退款中
 *   ③ 补偿  queryAndSync       —— 与 ② 共用同一段结清逻辑，不会有两套状态流转
 * </pre>
 *
 * <p>其中 ② 是<b>唯一</b>改变退款终态的地方：回调（渠道推）和补偿（我们问）
 * 都汇到这里。这样"退款什么时候算成功"这个问题<b>只有一个答案</b>，
 * 而不是"回调里一套判断、定时任务里另一套"（常见实现正是在两处各写了一遍）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundOrderServiceImpl extends ServiceImpl<RefundOrderMapper, RefundOrder>
        implements IRefundOrderService {

    private final PayOrderMapper payOrderMapper;
    /** ★ 按渠道码装填的渠道实现（Bean 名 = 渠道码），退款复用 M4-2 的同一套路由 */
    private final Map<String, IPayService> payChannels;
    private final RabbitMqHelper rabbitMqHelper;

    // ==================== ① 申请退款 ====================

    @Override
    public RefundResultVO applyRefund(RefundApplyDTO dto) {
        // 1. 幂等查：同一笔业务退款申请已经有单子了，直接返回它的当前状态。
        //    这是"重复申请"的快速路径（并发场景由下面的唯一索引兜底）
        RefundOrder existed = baseMapper.getByBizRefundOrderNo(dto.getBizRefundOrderNo());
        if (existed != null) {
            log.info("退款申请已存在，直接返回：bizRefundOrderNo={}, 当前状态={}",
                    dto.getBizRefundOrderNo(), RefundStatus.of(existed.getStatus()));
            return toVO(existed, true);
        }
        // 2. 原支付单必须存在且已支付 —— 没付过钱退什么
        PayOrder payOrder = payOrderMapper.getPaidByBizOrderNo(
                dto.getBizOrderNo(), PayConstants.PAY_STATUS_PAYED);
        if (payOrder == null) {
            throw new BizIllegalException(PayErrorInfo.Msg.REFUND_PAY_ORDER_NOT_PAID);
        }
        // 3. 单笔金额校验
        Integer amount = dto.getRefundAmount();
        if (amount == null || amount <= 0) {
            throw new BizIllegalException(PayErrorInfo.Msg.REFUND_AMOUNT_ILLEGAL);
        }
        if (amount > payOrder.getAmount()) {
            throw new BizIllegalException(String.format(
                    PayErrorInfo.Msg.REFUND_AMOUNT_EXCEED, amount, payOrder.getAmount()));
        }
        // 4. ★ 累计金额校验（资金安全）：已成功退出去的 + 本次 ≤ 原支付额。
        //    挡的是"同一笔订单被拆成多笔超额退款申请"，没有这道校验就会退多。
        Integer refunded = baseMapper.sumRefundedAmount(
                dto.getBizOrderNo(), RefundStatus.SUCCESS.getValue());
        int alreadyRefunded = refunded == null ? 0 : refunded;
        if (alreadyRefunded + amount > payOrder.getAmount()) {
            throw new BizIllegalException(String.format(
                    PayErrorInfo.Msg.REFUND_TOTAL_EXCEED, alreadyRefunded, amount, payOrder.getAmount()));
        }
        // 5. 建单。状态先落在"退款中" —— 因为我们还不知道渠道的结果
        RefundOrder ro = new RefundOrder()
                .setRefundOrderNo(PayConstants.REFUND_ORDER_NO_PREFIX + IdUtil.getSnowflakeNextIdStr())
                .setBizRefundOrderNo(dto.getBizRefundOrderNo())
                .setBizOrderNo(dto.getBizOrderNo())
                .setBizUserId(dto.getBizUserId())
                .setPayOrderNo(payOrder.getPayOrderNo())
                .setPayChannelCode(payOrder.getPayChannelCode())
                .setRefundAmount(amount)
                .setTotalAmount(payOrder.getAmount())
                // 全额退款标记：真实渠道在处理部分退款时需要知道这一点
                .setIsSplit(amount.equals(payOrder.getAmount()) ? 1 : 0)
                .setStatus(RefundStatus.REFUNDING.getValue());
        try {
            save(ro);
        } catch (DuplicateKeyException e) {
            // ★ 并发重复申请：两个请求同时通过了第 1 步的检查，这里由唯一索引挡住第二个。
            //   这不是异常，是"别人已经建好了"，直接读回来返回
            log.info("并发重复退款申请，已被唯一索引挡住：bizRefundOrderNo={}", dto.getBizRefundOrderNo());
            RefundOrder concurrent = baseMapper.getByBizRefundOrderNo(dto.getBizRefundOrderNo());
            return toVO(concurrent, true);
        }
        // 6. 调渠道发起退款
        IPayService channel = payChannels.get(payOrder.getPayChannelCode());
        if (channel == null) {
            // 渠道实现缺失属于配置与代码不一致，暴露而不是静默失败
            throw new BizIllegalException(String.format(
                    PayErrorInfo.Msg.CHANNEL_NOT_SUPPORTED, payOrder.getPayChannelCode()));
        }
        RefundResponse resp;
        try {
            resp = channel.refundOrder(ro.getPayOrderNo(), ro.getRefundOrderNo(),
                    ro.getRefundAmount(), ro.getTotalAmount());
        } catch (Exception e) {
            // 渠道调用异常：保持"退款中"不变，交给补偿任务重试。
            // ★ 这里**不能**直接标成失败 —— 因为请求可能已经到达渠道（网络超时是最典型的
            //   "未知结果"），标失败会导致后来渠道真退了钱、我们却显示失败，用户会重复申诉。
            log.error("调用退款渠道异常，保持退款中待补偿：refundOrderNo={}", ro.getRefundOrderNo(), e);
            return toVO(baseMapper.getByRefundOrderNo(ro.getRefundOrderNo()), false);
        }
        // 7. 渠道若当场给了终态（支付宝可能直接成功；Mock 渠道会回"退款中"），立刻结清
        if (resp.refundSuccess()) {
            settle(ro.getRefundOrderNo(), RefundStatus.SUCCESS.getValue(),
                    resp.getChannel(), resp.getCode(), resp.getMsg(), resp.getAmount());
        } else if (!Boolean.TRUE.equals(resp.getSuccess())) {
            // 渠道明确拒绝（参数错、余额不足等）—— 这种是确定的失败，可以直接结清
            settle(ro.getRefundOrderNo(), RefundStatus.FAILED.getValue(),
                    resp.getChannel(), resp.getCode(), resp.getMsg(), null);
        } else {
            // 退款中：只回填渠道信息，等回调或补偿
            log.info("渠道已受理退款，等待结果：refundOrderNo={}, msg={}",
                    ro.getRefundOrderNo(), resp.getMsg());
        }
        return toVO(baseMapper.getByRefundOrderNo(ro.getRefundOrderNo()), false);
    }

    // ==================== ② 结清（唯一的终态入口） ====================

    /**
     * ★★ 把一笔退款推进到终态。<b>回调与补偿共用这一处</b>。
     *
     * <p>关键是第一行的<b>条件更新</b>：只有"退款中 → 成功/失败"这次改动成功，
     * 才会写回执、才会发 MQ。重复回调、回调与补偿撞车，都会返回 0 行，
     * 于是不发消息、交易侧也不会重复处理退款。</p>
     *
     * @return true = 本次调用真的结清了；false = 已被处理过或状态不符
     */
    private boolean settle(String refundOrderNo, int targetStatus, String channel,
                           String code, String msg, Integer actualAmount) {
        int n = baseMapper.changeStatus(refundOrderNo, RefundStatus.REFUNDING.getValue(), targetStatus);
        if (n == 0) {
            log.info("退款单已是终态，本次结清被忽略（幂等）：refundOrderNo={}", refundOrderNo);
            return false;
        }
        baseMapper.markFinish(refundOrderNo, channel, code, msg, actualAmount);
        RefundOrder ro = baseMapper.getByRefundOrderNo(refundOrderNo);
        // 通知交易侧（它去改订单状态、撤销报名、发通知）
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.PAY_EXCHANGE,
                MqConstants.Key.REFUND_RESULT_KEY,
                RefundResultMessage.of(ro.getBizRefundOrderNo(), ro.getBizOrderNo(),
                        ro.getRefundOrderNo(), ro.getBizUserId(), ro.getRefundAmount(),
                        targetStatus, ro.getRefundChannel(), msg, LocalDateTime.now()));
        log.info("退款结清：refundOrderNo={}, bizRefundOrderNo={}, 结果={}",
                refundOrderNo, ro.getBizRefundOrderNo(), RefundStatus.of(targetStatus));
        return true;
    }

    // ==================== ③ 回调与补偿 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleRefundNotify(String refundOrderNo, boolean success, String failReason) {
        RefundOrder ro = baseMapper.getByRefundOrderNo(refundOrderNo);
        if (ro == null) {
            throw new BizIllegalException(PayErrorInfo.Msg.REFUND_ORDER_NOT_EXISTS);
        }
        if (RefundStatus.finished(ro.getStatus())) {
            log.info("退款单已结清，重复回调被忽略：refundOrderNo={}", refundOrderNo);
            return false;
        }
        String msg = success ? "渠道退款成功" : failReason;
        return settle(refundOrderNo,
                success ? RefundStatus.SUCCESS.getValue() : RefundStatus.FAILED.getValue(),
                PayConstants.MOCK_CHANNEL_CODE.equals(ro.getPayChannelCode())
                        ? PayConstants.MOCK_CHANNEL_CODE : ro.getRefundChannel(),
                success ? "SUCCESS" : "FAIL", msg, ro.getRefundAmount());
    }

    @Override
    public boolean queryAndSync(String refundOrderNo) {
        RefundOrder ro = baseMapper.getByRefundOrderNo(refundOrderNo);
        if (ro == null || RefundStatus.finished(ro.getStatus())) {
            return false;
        }
        IPayService channel = payChannels.get(ro.getPayChannelCode());
        if (channel == null) {
            log.error("退款单渠道实现缺失，无法对账：refundOrderNo={}, channel={}",
                    refundOrderNo, ro.getPayChannelCode());
            return false;
        }
        RefundResponse resp = channel.queryRefundStatus(ro.getPayOrderNo(), ro.getRefundOrderNo());
        if (resp == null || !Boolean.TRUE.equals(resp.getSuccess())) {
            // 查询本身失败（渠道不可用）：保持原状，等下一轮补偿
            log.warn("查询退款结果失败，保持退款中：refundOrderNo={}, msg={}",
                    refundOrderNo, resp == null ? "null" : resp.getMsg());
            return false;
        }
        if (resp.refundRunning()) {
            // 渠道还在处理 —— 这就是"退款中"状态存在的意义
            return false;
        }
        return settle(refundOrderNo,
                resp.refundSuccess() ? RefundStatus.SUCCESS.getValue() : RefundStatus.FAILED.getValue(),
                resp.getChannel(), resp.getCode(), resp.getMsg(), resp.getAmount());
    }

    @Override
    public RefundResultVO queryByBizRefundOrderNo(String bizRefundOrderNo) {
        RefundOrder ro = baseMapper.getByBizRefundOrderNo(bizRefundOrderNo);
        if (ro == null) {
            throw new BizIllegalException(PayErrorInfo.Msg.REFUND_ORDER_NOT_EXISTS);
        }
        return toVO(ro, false);
    }

    @Override
    public List<RefundOrder> listRunningPage(int pageNo, int size) {
        int offset = Math.max(0, (pageNo - 1) * size);
        return baseMapper.listRunningByPage(RefundStatus.REFUNDING.getValue(), offset, size);
    }

    private RefundResultVO toVO(RefundOrder ro, boolean reused) {
        if (ro == null) {
            return null;
        }
        return new RefundResultVO()
                .setRefundOrderNo(ro.getRefundOrderNo())
                .setBizRefundOrderNo(ro.getBizRefundOrderNo())
                .setBizOrderNo(ro.getBizOrderNo())
                .setRefundAmount(ro.getRefundAmount())
                .setStatus(ro.getStatus())
                .setRefundChannel(ro.getRefundChannel())
                .setMessage(ro.getResultMsg())
                .setReused(reused);
    }
}
