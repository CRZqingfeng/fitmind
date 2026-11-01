package com.fitmind.aigc.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 提交订单请求（fm-trade {@code POST /orders} 的请求体）。
 *
 * <p>★ <b>为什么 AI 侧要"自己再声明一遍"这个 DTO</b>（而不是复用
 * {@code fm-trade.domain.dto.PlaceOrderDTO}）：</p>
 * <ol>
 *   <li><b>依赖方向</b>。fm-aigc 只依赖 fm-api / fm-common，<b>不依赖 fm-trade 的实现包</b>
 *       —— 否则一个 AI 模块会把交易模块的 DTO、常量、枚举全拖进类路径。
 *       消费方声明的代价是"字段名靠 JSON 契约对齐"，收益是"服务方改内部结构不会连坐"。</li>
 *   <li>与 {@link PrePlaceOrderDTO} 同一套路：<b>只声明真正用到的字段</b>，
 *       服务方新增字段不会影响这里（反序列化默认忽略未知字段）。</li>
 * </ol>
 *
 * <p>★★ <b>{@code orderId} 是"AI 下单幂等"的全部秘密</b>：
 * 它由 {@code GET /orders/prePlace} 生成（雪花 id），提交时<b>原样带回来</b>。
 * 于是"AI 重复下单"这件事退化成一次<b>主键冲突</b> ——
 * 第二个请求会在 {@code doPlaceOrder} 里读到已存在的订单并直接返回它，
 * <b>不会产生第二笔单</b>。</p>
 *
 * <p>⚠️ <b>没有 {@code 价格} 字段，而且不能加</b>：金额一律由 fm-trade 从库里算。
 * 这是 M4-1「金额不由前端给」原则的延续 —— 只不过现在多了一个更不可信的"前端"：
 * 大模型。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaceOrderRequest {

    /** 订单id：由 prePlace 生成、原样带回 —— 这是幂等的依据 */
    private Long orderId;

    /** 要购买的课程id列表（AI 只传 id，不传价） */
    private List<Long> courseIds;

    /**
     * 使用的用户券id。
     *
     * <p>★ AI 目前**恒不传**（不做"帮用户挑券"这件事）——
     * 券的叠加推荐是"涉及钱的决策"，交给用户在收银台自己做。
     * 字段留着是因为它是 fm-trade 契约的一部分，省略它反而会让
     * "为什么 AI 不用券"变得看不见。</p>
     */
    private List<Long> userCouponIds;
}
