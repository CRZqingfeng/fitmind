package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 体测完成事件（fm-exam 生产 → fm-data 消费）。
 *
 * <p>放在 fm-common 的理由与其它消息体一样：生产方与消费方是<b>不同的服务</b>，
 * 消息体只能待在双方都依赖的公共模块。</p>
 *
 * <p><b>为什么发这个事件</b>：数据中心要统计"今日新增体测"，
 * 而它不应该去直连 fm_exam 库（那是违反微服务数据边界的）。
 * 业务侧发事件、数据中心订阅 —— 加一个统计维度时，
 * <b>不需要回头改体测服务</b>。</p>
 *
 * <p>⚠️ 消费侧必须自己做幂等：MQ 是 at-least-once，
 * 同一条事件可能投递多次，直接计数会多算（fm-data 用唯一索引去重）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class AssessmentCreatedMessage {

    /** ★ 幂等键：体测记录id（同一条体测只应被统计一次） */
    private Long assessmentId;

    private Long userId;

    /** 综合评分（数据中心可以据此统计平均分） */
    private Integer score;

    /** 体测时间（按它归到"哪一天"，而不是按消息到达时间 —— 支持补录） */
    private LocalDateTime assessTime;
}
