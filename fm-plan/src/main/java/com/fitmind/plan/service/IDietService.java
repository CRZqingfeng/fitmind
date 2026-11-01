package com.fitmind.plan.service;

import com.fitmind.plan.domain.dto.DietLogCreateDTO;
import com.fitmind.plan.domain.vo.DietLogCreatedVO;
import com.fitmind.plan.domain.vo.DietPlanCardVO;
import com.fitmind.plan.domain.vo.DietPlanDetailVO;
import com.fitmind.plan.domain.vo.DietTodayVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 饮食服务（今日汇总 + 饮食记录 + 饮食计划）。
 */
public interface IDietService {

    /** 今日汇总（摄入 / 预算 / 还可以吃 / 三大营养素 / 四餐明细） */
    DietTodayVO queryToday(LocalDate date);

    /** 推荐饮食计划（卡片字段，不查餐次） */
    List<DietPlanCardVO> queryRecommend();

    /** 饮食计划详情（第 N 天的餐次 + 食物） */
    DietPlanDetailVO queryPlanDetail(Long planId, Integer day);

    /** 记一份食物（同一天同一餐同一食物同一单位 → 合并） */
    DietLogCreatedVO createLog(DietLogCreateDTO dto);

    /** 删一条饮食记录 */
    void removeLog(Long id);
}
