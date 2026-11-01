package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.FoodFavorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食物收藏 Mapper。
 *
 * <p>取消收藏用 {@code delete(wrapper.eq(userId).eq(foodId))}，不需要自己写 SQL。</p>
 */
@Mapper
public interface FoodFavoriteMapper extends BaseMapper<FoodFavorite> {
}
