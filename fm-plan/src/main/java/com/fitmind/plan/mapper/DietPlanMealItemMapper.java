package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.DietPlanMealItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 饮食计划"一餐里的食物" Mapper。
 */
@Mapper
public interface DietPlanMealItemMapper extends BaseMapper<DietPlanMealItem> {
}
