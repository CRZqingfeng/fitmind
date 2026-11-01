package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 我加入 / 正在执行的计划（实例）。
 *
 * <p><b>最容易写错的地方</b>：重复点「执行本计划」**不能插新行**，而是复用
 * {@code uk_user_plan(user_id, plan_id)} 命中的那一行并重置 start_date。
 * 所以写入必须走 {@code INSERT … ON DUPLICATE KEY UPDATE}（见 UserPlanMapper），
 * 不要写成"先 select，没有就 insert"——中间有并发窗口，连点三次就可能进三条。</p>
 */
@Data
@Accessors(chain = true)
@TableName("user_plan")
public class UserPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long planId;

    /** 开始日期（重新执行时重置为今天） */
    private LocalDate startDate;

    /** 状态：0-进行中 1-已完成 2-已放弃 */
    private Integer status;

    /** 当前练到第几练 */
    private Integer currentDayIndex;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
