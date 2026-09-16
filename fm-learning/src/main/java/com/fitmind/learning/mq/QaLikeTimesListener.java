package com.fitmind.learning.mq;

import com.fitmind.api.dto.remark.LikedTimesDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.learning.domain.po.InteractionReply;
import com.fitmind.learning.service.IInteractionReplyService;
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
 * 问答回答的点赞数同步：把 fm-remark 推来的最新计数刷进 {@code interaction_reply.liked_times}。
 *
 * <p>为什么要冗余这个字段而不是每次去 count：问题/回答列表要按点赞数排序，
 * 跨服务实时统计排不了序 —— 用「冗余字段 + 异步同步」换列表页的查询性能。</p>
 *
 * <p><b>覆盖更新</b>（{@code set liked_times = N}）：所以这条消息重复投递是无害的，
 * 不需要像积分那样做唯一索引幂等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaLikeTimesListener {

    private final IInteractionReplyService replyService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.QA_LIKED_TIMES_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LIKE_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.QA_LIKED_TIMES_KEY
    ))
    public void listenReplyLikedTimesChange(List<LikedTimesDTO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<InteractionReply> updates = new ArrayList<>(list.size());
        for (LikedTimesDTO dto : list) {
            updates.add(new InteractionReply()
                    .setId(dto.getBizId())
                    .setLikedTimes(dto.getLikedTimes()));
        }
        // 一条消息带一批（默认 30 条），一次批量刷完
        replyService.updateBatchById(updates);
        log.debug("已同步 {} 条回答的点赞数", updates.size());
    }
}
