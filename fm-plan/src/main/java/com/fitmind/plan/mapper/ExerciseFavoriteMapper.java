package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.ExerciseFavorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 动作收藏 Mapper（P0 只建表，接口留 P1）。
 */
@Mapper
public interface ExerciseFavoriteMapper extends BaseMapper<ExerciseFavorite> {
}
