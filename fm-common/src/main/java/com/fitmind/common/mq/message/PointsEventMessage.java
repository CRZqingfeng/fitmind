package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用积分事件（问答、笔记、动作小测等场景共用）。
 *
 * <p>携带 {@code bizId}（业务唯一标识）是<b>幂等</b>的前提：
 * 积分侧靠 {@code (user_id, type, bizId)} 唯一索引挡住重复投递——
 * MQ 是 at-least-once，重复消费不能让用户重复得分。</p>
 *
 * <p><b>★ 为什么这个类放在 fm-common 而不是消费方 fm-learning</b>：
 * M2-2c 起，生产方变成了 fm-course（答题判分在 course 侧完成）。
 * 如果消息体留在 fm-learning 的包里，fm-course 就得<b>反向依赖消费方</b>才能构造它——
 * 依赖方向和消息流向正好相反。跨模块共用的消息体，位置只有一个正确答案：
 * 放在双方都依赖的公共模块。</p>
 *
 * <p>（只在单个服务内部流转的消息体——比如 {@code SectionLearnedMessage}、
 * {@code SignInMessage}——继续留在各自的 {@code mq.message} 包里。）</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class PointsEventMessage {

    private Long userId;

    /** 业务唯一标识：如笔记id、回复id、{@code courseId:cataId:subjectId} */
    private String bizId;
}
