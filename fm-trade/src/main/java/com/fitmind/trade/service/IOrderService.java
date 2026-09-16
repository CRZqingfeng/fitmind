package com.fitmind.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.trade.constants.OrderStatus;
import com.fitmind.trade.domain.dto.PlaceOrderDTO;
import com.fitmind.trade.domain.po.TradeOrder;
import com.fitmind.trade.domain.query.OrderPageQuery;
import com.fitmind.trade.domain.vo.OrderVO;
import com.fitmind.trade.domain.vo.PayUrlVO;
import com.fitmind.trade.domain.vo.PlaceOrderResultVO;
import com.fitmind.trade.domain.vo.PrePlaceOrderVO;

import java.util.List;

/**
 * 订单服务。
 */
public interface IOrderService extends IService<TradeOrder> {

    /**
     * 预下单：生成订单 id + 金额预览，<b>不落库</b>。
     *
     * <p>前端拿到 {@code orderId} 后带着它提交订单 —— 这样下单接口天然幂等。</p>
     */
    PrePlaceOrderVO prePlaceOrder(List<Long> courseIds);

    /** 下单（同一用户串行、订单 id 幂等、金额回源） */
    PlaceOrderResultVO placeOrder(PlaceOrderDTO dto);

    /** 我的订单分页 */
    PageDTO<OrderVO> queryMyOrders(OrderPageQuery query);

    /** 订单详情（只能看自己的） */
    OrderVO queryOrderDetail(Long orderId);

    /** 用户主动取消订单 */
    void cancelOrder(Long orderId);

    /**
     * 为待支付订单发起支付（调 fm-pay 申请支付单）。
     *
     * <p><b>★ 金额由服务端从订单里取</b>（{@code realAmount}），不接前端传值 ——
     * 否则改个请求体就能用 1 分钱买下 199 的课。</p>
     */
    PayUrlVO applyPay(Long orderId, String payChannelCode);

    /**
     * ★ 统一的状态变更入口。
     *
     * <p>做两件事，缺一不可：</p>
     * <ol>
     *   <li>状态机校验（{@link OrderStatus#checkTransfer}）—— 防<b>写错代码</b>，
     *       比如从"已关闭"改到"已支付"</li>
     *   <li>条件更新（{@code WHERE status = expect}）—— 防<b>并发</b>，
     *       同时让重复调用天然幂等</li>
     * </ol>
     *
     * @param update 要写入的字段（至少要有 id 和目标 status；payTime/closeTime/message 可选）
     * @param expect 调用方<b>预期</b>的当前状态
     * @return true = 本次调用是"第一个"改成功的；false = 状态已不是预期，什么都没做
     */
    boolean changeStatus(TradeOrder update, OrderStatus expect);

    /** 超时关闭（供定时任务调用）。返回 true 表示确实是由这次调用关掉的 */
    boolean closeTimeoutOrder(Long orderId);

    /**
     * ★ 按明细聚合父单状态（M4-4 退款流程专用）。
     *
     * <p><b>为什么需要它</b>：退款是按<b>明细</b>做的（一笔订单可能买多门课），
     * 而订单状态是<b>整单级</b>的。于是每次明细状态变化后，都要重新算一遍
     * "这笔订单整体上处于什么状态"：</p>
     * <ul>
     *   <li>所有明细都已退款 → {@code REFUND_SUCCESS}</li>
     *   <li>还有明细在退款中 → {@code REFUNDED}（退款中）</li>
     *   <li>否则（退款被拒 / 失败 / 撤销）→ 回到 {@code PAYED}</li>
     * </ul>
     *
     * <p>把这段计算收在<b>一个方法</b>里，而不是散在"申请/审批/撤销/回调"
     * 四处各写一遍 —— 常见实现就是每个入口各写一遍，于是同一笔订单在不同路径下
     * 算出来的父单状态不一致。</p>
     */
    void syncRefundStatus(Long orderId);
}
