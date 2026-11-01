package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.AiPlanDraft;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 定制草稿 Mapper。
 *
 * <p>查询一律走 {@code uk_draft_user} / {@code uk_draft_preview}，两个唯一索引都命中，
 * 不需要额外建普通索引。</p>
 */
@Mapper
public interface AiPlanDraftMapper extends BaseMapper<AiPlanDraft> {
}
