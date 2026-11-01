package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.Exercise;
import org.apache.ibatis.annotations.Mapper;

/**
 * 动作库 Mapper。
 *
 * <p>分页筛选全部用 MyBatis-Plus 的 QueryWrapper 拼（category / body_part / equipment 都有索引），
 * 没有需要手写 SQL 的场景，因此本模块<b>没有</b> mapper/*.xml。</p>
 */
@Mapper
public interface ExerciseMapper extends BaseMapper<Exercise> {
}
