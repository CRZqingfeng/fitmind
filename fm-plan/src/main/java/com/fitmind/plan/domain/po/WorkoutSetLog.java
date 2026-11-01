package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 每一组的完成记录（日志，事实）。
 *
 * <p><b>最容易写错的地方</b>：这一层记录的是"我今天实际推了几个"，
 * 所以 {@code exerciseId} 要冗余一份 —— 前端按动作统计容量时不用再 join plan_day_item；
 * 而且**即使以后计划被删了，这条历史记录还看得懂**。</p>
 *
 * <p>重复提交同一组（同一 session + item + setNo）被 {@code uk_session_item_set} 挡住，
 * 走 {@code ON DUPLICATE KEY UPDATE} 覆盖 —— 天然幂等，不写 if。</p>
 */
@Data
@Accessors(chain = true)
@TableName("workout_set_log")
public class WorkoutSetLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long itemId;

    private Long exerciseId;

    /** 第几组，从 1 开始 */
    private Integer setNo;

    private BigDecimal weightKg;

    private Integer reps;

    /** 计时型本组时长（秒） */
    private Integer durationSec;

    /** 0-否 1-是 */
    private Integer done;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
