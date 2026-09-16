package com.fitmind.pay.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.pay.domain.dto.ApplyPayDTO;
import com.fitmind.pay.domain.po.PayOrder;
import com.fitmind.pay.domain.vo.PayResultVO;

/**
 * 支付单服务。
 */
public interface IPayOrderService extends IService<PayOrder> {

    /** 申请支付（创建支付单 + 调渠道拿支付链接） */
    PayResultVO applyPay(ApplyPayDTO dto);

    /** 按支付单号查（供回调与查询接口使用） */
    PayOrder getByPayOrderNo(String payOrderNo);

    /**
     * ★ 处理支付成功。
     *
     * <p>这是回调与 Mock 触发<b>共用的入口</b>，也是幂等的关键：
     * 只有"待支付 → 已支付"这次条件更新成功，才会发 MQ。</p>
     *
     * @return true = 这次调用真的把状态改成了已支付（第一次）；
     *         false = 已经处理过了，没做任何事
     */
    boolean handlePaySuccess(String payOrderNo);

    /** 关闭支付单（支付失败 / 用户取消） */
    boolean closePayOrder(String payOrderNo);
}
