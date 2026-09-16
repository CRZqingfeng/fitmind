package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CourseCatalogueDraft;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 训练目录（草稿表）Mapper。
 */
@Mapper
public interface CourseCatalogueDraftMapper extends BaseMapper<CourseCatalogueDraft> {

    /** 按课程查目录 */
    @Select("SELECT * FROM course_catalogue_draft WHERE course_id = #{courseId}")
    List<CourseCatalogueDraft> getByCourseId(@Param("courseId") Long courseId);

    /**
     * 按类型删除某课程的目录。
     *
     * <p>保存目录时先删后插，靠这个方法清掉旧数据
     * （数据已经在内存 map 里保住了，见 CourseCatalogueDraftServiceImpl#packageCatalogue）。</p>
     *
     * @param types 要删除的目录类型（1-训练日 2-训练动作 3-测试）
     */
    @Delete("""
            <script>
            DELETE FROM course_catalogue_draft
            WHERE course_id = #{courseId}
            AND type IN
            <foreach collection="types" item="t" open="(" separator="," close=")">#{t}</foreach>
            </script>
            """)
    int deleteByCourseId(@Param("courseId") Long courseId, @Param("types") List<Integer> types);

    /**
     * 下架时把正式表的训练目录<b>反向复制回草稿表</b>。
     *
     * <p>与 course_draft / course_content_draft 的反向复制配套，同一个事务里执行。</p>
     */
    @Insert("""
            INSERT INTO course_catalogue_draft
                (id, name, course_id, type, parent_catalogue_id, c_index,
                 media_id, video_name, media_duration, trailer,
                 sets, reps, rest_seconds, weight, duration_seconds,
                 action_type, equipment, target_muscles,
                 can_update, create_time, update_time, creater, updater)
            SELECT
                id, name, course_id, type, parent_catalogue_id, c_index,
                media_id, video_name, media_duration, trailer,
                sets, reps, rest_seconds, weight, duration_seconds,
                action_type, equipment, target_muscles,
                1, create_time, update_time, creater, updater
            FROM course_catalogue
            WHERE course_id = #{courseId} AND deleted = 0
            """)
    int insertFromCourseCatalogue(@Param("courseId") Long courseId);

    /** 查询某课程下所有「训练动作」的 id（供题目模块使用） */
    @Select("""
            SELECT id FROM course_catalogue_draft
            WHERE course_id = #{courseId} AND type = 2
            """)
    List<Long> getSectionIdByCourseId(@Param("courseId") Long courseId);
}
