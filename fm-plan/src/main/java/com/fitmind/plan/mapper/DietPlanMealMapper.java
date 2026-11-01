package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.DietPlanMeal;
import org.apache.ibatis.annotations.Mapper;

/**
 * 饮食计划"一餐" Mapper。
 */
@Mapper
public interface DietPlanMealMapper extends BaseMapper<DietPlanMeal> {
}
