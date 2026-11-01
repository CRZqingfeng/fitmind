package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 体征时序（日志）。
 *
 * <p><b>最容易写错的地方</b>：改体重是<b>插新行</b>，不是更新旧行。
 * 体重是时序数据 —— 三个月后回头看"我是怎么掉下来的"，全靠这些历史行。
 * 但**同一天**改两次必须落成同一行（唯一索引 {@code uk_body_user_date}），
 * 否则一天记三次体重会变成三行，趋势图会被打成一堆锯齿。</p>
 *
 * <p>★ {@code kcalBudget} 与三大营养素是**同一个东西的两种表述**，
 * 必须满足 {@code protein×4 + carb×4 + fat×9 = kcalBudget}（由 {@code NutritionCalculator} 保证）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("user_body_metric")
public class UserBodyMetric implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate recordDate;

    private BigDecimal heightCm;

    /** 体重（kg）★ 库里全是 kg，界面显示"斤"由前端 ×2 */
    private BigDecimal weightKg;

    private BigDecimal targetWeightKg;

    private LocalDate targetDate;

    private BigDecimal bmi;

    private Integer kcalBudget;

    private BigDecimal proteinG;

    private BigDecimal carbG;

    private BigDecimal fatG;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
