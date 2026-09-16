package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CataSubjectCount;
import com.fitmind.course.domain.po.CourseCataSubject;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 训练节点-题目关联（正式表）Mapper。
 */
@Mapper
public interface CourseCataSubjectMapper extends BaseMapper<CourseCataSubject> {

    @Select("SELECT * FROM course_cata_subject WHERE course_id = #{courseId} ORDER BY c_index")
    List<CourseCataSubject> getByCourseId(@Param("courseId") Long courseId);

    @Delete("DELETE FROM course_cata_subject WHERE course_id = #{courseId}")
    int deleteByCourseId(@Param("courseId") Long courseId);

    /**
     * 批量统计「每个节点下有几道题」。
     *
     * <p>一次 {@code GROUP BY} 拿全，避免目录树上每个节点各查一次（N+1）。</p>
     */
    @Select("""
            <script>
            SELECT cata_id AS cataId, COUNT(*) AS num
            FROM course_cata_subject
            WHERE cata_id IN
            <foreach collection="cataIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY cata_id
            </script>
            """)
    List<CataSubjectCount> countByCataIds(@Param("cataIds") List<Long> cataIds);

    /**
     * 上架：草稿关联全量写入正式表。
     *
     * <p>与目录一样是「先清空再全量插入」——只做 saveOrUpdate 的话，
     * 本次删掉的关联会残留在正式表里。</p>
     */
    @Insert("""
            INSERT INTO course_cata_subject
                (id, course_id, cata_id, subject_id, c_index, create_time, update_time)
            SELECT id, course_id, cata_id, subject_id, c_index, create_time, update_time
            FROM course_cata_subject_draft
            WHERE course_id = #{courseId}
            """)
    int insertFromDraft(@Param("courseId") Long courseId);

    /**
     * 查出一批题目里「已经被关联在某个节点上」的那些（删题目的前置检查）。
     *
     * <p>返回非空就说明有题目正在被课程使用，不能删。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT subject_id
            FROM course_cata_subject
            WHERE subject_id IN
            <foreach collection="subjectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> queryReferencedSubjectIds(@Param("subjectIds") List<Long> subjectIds);
}
