package com.fitmind.learning.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.learning.enums.PointsRecordType;
import com.fitmind.common.mq.message.PointsEventMessage;
import com.fitmind.common.mq.message.SectionLearnedMessage;
import com.fitmind.common.mq.message.SignInMessage;
import com.fitmind.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 积分事件监听：把各业务动作转成积分。
 *
 * <p>为什么要走 MQ 而不是同步加分：积分是"锦上添花"，
 * 不能因为积分写库慢/失败而拖慢签到、学习这些主流程。MQ 顺带削峰。</p>
 *
 * <p>队列由消费者声明（谁消费谁决定队列怎么配），生产者只声明交换机。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsMessageListener {

    /** 完成训练动作得分 */
    private static final int POINTS_PER_SECTION = 10;

    /** 发表回复得分 */
    private static final int POINTS_PER_REPLY = 5;

    /** 写笔记 / 笔记被收藏得分 */
    private static final int POINTS_PER_NOTE = 3;
    /** 答对一道动作小测得分 */
    private static final int POINTS_PER_PRACTICE = 2;

    private static final int POINTS_PER_NOTE_GATHERED = 2;

    private final IPointsRecordService pointsRecordService;

    /** 完成训练动作（M3-1 生产） */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.LEARNING_POINTS_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SECTION_LEARNED
    ))
    public void listenSectionLearned(SectionLearnedMessage message) {
        if (message == null || message.getUserId() == null) {
            log.warn("完成动作消息体有误，忽略：{}", message);
            return;
        }
        try {
            pointsRecordService.addPointsRecord(message.getUserId(), POINTS_PER_SECTION,
                    PointsRecordType.LEARNING, message.bizId());
        } catch (Exception e) {
            // 与 M2-3 同策略：只记日志不重投（当前没有死信队列，抛异常会无限重投刷屏）
            log.error("学习积分发放失败：{}，原因：{}", message, e.getMessage());
        }
    }

    /** 每日签到（M3-2 生产） */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.SIGN_POINTS_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SIGN_IN
    ))
    public void listenSignIn(SignInMessage message) {
        if (message == null || message.getUserId() == null) {
            log.warn("签到消息体有误，忽略：{}", message);
            return;
        }
        try {
            pointsRecordService.addPointsRecord(message.getUserId(), message.getPoints(),
                    PointsRecordType.SIGN, message.getBizId());
        } catch (Exception e) {
            log.error("签到积分发放失败：{}，原因：{}", message, e.getMessage());
        }
    }

    /** 发表问答回复（M3-3 生产） */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.QA_POINTS_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.WRITE_REPLY
    ))
    public void listenWriteReply(PointsEventMessage message) {
        grant(message, POINTS_PER_REPLY, PointsRecordType.QA, "问答回复");
    }

    /** 写笔记（M3-3 生产） */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.NOTE_POINTS_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.WRITE_NOTE
    ))
    public void listenWriteNote(PointsEventMessage message) {
        grant(message, POINTS_PER_NOTE, PointsRecordType.NOTE, "写笔记");
    }

    /** 笔记被收藏（M3-3 生产；与写笔记分开队列，语义与分值都不同） */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.NOTE_GATHERED_POINTS_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.NOTE_GATHERED
    ))
    public void listenNoteGathered(PointsEventMessage message) {
        grant(message, POINTS_PER_NOTE_GATHERED, PointsRecordType.NOTE, "笔记被收藏");
    }

    /**
     * 答对动作小测（M2-2c 生产，生产方在 fm-course）。
     *
     * <p>为什么判分在 fm-course、加分在 fm-learning：题目数据在课程库，
     * 判分是纯计算不用跨服务；而积分明细和积分榜属于学习域。
     * 两边通过这条 MQ 解耦——课程侧不用知道积分的每日上限规则，
     * 学习侧也不用知道题目长什么样。</p>
     *
     * <p>幂等完全交给 {@code points_record} 的 {@code uk_user_type_biz} 唯一索引：
     * 学员重复提交同一道题，这里会收到重复消息，但明细只落一行。</p>
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.PRACTICE_POINTS_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.PRACTICE_ANSWERED
    ))
    public void listenPracticeAnswered(PointsEventMessage message) {
        grant(message, POINTS_PER_PRACTICE, PointsRecordType.PRACTICE, "动作小测");
    }

    private void grant(PointsEventMessage message, int points, PointsRecordType type, String scene) {
        if (message == null || message.getUserId() == null) {
            log.warn("{}消息体有误，忽略：{}", scene, message);
            return;
        }
        try {
            pointsRecordService.addPointsRecord(message.getUserId(), points, type, message.getBizId());
        } catch (Exception e) {
            log.error("{}积分发放失败：{}，原因：{}", scene, message, e.getMessage());
        }
    }
}
