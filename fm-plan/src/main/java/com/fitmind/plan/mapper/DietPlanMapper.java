package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.DietPlan;
import org.apache.ibatis.annotations.Mapper;

/**
 * 饮食计划 Mapper。
 *
 * <p>全部查询用 QueryWrapper 拼（{@code source/status} 有索引），没有需要手写 SQL 的场景。</p>
 */
@Mapper
public interface DietPlanMapper extends BaseMapper<DietPlan> {
}
