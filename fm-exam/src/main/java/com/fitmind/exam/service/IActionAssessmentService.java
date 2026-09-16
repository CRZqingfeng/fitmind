package com.fitmind.exam.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.exam.domain.dto.ActionReviewDTO;
import com.fitmind.exam.domain.dto.ActionSubmitDTO;
import com.fitmind.exam.domain.po.ActionAssessment;
import com.fitmind.exam.domain.vo.ActionAssessmentVO;

/**
 * 动作考核服务。
 *
 * <p>流程：学员提交视频 → 教练观看评分 → 学员看到分数与评语。</p>
 */
public interface IActionAssessmentService extends IService<ActionAssessment> {

    /** 学员提交考核 */
    Long submit(ActionSubmitDTO dto);

    /**
     * 教练评分。
     *
     * <p>★ 用条件更新（{@code WHERE status = 待评分}）：
     * 两个教练同时给同一条考核打分时只有一个能成功，不会互相覆盖。</p>
     */
    void review(ActionReviewDTO dto);

    /** 我的提交记录 */
    PageDTO<ActionAssessmentVO> mySubmissions(PageQuery query);

    /** 教练视角：待评分列表 */
    PageDTO<ActionAssessmentVO> pendingList(PageQuery query);

    /** 详情（学员只能看自己的） */
    ActionAssessmentVO detail(Long id);
}
