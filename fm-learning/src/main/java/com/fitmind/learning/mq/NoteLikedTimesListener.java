package com.fitmind.learning.mq;

import com.fitmind.api.dto.remark.LikedTimesDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.learning.domain.po.Note;
import com.fitmind.learning.service.INoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔记点赞数同步：把 fm-remark 推来的最新计数刷进 {@code note.liked_times}。
 *
 * <p>⚠️ <b>这条链路在常见实现里是断的</b>：{@code 互动服务} 的
 * {@code BIZ_TYPES} 声明了 {@code "NOTE"}、{@code MqConstants.NOTE_LIKED_TIMES_KEY}
 * 也定义了，但 {@code note} 表<b>没有 liked_times 字段</b>，也<b>没有任何消费者</b> ——
 * 编译不报错、启动不报错，功能静默不存在。本阶段把它接通。</p>
 *
 * <p><b>覆盖更新</b>（{@code set liked_times = N}）：消息重复投递无害，
 * 不需要像积分那样做唯一索引幂等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteLikedTimesListener {

    private final INoteService noteService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.NOTE_LIKED_TIMES_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LIKE_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.NOTE_LIKED_TIMES_KEY
    ))
    public void listenNoteLikedTimesChange(List<LikedTimesDTO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Note> updates = new ArrayList<>(list.size());
        for (LikedTimesDTO dto : list) {
            updates.add(new Note()
                    .setId(dto.getBizId())
                    .setLikedTimes(dto.getLikedTimes()));
        }
        noteService.updateBatchById(updates);
        log.debug("已同步 {} 条笔记的点赞数", updates.size());
    }
}
