package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 签到成功事件（异步加分）。
 *
 * <p>签到时只写 Redis 位图 + 发消息，加分交给积分模块异步做——
 * 签到主流程不因积分写库而变慢，这也是"M"消息削峰"的小场景。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class SignInMessage {

    private Long userId;

    /** 本次应得积分（基础分 + 连续奖励） */
    private Integer points;

    /** 幂等业务标识：yyyyMMdd */
    private String bizId;
}
