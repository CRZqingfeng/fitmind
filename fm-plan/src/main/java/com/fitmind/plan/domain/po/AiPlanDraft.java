package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 定制的预览草稿指针（M11-4 / P2）。
 *
 * <p><b>本类最容易写错的地方</b>：把它当成"预览内容的存储"。
 * 它<b>只存指针</b>（plan_id / diet_plan_id），内容在那两张草稿表里 ——
 * 所以"清空重建"时删的是 plan_day / diet_plan_meal，不是这一行。</p>
 *
 * <p>★ {@code uk_draft_user} 保证"一个用户只有一个活跃草稿槽"：
 * 连续点三次「重新生成」不会堆出三份计划，而是复用同一条 plan/diet_plan。
 * 这个约束由<b>索引</b>保证，不是靠 {@code if (查到了)}（有并发窗口）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("ai_plan_draft")
@Schema(description = "AI 定制草稿指针")
public class AiPlanDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "归属用户")
    private Long userId;

    @Schema(description = "本次预览 id（uuid），日志串联 + apply 凭证")
    private String previewId;

    @Schema(description = "训练计划草稿 id（plan.status=0/source=2）")
    private Long planId;

    @Schema(description = "饮食计划草稿 id（diet_plan.status=0/source=2）")
    private Long dietPlanId;

    @Schema(description = "AI 原始骨架 JSON（clamp 前），排查用")
    private String skeletonJson;

    @Schema(description = "0=AI 正常 1=降级为规则骨架")
    private Integer degraded;

    @Schema(description = "0=未接受 1=已接受")
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 草稿状态：未接受 */
    public static final int STATUS_PENDING = 0;
    /** 草稿状态：已接受（之后再次 preview 必须新开 plan/diet，不能动已发布的） */
    public static final int STATUS_ACCEPTED = 1;
}
