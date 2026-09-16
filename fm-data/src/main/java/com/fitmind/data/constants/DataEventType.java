package com.fitmind.data.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据中心订阅的事件类型。
 *
 * <p>它同时也是 {@code data_event_record.event_type} 的取值 ——
 * 唯一索引是 {@code (event_type, biz_id)}，所以<b>不同事件类型的 biz_id
 * 即使撞了也没关系</b>（订单 id=100 和体测 id=100 互不影响）。
 * 这一点很像 M3-3 点赞时给 Redis key 加 bizType 前缀的理由。</p>
 */
@Getter
@AllArgsConstructor
public enum DataEventType {

    /** 每日签到（生产方 fm-learning） */
    SIGN_IN("sign.in", "签到"),
    /** 完成一个训练小节（生产方 fm-learning） */
    SECTION_LEARNED("section.learned", "完成训练"),
    /** 答对一道动作小测（生产方 fm-course） */
    PRACTICE_ANSWERED("practice.answered", "小测答对"),
    /** 订单支付成功（生产方 fm-trade） */
    ORDER_PAY("order.pay", "成交"),
    /** 退款到账（生产方 fm-trade） */
    ORDER_REFUND("order.refund", "退款"),
    /** 完成一次体测（生产方 fm-exam） */
    ASSESSMENT("assessment.created", "体测"),
    ;

    /** 对应的 MQ routing key（与 {@code MqConstants.Key} 保持一致，这里冗余一份便于排查） */
    private final String routingKey;
    private final String desc;
}
