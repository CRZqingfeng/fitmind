package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 训练日（模板）—— "周一练胸"这一层。
 *
 * <p><b>最容易写错的地方</b>：{@code dayIndex} 和 {@code sort} 不是一回事。
 * dayIndex = "第几练"（同一计划内唯一，决定 current_day_index 往后挪到哪），
 * sort = 展示顺序。P0 两者同值，但**不要合成一个字段**：以后要做
 * "把第 5 练拖到第 1 位显示"时，合并过的字段就没法表达了。</p>
 */
@Data
@Accessors(chain = true)
@TableName("plan_day")
public class PlanDay implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long planId;

    /** 第几练，从 1 开始 */
    private Integer dayIndex;

    /**
     * 星期几：1~7（周一=1），null = 不绑定星期。
     * ★「今日训练」只认这个字段；为 null 的训练日永远显示"今天休息"。
     */
    private Integer weekday;

    /** 训练日名，默认"未命名训练日" */
    private String name;

    private Integer sort;

    /** 预计分钟（后端按 Σ组数×单组秒 + Σ组休 算，前端不许自己再算一份） */
    private Integer estMinutes;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
