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
 * 训练日里的动作项（模板）。
 *
 * <p><b>最容易写错的地方</b>：组数 / 次数 / 重量 / 组休**只能放在这一层**。
 * 一旦有人把它们搬到 exercise（"卧推默认 4 组"），
 * 用户把某个计划里的卧推改成 5 组时，全站所有计划里的卧推都会变。</p>
 *
 * <p>{@code weightKg} 单位是 <b>kg</b>；界面显示"斤"时 ×2，换算只在前端一个函数里做。</p>
 */
@Data
@Accessors(chain = true)
@TableName("plan_day_item")
public class PlanDayItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long planDayId;

    private Long exerciseId;

    /** 训练日内的顺序，从 1 开始 */
    private Integer sort;

    /** 组类型：0-正式 1-热身 2-拉伸 */
    private Integer groupType;

    private Integer sets;

    /** 每组次数（计时型为 0） */
    private Integer reps;

    /** 计时型单组秒数（平板/跳绳），次数型为 0 */
    private Integer durationSec;

    /** 建议重量 kg，可为 null（徒手 / 用户还没填） */
    private BigDecimal weightKg;

    private Integer restSec;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
