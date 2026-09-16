package com.fitmind.exam.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.exam.domain.dto.BodyAssessFormDTO;
import com.fitmind.exam.domain.po.BodyAssessment;
import com.fitmind.exam.domain.vo.BodyAssessVO;
import com.fitmind.exam.domain.vo.BodyTrendVO;

/**
 * 体测评估服务。
 */
public interface IBodyAssessmentService extends IService<BodyAssessment> {

    /**
     * ★ 提交一次体测。
     *
     * <p>服务端做的事：取用户的性别与年龄 → 计算五项指标与分级 →
     * 生成结论与训练建议 → 落库（不可变快照）→ 发"体测完成"事件。</p>
     *
     * @return 体测记录id
     */
    Long assess(BodyAssessFormDTO dto);

    /** 最新一次评估结论 */
    BodyAssessVO latest();

    /** 某次体测的详情（只能看自己的） */
    BodyAssessVO detail(Long id);

    /** 我的体测历史（倒序） */
    PageDTO<BodyAssessVO> myHistory(PageQuery query);

    /** ★ 趋势：首次 vs 最新的变化量 */
    BodyTrendVO trend();
}
