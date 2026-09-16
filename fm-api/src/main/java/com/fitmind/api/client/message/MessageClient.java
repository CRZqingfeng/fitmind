package com.fitmind.api.client.message;

import com.fitmind.api.client.message.fallback.MessageClientFallback;
import com.fitmind.api.dto.message.NoticeSendDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 消息服务 Feign 客户端。
 *
 * <p><b>★ 用法上的取舍：能用 MQ 就别用它。</b></p>
 *
 * <p>本项目的通知主链路走 <b>MQ 事件</b>（支付成功、退款到账都是订阅方自己收），
 * 原因是同步调用有三个问题：① 交易侧要多等一次网络往返；
 * ② 消息服务一慢，支付回调就跟着慢；③ 无法重放、无法多消费者。</p>
 *
 * <p>那什么时候该用它 —— <b>当"通知"是这次操作的直接目的、而不是副作用时</b>。
 * 典型例子：教练给学员的动作考核评了分，学员应该收到"你的考核结果出来了"。
 * 这件事：</p>
 * <ul>
 *   <li>没有对应的业务事件可订阅（"评分完成"不是一笔交易）</li>
 *   <li>低频（一次评分一次调用，不是高并发路径）</li>
 *   <li>而且是教练主动发起的操作，等一下回执也合理</li>
 * </ul>
 *
 * <p>一句话：<b>业务副作用走 MQ，用户可感知的动作可以直接调。</b></p>
 */
@FeignClient(value = "fm-message", fallbackFactory = MessageClientFallback.class)
public interface MessageClient {

    /**
     * 按模板给指定用户发一条站内信（可选带短信）。
     *
     * <p>幂等：{@code sourceId} 会进 {@code user_inbox} 的唯一索引，
     * 重复调用不会重复通知。</p>
     */
    @PostMapping("/internal/messages/send")
    void sendByTemplate(@RequestBody NoticeSendDTO dto);
}
