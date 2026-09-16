package com.fitmind.message;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 消息服务启动类。
 *
 * <p><b>★ 本模块是从零补全的</b>（需求文档：常见实现的 消息服务 是"空壳"）。</p>
 *
 * <p>它做两件事，设计要点分别在各自的包里有详细说明：</p>
 * <ol>
 *   <li><b>站内信</b>（{@code service/impl/*ServiceImpl}）<br>
 *       分两张表：{@code public_notice} 是"公告箱"，全员公告只写一行，
 *       用户来查时才展开到他自己的信箱（<b>pull 模式</b>）——
 *       给 10 万人发一条公告不该写 10 万行；{@code user_inbox} 是"用户信箱"，
 *       定向通知（购课成功、退款到账）直接写。<br>
 *       ★ 用户信箱上加了 {@code (user_id, source_type, source_id)} 唯一索引 ——
 *       常见实现这张表<b>一个唯一索引都没有</b>，MQ 重投就会重复通知。</li>
 *   <li><b>通知的触发方式</b>（{@code mq/*Listener}）<br>
 *       交易侧只发业务事件（{@code order.pay} / {@code order.refund}），
 *       本模块作为消费者订阅。<b>不走同步 Feign</b> —— 否则消息服务一慢，
 *       用户的支付回调就失败了。</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class MessageApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessageApplication.class, args);
    }
}
