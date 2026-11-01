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
 * 一餐里的一份食物（模板）—— <b>热量真相在这一层</b>。
 *
 * <p><b>最容易写错的地方</b>：{@code kcal} 是导入时的**快照**，不是展示口径。
 * 模板允许"跟着食物库更新"，所以运营改了 {@code food.kcal_per_100g} 之后，
 * 这一列会过期。<b>详情页展示时按 {@code food 现价 × amount} 实时算</b>，
 * 这一列只用于对账（出错时能一眼看出是食物库改了，还是导入算法错了）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("diet_plan_meal_item")
public class DietPlanMealItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mealId;

    private Long foodId;

    /** 横滑卡片顺序 */
    private Integer sort;

    private BigDecimal amount;

    private String unit;

    /** 导入时的热量快照（不用于展示） */
    private Integer kcal;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
