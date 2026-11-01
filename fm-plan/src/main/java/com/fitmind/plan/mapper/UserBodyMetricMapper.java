package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.UserBodyMetric;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 体征 Mapper。
 */
@Mapper
public interface UserBodyMetricMapper extends BaseMapper<UserBodyMetric> {

    /**
     * 记录体征（同一天只留一行）。
     *
     * <p><b>为什么是 upsert 而不是纯 insert</b>：唯一索引是
     * {@code uk_body_user_date(user_id, record_date)}，同一天改第二次必然撞键。
     * 撞键时应该**更新当天那一行**（用户上午称 65、晚上称 64.6，只该留一个数），
     * 而不是报 500。</p>
     *
     * <p>★ 跨天仍然是插新行（体重是时序）—— 这件事由唯一索引自然实现：
     * record_date 不同就是不同的行。</p>
     */
    @Insert("INSERT INTO user_body_metric (user_id, record_date, height_cm, weight_kg, target_weight_kg, "
            + "target_date, bmi, kcal_budget, protein_g, carb_g, fat_g) "
            + "VALUES (#{m.userId}, #{m.recordDate}, #{m.heightCm}, #{m.weightKg}, #{m.targetWeightKg}, "
            + "#{m.targetDate}, #{m.bmi}, #{m.kcalBudget}, #{m.proteinG}, #{m.carbG}, #{m.fatG}) AS new "
            + "ON DUPLICATE KEY UPDATE "
            + "height_cm = new.height_cm, weight_kg = new.weight_kg, "
            + "target_weight_kg = new.target_weight_kg, target_date = new.target_date, "
            + "bmi = new.bmi, kcal_budget = new.kcal_budget, "
            + "protein_g = new.protein_g, carb_g = new.carb_g, fat_g = new.fat_g")
    int upsert(@Param("m") UserBodyMetric metric);
}
