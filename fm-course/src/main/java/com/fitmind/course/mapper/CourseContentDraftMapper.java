package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CourseContentDraft;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 课程正文（草稿表）Mapper。
 */
@Mapper
public interface CourseContentDraftMapper extends BaseMapper<CourseContentDraft> {

    /**
     * 下架时把正式表正文<b>反向复制回草稿表</b>。
     *
     * <p>与 {@code CourseDraftMapper.insertFromCourse} 配套，
     * 两者在同一个事务里执行。</p>
     */
    @Insert("""
            INSERT INTO course_content_draft
                (id, course_introduce, use_people, course_detail,
                 precautions, required_equipment,
                 create_time, update_time, creater, updater)
            SELECT
                id, course_introduce, use_people, course_detail,
                precautions, required_equipment,
                create_time, update_time, creater, updater
            FROM course_content
            WHERE id = #{id}
            """)
    int insertFromCourseContent(@Param("id") Long id);
}
