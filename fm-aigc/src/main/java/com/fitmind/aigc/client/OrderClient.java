package com.fitmind.aigc.client;

import com.fitmind.aigc.client.dto.PrePlaceOrderDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
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
 * <p>⚠️ 注意 {@code prePlace} <b>会在 fm-trade 里真的落一条待支付订单</b>
 * （这是 M4-1 的设计：先拿订单 id 与金额预览，用户确认后再提交）。
 * 所以工具的职责是"生成待确认卡片"，<b>不能替用户完成支付</b> ——
 * 支付那一步仍然要用户自己点。超时未支付会被 M4-1 的定时任务自动关闭，
 * 不会留下垃圾数据。</p>
 */
@FeignClient(value = "fm-trade", path = "/orders")
public interface OrderClient {

    /** 预下单：返回订单 id 与金额预览（金额来自库里，不由调用方提供） */
    @GetMapping("/prePlace")
    PrePlaceOrderDTO prePlace(@RequestParam("courseIds") List<Long> courseIds);
}
