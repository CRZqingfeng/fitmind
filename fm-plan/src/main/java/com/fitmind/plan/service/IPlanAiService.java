package com.fitmind.plan.service;

import com.fitmind.plan.domain.dto.AiPreviewDTO;
import com.fitmind.plan.domain.vo.AiApplyVO;
import com.fitmind.plan.domain.vo.AiPreviewVO;
import com.fitmind.plan.domain.vo.AiTargetVO;

/**
 * AI 定制计划服务（M11-4 / P2）。
 *
 * <p>分工：<b>数字与选材在这里，语言与骨架在 fm-aigc</b>。
 * 本服务不调模型、不认识 prompt —— 它只接受"骨架"这种<b>纯数据</b>入参，
 * 这样 aigc 挂了整条链路仍然可跑（全用规则默认）。</p>
 */
public interface IPlanAiService {

    /**
     * 目标推算：当前 → 目标、预计达成日、7 天折线。
     *
     * <p>数据来源：最新 {@code user_body_metric} → 缺失则 fm-user 档案 → 都没有则
     * {@code needProfile=true}（前端引导去记录体重，而不是显示 0）。</p>
     */
    AiTargetVO queryTarget();

    /**
     * 生成预览：写两份草稿（plan / diet_plan，均 status=0、source=2）+ 一行 ai_plan_draft。
     *
     * <p>★ 同一用户重复调用<b>覆盖</b>上一次未接受的草稿（复用同一条 plan/diet_plan 行），
     * 靠 {@code uk_draft_user} 保证；已接受的草稿不会被改动。</p>
     */
    AiPreviewVO preview(AiPreviewDTO dto);

    /**
     * 接受预览：草稿置发布 + 立刻「执行本计划」（建 user_plan）。
     *
     * <p>★ 只认 {@code previewId}，且必须是<b>当前用户</b>的草稿 ——
     * 不接受前端传 planId，否则可以"接受别人的草稿"。幂等。</p>
     */
    AiApplyVO apply(String previewId);
}
