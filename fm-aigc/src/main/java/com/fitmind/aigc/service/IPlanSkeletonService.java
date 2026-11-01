package com.fitmind.aigc.service;

import com.fitmind.aigc.domain.dto.PlanSkeletonDTO;
import com.fitmind.aigc.domain.vo.PlanSkeletonVO;

/**
 * 定制计划骨架服务（M11-4 / P2）。
 *
 * <p>它只做三件事：<b>读一眼用户数据 → 问一次模型 → 把 JSON 校验成能用的骨架</b>。
 * 具体计划（动作、食物、克数、热量）不在这里生成 —— 那是 fm-plan 的活。</p>
 */
public interface IPlanSkeletonService {

    /**
     * 生成骨架。
     *
     * <p>★ 降级是<b>正常返回</b>而不是抛异常：模型挂了/输出不合规时，
     * 用规则骨架顶上（{@code degraded=true}），用户照样能拿到计划。</p>
     */
    PlanSkeletonVO skeleton(PlanSkeletonDTO dto);
}
