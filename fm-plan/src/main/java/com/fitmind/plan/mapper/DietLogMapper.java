package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.DietLog;
import com.fitmind.plan.domain.vo.MacroSumVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 饮食记录 Mapper。
 */
@Mapper
public interface DietLogMapper extends BaseMapper<DietLog> {

    /**
     * 记一份食物（幂等合并）。
     *
     * <p>★★ <b>六列必须一起累加</b>（amount / gram / kcal / protein_g / carb_g / fat_g）。</p>
     *
     * <p>只写 {@code amount = diet_log.amount + new.amount} 是最典型的错法：
     * 用户看到"燕麦片 50g"变成"100g"，热量却还是 46 千卡（100g 的值），
     * 于是"还可以吃"少扣了 46 千卡 —— 而这个错在页面上**几乎看不出来**，
     * 只有对账时才会发现总数不对。</p>
     *
     * <p><b>为什么是"累加"而不是"按总克数 × 当前单价重算"</b>：</p>
     *
     * <p>先说实话 —— 累加**确实有舍入漂移**（实测：燕麦 50g 蛋白 5.05 先存成 5.1，
     * 再记一份合并成 10.2，而按 100g 一次算是 10.1）。
     * 早先这里写的是"累加不漂移、重算才漂移"，那句话是反的：
     * 逐份舍入再相加，误差会累加；按总量算只会舍入一次。</p>
     *
     * <p><b>那为什么仍然选累加</b>：因为 {@code diet_log} 是<b>快照表</b>。
     * 如果合并时按"当前单价 × 总克数"重算，那么运营把食物热量改掉的当天，
     * 早先那几份的历史值就会被一起改写 —— 这与"历史记录不跟食物库变"直接冲突。
     * 累加保住了每一份"记的时候是什么价"，代价是宏观营养素最多每天漂 0.1g 量级。</p>
     *
     * <p>★ 取舍是明确的：<b>快照语义 &gt; 0.1g 精度</b>。
     * 要精确总量时看 {@code amount/gram}（这两列是精确的），不要用宏观营养素反推热量。</p>
     */
    @Insert("INSERT INTO diet_log (user_id, log_date, meal_type, food_id, food_name, "
            + "amount, unit, gram, kcal, protein_g, carb_g, fat_g) "
            + "VALUES (#{log.userId}, #{log.logDate}, #{log.mealType}, #{log.foodId}, #{log.foodName}, "
            + "#{log.amount}, #{log.unit}, #{log.gram}, #{log.kcal}, "
            + "#{log.proteinG}, #{log.carbG}, #{log.fatG}) AS new "
            + "ON DUPLICATE KEY UPDATE "
            + "amount = diet_log.amount + new.amount, "
            + "gram = diet_log.gram + new.gram, "
            + "kcal = diet_log.kcal + new.kcal, "
            + "protein_g = diet_log.protein_g + new.protein_g, "
            + "carb_g = diet_log.carb_g + new.carb_g, "
            + "fat_g = diet_log.fat_g + new.fat_g")
    int upsertMerge(@Param("log") DietLog log);

    /** 某天的摄入合计（SUM 交给数据库，不把记录全查回来） */
    @Select("SELECT COALESCE(SUM(kcal),0) AS kcal, COALESCE(SUM(protein_g),0) AS protein, "
            + "COALESCE(SUM(carb_g),0) AS carb, COALESCE(SUM(fat_g),0) AS fat "
            + "FROM diet_log WHERE user_id = #{userId} AND log_date = #{date}")
    MacroSumVO sumOfDate(@Param("userId") Long userId, @Param("date") LocalDate date);
}
