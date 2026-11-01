package com.fitmind.aigc.client;

import com.fitmind.aigc.client.dto.PlaceOrderRequest;
import com.fitmind.aigc.client.dto.PlaceOrderResultDTO;
import com.fitmind.aigc.client.dto.PrePlaceOrderDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 交易（fm-trade）—— AI 工具专用客户端。设计说明见 {@link SearchClient}。
 *
 * <p>★★ <b>这个接口是"AI 不能决定金额"这条原则的落点。</b>
 * 预下单只传课程 id，<b>不传价格</b> —— 金额全部由 fm-trade 从库里算
 * （沿用 M4-1 的"金额不由前端给"原则，现在多了一个更不可信的"前端"：大模型）。
 * 就算模型在对话里说"这门课 1 分钱"，它也没有把这个数字送进交易的通道。</p>
 *
 * <p>★★ <b>下单是"两段式"，这是本客户端存在两个方法的原因</b>
 * （对应 M6-8 #1 —— 这里曾经只声明了 {@code prePlace} 一个方法，是个真 bug）：</p>
 * <pre>
 *   GET  /orders/prePlace  → 只算价 + 生成订单 id，★ <b>不落库</b>
 *   POST /orders           → ★ <b>真正写 trade_order / trade_order_detail</b>
 * </pre>
 *
 * <p>⚠️ <b>务必记牢：{@code prePlace} 不落库。</b>
 * 它的返回值里那些"金额、课程清单"只是<b>预览</b>，
 * 用它去渲染"待支付订单卡片"会让用户点进支付页发现订单根本不存在。
 * 前端 {@code CheckoutView} 的正确写法就是 {@code prePlace → place → pay} 三段式，
 * <b>AI 走的是同一条路</b>（见 {@code OrderTools.prePlaceOrder}）。</p>
 *
 * <p>★ 为什么不让 fm-trade 提供一个"AI 专用下单接口"把两段合并：
 * <b>多一个写库入口 = 多一个安全面</b>。让 AI 复用人类走的接口，
 * 那么"金额不由调用方给、课程必须真实存在、不能重复购买"这些校验
 * <b>天然对 AI 也生效</b>，不需要在第二条通道上再写一遍。</p>
 */
@FeignClient(value = "fm-trade", path = "/orders")
public interface OrderClient {

    /**
     * 预下单：返回订单 id 与金额预览（★ <b>不落库</b>，金额来自库里、不由调用方提供）。
     *
     * <p>它唯一的副作用是生成一个雪花 id 给调用方带回去 ——
     * 那是下单接口做幂等用的钥匙。</p>
     */
    @GetMapping("/prePlace")
    PrePlaceOrderDTO prePlace(@RequestParam("courseIds") List<Long> courseIds);

    /**
     * 提交订单：★ <b>这一步才真的写库</b>（主单 + 明细 + 清购物车 + 投递超时关单消息）。
     *
     * <p>{@code orderId} 必须是 {@link #prePlace} 刚返回的那个 ——
     * 沿用同一个 id 意味着"重复调用"退化成"主键冲突"，
     * fm-trade 会直接返回那笔已存在的订单（{@code created=false}），
     * <b>而不是又造一笔</b>。</p>
     *
     * <p>⚠️ 业务失败（已购 / 有未支付旧单）时它<b>不会抛异常</b>，
     * 而是返回一个带 {@code code/msg} 的失败信封 —— 见 {@link PrePlaceOrderDTO} 的说明。</p>
     */
    @PostMapping
    PlaceOrderResultDTO place(@RequestBody PlaceOrderRequest request);
}
