package com.fitmind.data.service;

import com.fitmind.data.constants.DataEventType;
import com.fitmind.data.constants.DataMetric;

import java.time.LocalDate;

/**
 * 数据采集服务。
 *
 * <p><b>职责边界：只做"把一次事件记下来"，不做任何业务判断。</b>
 * 每个 MQ 消费者把消息拆成「事件类型 + 业务 id + 日期」丢给它，
 * 剩下的（去重、累加、落库）都在这里。</p>
 */
public interface IDataCollectService {

    /**
     * 记录一次事件并累加对应指标。
     *
     * <p>★ 这是<b>幂等</b>的：同一 {@code (eventType, bizId)} 只会真正计数一次。</p>
     *
     * @param eventType 事件类型
     * @param bizId     业务唯一标识
     * @param userId    关联用户（可为空）
     * @param date      归属日期（业务发生时间，不是现在）
     * @param metrics   要累加的指标（可多个，如"成交"要同时加订单数和金额）
     * @return true 表示首次处理（真正计数了）；false 表示重复投递，已忽略
     */
    boolean collect(DataEventType eventType, String bizId, Long userId, LocalDate date,
                    MetricDelta... metrics);

    /**
     * 累加课程维度（销量 / 销售额 / 退款数）。
     *
     * @param courseId 课程id
     * @param date     归属日期
     * @param count    销量增量
     * @param amount   销售额增量（分）
     * @param refund   退款笔数增量
     */
    void collectCourse(Long courseId, LocalDate date, int count, long amount, int refund);

    /**
     * 指标增量。
     *
     * <p>为什么是"增量"而不是"值"：累加语义下，同一次事件可能要加多个指标
     * （成交 = 订单数 +1 且金额 +N），用增量对象表达最自然。</p>
     */
    record MetricDelta(DataMetric metric, long delta) {

        public static MetricDelta of(DataMetric metric, long delta) {
            return new MetricDelta(metric, delta);
        }

        /** 计数类指标：+1 */
        public static MetricDelta one(DataMetric metric) {
            return new MetricDelta(metric, 1);
        }
    }
}
