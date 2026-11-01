package com.fitmind.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.plan.domain.po.PlanDay;

import java.util.List;

/**
 * 训练日 / 动作项编排服务。
 */
public interface IPlanDayService extends IService<PlanDay> {

    /** 批量添加训练日（weekdays 里已存在的星期会被跳过，防止双击加出两遍） */
    void addDays(Long planId, List<Integer> weekdays);

    /** 训练日重命名（name 为空 = 保持原值） */
    void renameDay(Long dayId, String name);

    /** 往训练日批量加动作（组数/次数/组休取默认值 4×8、组休 60） */
    void addItems(Long dayId, List<Long> exerciseIds);

    /** 删除一个动作项，并重排后续序号、重算预计时长 */
    void removeItem(Long dayId, Long itemId);

    /**
     * 删除一个训练日（连同它下面的动作项），并重排剩余训练日的 day_index / sort、
     * 回填"一周几练"。
     *
     * <p>★ 已经练过的训练日<b>不允许删</b>（{@code workout_session.plan_day_id} 会变孤儿），
     * 会返回 400 + 说明。</p>
     */
    void deleteDay(Long dayId);
}
