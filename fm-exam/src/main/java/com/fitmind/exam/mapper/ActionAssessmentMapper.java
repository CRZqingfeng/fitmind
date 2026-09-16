package com.fitmind.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.exam.domain.po.ActionAssessment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 动作考核 Mapper。
 */
@Mapper
public interface ActionAssessmentMapper extends BaseMapper<ActionAssessment> {

    /**
     * ★ 评分：条件更新（{@code WHERE status = 待评分}）。
     *
     * <p>评分结果与状态在<b>同一次更新</b>里写入 ——
     * 如果先改状态再补分数，中间那段时间这条记录是"已评分但没分"的，
     * 而且补写那步没有条件校验的话，并发里被抢输的那个请求也能覆盖分数。</p>
     *
     * @return 1 = 本次抢到了评分权；0 = 已被别人评过
     */
    @Update("""
            UPDATE action_assessment
            SET status = #{targetStatus},
                score = #{score},
                comment = #{comment},
                coach_id = #{coachId},
                review_time = NOW()
            WHERE id = #{id} AND status = #{expectStatus}
            """)
    int review(@Param("id") Long id,
               @Param("expectStatus") int expectStatus,
               @Param("targetStatus") int targetStatus,
               @Param("score") Integer score,
               @Param("comment") String comment,
               @Param("coachId") Long coachId);
}
