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
 * 饮食计划的第 N 天（模板）。
 *
 * <p>{@code kcalTotal / proteinG / carbG / fatG} 都是**冗余**：真相在各餐的食物项那一层，
 * 这里是导入/编辑时同一次事务里汇总出来的。冗余的理由是详情页要"第1天~第5天"横滑，
 * 每一天都现算一遍汇总会让翻页变慢；代价是改食物库后要重跑汇总。</p>
 */
@Data
@Accessors(chain = true)
@TableName("diet_plan_day")
public class DietPlanDay implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long dietPlanId;

    private Integer dayIndex;

    private Integer kcalTotal;

    private BigDecimal proteinG;

    private BigDecimal carbG;

    private BigDecimal fatG;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
