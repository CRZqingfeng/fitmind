package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 订单退款到账（fm-trade 生产 → fm-learning / fm-message 消费）。
 *
 * <p><b>★ 一条业务事件，两个消费者</b>（topic 交换机上一个 routing key 绑两个队列）：</p>
 * <ul>
 *   <li><b>fm-learning</b>：撤销课表 —— 钱退了，课程权益就不该留着。
 *       与 {@link OrderPayMessage} 的"写课表"正好相反，两者共用同一套
 *       {@code learning_lesson} 幂等约束。</li>
 *   <li><b>fm-message</b>：给用户发"退款到账"站内信 / 短信。</li>
 * </ul>
 *
 * <p><b>为什么不直接让交易服务同步调这两个服务</b>：那样每加一个下游
 * （比如将来 fm-data 要统计退款率、fm-aigc 要根据退款调整训练建议）
 * 都要回来改交易服务。发事件则生产方零改动。</p>
 *
 * <p><b>幂等</b>：learning 侧靠"删除不存在的记录返回 0 行"天然幂等；
 * message 侧靠 {@code user_inbox} 的 {@code (user_id, source_type, source_id)} 唯一索引
 * （{@code source_id} 用本消息的 {@code refundApplyId}）。</p>
 *
 * <p>注意 {@code courseName} 是<b>订单明细里的快照</b>，不是课程表的当前值 ——
 * 通知文案要说"《XX》的退款已到账"，而课程可能已经被改名甚至删除。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class OrderRefundMessage {

    /** 订单id */
    private Long orderId;

    /** ★ 退款申请id —— 消费端做幂等用的业务主键 */
    private Long refundApplyId;

    /** 退款用户（★ MQ 消费线程里没有登录上下文，用户必须由消息指定） */
    private Long userId;

    /** 退款金额（分） */
    private Integer refundAmount;

    /** 被撤销权益的课程（按明细退款，所以通常只有一个元素） */
    private List<Long> courseIds;

    /** 课程名（订单快照，给通知文案用） */
    private String courseName;
}
