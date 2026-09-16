package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.Subject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 题目 Mapper。
 */
@Mapper
public interface SubjectMapper extends BaseMapper<Subject> {

    /**
     * 原子累加作答统计。
     *
     * <p>为什么不用"查出来 +1 再写回"：同一道题可能被多个学员同时作答，
     * 读改写会丢更新。直接 {@code answer_times = answer_times + 1} 交给数据库，
     * 是原子的。</p>
     *
     * @param times        本次作答次数（通常是 1）
     * @param correctTimes 本次答对的次数（0 或 1）
     */
    @Update("""
            UPDATE subject
            SET answer_times = answer_times + #{times},
                correct_times = correct_times + #{correctTimes}
            WHERE id = #{id}
            """)
    int increaseTimes(@Param("id") Long id,
                      @Param("times") int times,
                      @Param("correctTimes") int correctTimes);
}
