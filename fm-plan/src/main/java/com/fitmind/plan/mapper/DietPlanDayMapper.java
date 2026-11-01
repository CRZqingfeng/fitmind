package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.DietPlanDay;
import org.apache.ibatis.annotations.Mapper;

/**
 * 饮食计划"第 N 天" Mapper。
 */
@Mapper
public interface DietPlanDayMapper extends BaseMapper<DietPlanDay> {
}
