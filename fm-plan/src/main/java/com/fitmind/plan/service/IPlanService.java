package com.fitmind.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.plan.domain.dto.PlanCreateDTO;
import com.fitmind.plan.domain.dto.PlanUpdateDTO;
import com.fitmind.plan.domain.po.Plan;
import com.fitmind.plan.domain.vo.PlanCardVO;
import com.fitmind.plan.domain.vo.PlanDetailVO;
import com.fitmind.plan.domain.vo.MyPlanVO;
import com.fitmind.plan.domain.vo.TodayWorkoutVO;

import java.util.List;

/**
 * 计划服务。
 */
public interface IPlanService extends IService<Plan> {

    /** 推荐计划列表（只返回卡片字段，不带动作明细，避免 N+1） */
    List<PlanCardVO> queryRecommend(String goal);

    /**
     * 我的计划列表（"我的计划" tab）。
     *
     * <p>★ 这是原设计 17 个接口里漏掉的一个：首页那个 tab 要显示"我练到哪了"，
     * 只靠 /plans/today 拿不到"我执行过的全部计划"。</p>
     */
    List<MyPlanVO> queryMine();

    /** 计划详情：基本信息 + 训练日 + 每个训练日的动作项 */
    PlanDetailVO queryDetail(Long planId);

    /** 创建空计划，返回 planId */
    Long createPlan(PlanCreateDTO dto);

    /**
     * 编辑计划（改名 / 描述 / 目标 / 难度；不传 = 不改）。
     *
     * <p>★ 官方计划不能改（{@code ownerId=0}），要改请先「执行本计划」复制一份。</p>
     */
    void updatePlan(Long planId, PlanUpdateDTO dto);

    /** 执行本计划（幂等：重复调用复用同一行 user_plan，并重置 start_date），返回 userPlanId */
    Long executePlan(Long planId);

    /** 今日训练（三态 + 今日/累计分钟数） */
    TodayWorkoutVO queryToday();
}
