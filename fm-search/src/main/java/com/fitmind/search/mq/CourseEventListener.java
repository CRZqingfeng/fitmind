package com.fitmind.search.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.search.service.ICourseIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 课程上下架消息监听 —— 把 M2-2 发出的消息接住，同步 ES 索引。
 *
 * <p><b>队列由消费者声明</b>（{@code @QueueBinding}），生产者只声明交换机：
 * 谁消费谁决定队列怎么配（是否持久化、是否死信、预取多少）。
 * 生产方也声明队列的话，两边配置一旦不一致，启动就会报
 * {@code PRECONDITION_FAILED}（队列参数冲突），反而添乱。</p>
 *
 * <p><b>为什么不抛异常而是 catch 掉</b>：当前是自动 ack 且没有死信队列，
 * 抛异常会让消息被拒收后<b>无限重投</b>（服务起来一次刷一次屏）。
 * 索引同步是"最终一致"的场景——失败了最多是搜不到，代价远小于刷爆日志。
 * 死信队列留到 M4 交易模块一起做（那边对可靠性要求真正高）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseEventListener {

    private final ICourseIndexService courseIndexService;

    /**
     * 课程上架：拉课程数据 → 写索引。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.COURSE_UP_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.COURSE_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.COURSE_UP_KEY
    ))
    public void listenCourseUp(Long courseId) {
        try {
            log.info("收到课程上架消息：courseId={}", courseId);
            courseIndexService.saveCourseIndex(courseId);
        } catch (Exception e) {
            // 只记日志不重投：见类注释说明
            log.error("处理课程上架消息失败，该课程暂不可被搜索到。courseId={}，原因：{}",
                    courseId, e.getMessage(), e);
        }
    }

    /**
     * 课程下架：删索引。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.COURSE_DOWN_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.COURSE_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.COURSE_DOWN_KEY
    ))
    public void listenCourseDown(Long courseId) {
        try {
            log.info("收到课程下架消息：courseId={}", courseId);
            courseIndexService.deleteCourseIndex(courseId);
        } catch (Exception e) {
            log.error("处理课程下架消息失败，该课程可能仍可被搜索到。courseId={}，原因：{}",
                    courseId, e.getMessage(), e);
        }
    }

    /**
     * 课程过期：与下架同样处理（删索引）。
     *
     * <p>现在还没有服务往这个 key 发消息（M3 的报名有效期到了才用），
     * 先占好位置，避免到时候又要改一遍 MQ 配置。</p>
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.COURSE_EXPIRE_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.COURSE_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.COURSE_EXPIRE_KEY
    ))
    public void listenCourseExpire(Long courseId) {
        try {
            log.info("收到课程过期消息：courseId={}", courseId);
            courseIndexService.deleteCourseIndex(courseId);
        } catch (Exception e) {
            log.error("处理课程过期消息失败。courseId={}，原因：{}", courseId, e.getMessage(), e);
        }
    }
}
