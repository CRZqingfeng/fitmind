package com.fitmind.aigc.mq;

import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.AiChatMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把一轮对话的指标发布给数据中心（M5）。
 *
 * <p>★★ <b>为什么走 MQ 而不是同步调接口</b>：</p>
 * <ol>
 *   <li><b>统计不是用户要的东西</b>，不该让对话等它 ——
 *       同步调用会把数据中心的一次抖动变成用户多等 100ms；</li>
 *   <li>更关键的是<b>可用性方向</b>：数据中心挂了，AI 对话不该跟着挂。
 *       异步发送 + 失败只记日志，把"统计"变成对话链路上<b>最软的一环</b>；</li>
 *   <li>一个 routing key 可以绑多个队列 —— 以后要加"AI 成本告警"这类消费者，
 *       不需要回头改这里（与 M4-4 的站内信同一个设计）。</li>
 * </ol>
 *
 * <p>★ 用 {@code sendAsync} 而不是 {@code send}：发送要走一次网络往返，
 * 而这一轮对话的收尾（落库、发 STOP）已经做完了 —— 没有必要为了统计
 * 让响应线程再等一次 RTT。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatPublisher {

    private final RabbitMqHelper rabbitMqHelper;
    private final AigcProperties props;

    /**
     * 发布一条对话指标。
     *
     * <p>⚠️ <b>这个方法永远不会抛异常</b>：它的返回值是 void，
     * 而且内部吞掉所有失败 —— 调用点不需要 try-catch，
     * 也就不会有人"忘了处理统计失败"。统计失败的正确处置就是记一条日志。</p>
     */
    public void publish(AiChatMessage message) {
        if (!props.getObserve().isPublishEvent() || message == null) {
            return;
        }
        try {
            rabbitMqHelper.sendAsync(MqConstants.Exchange.AI_EXCHANGE,
                    MqConstants.Key.AI_CHAT, message);
            log.debug("AI 指标已发布：requestId={}, tokens={}/{}, 首字 {}ms",
                    message.getRequestId(), message.getPromptTokens(),
                    message.getCompletionTokens(), message.getFirstTokenMs());
        } catch (Exception e) {
            // 统计失败不影响对话 —— 这里的取舍是"少一条统计"vs"用户看到报错"，
            // 两者严重程度差得很远
            log.warn("AI 指标发布失败（不影响对话）：requestId={}, 原因={}",
                    message.getRequestId(), e.getMessage());
        }
    }
}
