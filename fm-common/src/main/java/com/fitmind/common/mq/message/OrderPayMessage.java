package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 订单已支付（fm-trade 生产 → fm-learning 消费）。
 *
 * <p>消费端据此把课程写进用户课表 —— 这就是"付费课报名"的落地动作。
 * 为什么不让用户直接调报名接口：报名是<b>支付成功的后果</b>，
 * 触发权应该在交易侧（否则改个请求就能免费拿到付费课）。</p>
 *
 * <p>幂等由 {@code learning_lesson} 的 {@code (user_id, course_id)} 唯一索引保证 ——
 * MQ 重复投递时插入会冲突，捕获异常即可，不会重复建课表。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class OrderPayMessage {

    /** 订单id */
    private Long orderId;

    /** 下单用户 */
    private Long userId;

    /** 本次购买的课程id */
    private List<Long> courseIds;
    /**
     * 实付金额（分）。
     *
     * <p><b>M5 补上的</b>：数据中心要统计成交额（GMV），但原来这条消息只带课程 id。
     * 加字段而不是让 fm-data 回头查订单 —— 那样数据中心就要知道订单表结构，
     * 又变成直连业务库了。</p>
     *
     * <p>对已有消费者（fm-learning 写课表、fm-message 发通知）无影响：
     * 多一个字段只是多一段 JSON，它们不读就不关心。</p>
     */
    private Integer amount;
}
