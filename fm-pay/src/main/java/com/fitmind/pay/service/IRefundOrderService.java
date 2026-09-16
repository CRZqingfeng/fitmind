package com.fitmind.pay.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.pay.domain.dto.RefundApplyDTO;
import com.fitmind.pay.domain.po.RefundOrder;
import com.fitmind.pay.domain.vo.RefundResultVO;

import java.util.List;

/**
 * 退款单服务。
 */
public interface IRefundOrderService extends IService<RefundOrder> {

    /**
     * 申请退款（供 fm-trade 通过内部接口调用）。
     *
     * <p><b>幂等</b>：靠 {@code uk_biz_refund_order_no} 唯一索引 ——
     * 同一笔业务退款申请重复调用时，第二次会读到已存在的那张单并原样返回
     * （{@code reused=true}），不会被扣两次钱。</p>
     *
     * @return 此刻的退款状态。<b>注意可能仍是"退款中"</b>，最终结果靠回调或补偿
     */
    RefundResultVO applyRefund(RefundApplyDTO dto);

    /** 按业务退款单号查当前结果（供交易侧对账 / 兜底查询） */
    RefundResultVO queryByBizRefundOrderNo(String bizRefundOrderNo);

    /**
     * 处理渠道退款回调。
     *
     * <p>★ 与支付回调同构：<b>条件更新成功才发 MQ</b>。
     * 重复回调时第二次更新 0 行 → 不再发消息 → 交易侧不会重复处理。</p>
     *
     * @param success    true=退款成功；false=退款失败
     * @param failReason 失败原因（成功时为 null）
     * @return 本次是否真的结清了（false = 已经处理过或状态不符）
     */
    boolean handleRefundNotify(String refundOrderNo, boolean success, String failReason);

    /**
     * 主动查渠道并同步状态（补偿任务调用）。
     *
     * <p>与回调的区别：回调是"渠道推给我们"，这里是"我们主动去问"。
     * 两条路最终都走同一段结清逻辑，所以<b>不会出现两套状态流转</b>。</p>
     *
     * @return true = 本次查询把状态推进到了终态
     */
    boolean queryAndSync(String refundOrderNo);

    /** 分页捞"退款中"的单子（补偿任务用；配合 XXL-JOB 分片广播，每片取自己那一页） */
    List<RefundOrder> listRunningPage(int pageNo, int size);
}
