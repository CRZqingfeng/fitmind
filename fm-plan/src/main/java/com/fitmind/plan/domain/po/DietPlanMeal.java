package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 一天里的一餐（模板）。
 *
 * <p><b>最容易写错的地方</b>：唯一键是 {@code (day, meal_type, meal_index)} 三列。
 * 少了 {@code mealIndex}，"每日六餐（早中晚 + 三次加餐）"只能存进去一条加餐，
 * 而参考 App 的增肌计划描述里明确写着"含早中晚三次加餐"——
 * 数据模型不支持，文案就成了假的。</p>
 */
@Data
@Accessors(chain = true)
@TableName("diet_plan_meal")
public class DietPlanMeal implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long dietPlanDayId;

    /** 1早 2午 3晚 4加餐 */
    private Integer mealType;

    /** 同餐次里第几顿（加餐 1/2/3） */
    private Integer mealIndex;

    /** 建议时间，如 07:30:00 */
    private LocalTime mealTime;

    /** 这一餐热量（冗余，真相在食物项） */
    private Integer kcal;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
