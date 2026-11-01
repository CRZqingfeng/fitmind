package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.Food;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食物库 Mapper。
 *
 * <p>筛选条件（category / owner_id）都有索引；{@code units_json} 是展示用，不参与筛选。</p>
 */
@Mapper
public interface FoodMapper extends BaseMapper<Food> {
}
