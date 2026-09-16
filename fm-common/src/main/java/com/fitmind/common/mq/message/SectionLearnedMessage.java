package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 完成训练动作事件。
 *
 * <p>比常见实现多带 {@code lessonId}/{@code sectionId}：积分侧要用它拼
 * {@code bizId = lessonId:sectionId} 做<b>幂等</b>——
 * MQ 是 at-least-once，重复投递不能让用户重复得分。</p>
 *
 * <p>常见实现这个事件只有消费者没有生产者（链路是断的），且消息体只有 userId，
 * 根本没法做幂等。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class SectionLearnedMessage {

    private Long userId;

    private Long lessonId;

    private Long sectionId;

    /** 幂等业务标识：{@code lessonId:sectionId} */
    public String bizId() {
        return lessonId + ":" + sectionId;
    }
}
