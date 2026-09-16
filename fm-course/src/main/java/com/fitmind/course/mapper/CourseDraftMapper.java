package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CourseDraft;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 课程（草稿表）Mapper。
 */
@Mapper
public interface CourseDraftMapper extends BaseMapper<CourseDraft> {

    /**
     * 下架时把正式表的数据<b>反向复制回草稿表</b>。
     *
     * <p>这样做的好处：教练下次编辑课程时，之前填的内容还在，不用重新录入一遍。</p>
     *
     * <p>注意 {@code c_version} 重置为 0（草稿是新的一份，重新计版本），
     * {@code can_update} 置 1（允许编辑）。</p>
     */
    @Insert("""
            INSERT INTO course_draft
                (id, name, course_type, cover_url,
                 first_cate_id, second_cate_id, third_cate_id,
                 free, price, status, step, score,
                 media_duration, valid_duration, section_num,
                 goal_type, training_level, total_days, weekly_frequency,
                 equipment, estimated_calories,
                 enroll_start_time, enroll_end_time,
                 can_update, c_version, publish_time,
                 create_time, update_time, creater, updater)
            SELECT
                id, name, course_type, cover_url,
                first_cate_id, second_cate_id, third_cate_id,
                free, price, 3, step, score,
                media_duration, valid_duration, section_num,
                goal_type, training_level, total_days, weekly_frequency,
                equipment, estimated_calories,
                enroll_start_time, enroll_end_time,
                1, 0, publish_time,
                create_time, update_time, creater, updater
            FROM course
            WHERE id = #{id}
            """)
    int insertFromCourse(@Param("id") Long id);
}
