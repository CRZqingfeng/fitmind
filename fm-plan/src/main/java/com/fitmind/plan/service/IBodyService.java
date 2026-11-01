package com.fitmind.plan.service;

import com.fitmind.plan.domain.dto.BodyMetricDTO;
import com.fitmind.plan.domain.po.UserBodyMetric;
import com.fitmind.plan.domain.vo.BodyMetricVO;

/**
 * 体征服务（体重 / 预算 / 三大营养素目标）。
 */
public interface IBodyService {

    /** 记录体重（跨天插新行，同一天更新那一行） */
    BodyMetricVO record(BodyMetricDTO dto);

    /** 当前体征（最新一行；没有就尝试用 fm-user 的档案兜底） */
    BodyMetricVO latest();

    /**
     * 解析当前用户"可用的体征"：库里有就用库里的；没有就用用户档案兜底并**落一行**。
     *
     * <p>单独暴露给饮食服务用：{@code /diet/today} 要预算，而预算来自体征。
     * 让 DietService 自己再查一遍 user_body_metric 的话，
     * "档案兜底"这套逻辑就有两份实现，迟早分叉。</p>
     *
     * @return 没有任何来源时返回 null（前端据此显示"先记录体重"）
     */
    UserBodyMetric ensureMetric(Long userId);
}
