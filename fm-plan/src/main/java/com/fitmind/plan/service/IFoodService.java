package com.fitmind.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.plan.domain.dto.FoodCreateDTO;
import com.fitmind.plan.domain.po.Food;
import com.fitmind.plan.domain.query.FoodQuery;
import com.fitmind.plan.domain.vo.FoodDictVO;
import com.fitmind.plan.domain.vo.FoodVO;

/**
 * 食物库服务。
 */
public interface IFoodService extends IService<Food> {

    /** 分页（scope: common/favorite/mine；category 与 keyword 可叠加） */
    PageDTO<FoodVO> queryPage(FoodQuery query);

    /** 字典：有数据的分类 + 餐次 + 左侧入口（标明哪个是分类、哪个是过滤） */
    FoodDictVO queryDict();

    /** 自定义食物，返回 foodId */
    Long createFood(FoodCreateDTO dto);

    /** 删除自定义食物（只能删自己建的） */
    void removeFood(Long id);

    /** 收藏 / 取消收藏 */
    void favorite(Long foodId);

    void unfavorite(Long foodId);
}
