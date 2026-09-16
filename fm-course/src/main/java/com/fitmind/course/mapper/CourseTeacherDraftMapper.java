package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CourseTeacherDraft;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 课程教练（草稿表）Mapper。
 */
@Mapper
public interface CourseTeacherDraftMapper extends BaseMapper<CourseTeacherDraft> {

    /** 按课程删除教练（保存时先清空再插入；上架后也要清空草稿） */
    @Delete("DELETE FROM course_teacher_draft WHERE course_id = #{courseId}")
    int deleteByCourseId(@Param("courseId") Long courseId);

    /**
     * 下架时把正式表的课程教练<b>反向复制回草稿表</b>。
     */
    @Insert("""
            INSERT INTO course_teacher_draft
                (id, course_id, teacher_id, is_show, c_index,
                 create_time, update_time, creater, updater)
            SELECT
                id, course_id, teacher_id, is_show, c_index,
                create_time, update_time, creater, updater
            FROM course_teacher
            WHERE course_id = #{courseId} AND deleted = 0
            """)
    int insertFromCourseTeacher(@Param("courseId") Long courseId);
}
