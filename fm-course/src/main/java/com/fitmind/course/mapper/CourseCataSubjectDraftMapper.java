package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CataSubjectCount;
import com.fitmind.course.domain.po.CourseCataSubjectDraft;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 训练节点-题目关联（草稿表）Mapper。
 */
@Mapper
public interface CourseCataSubjectDraftMapper extends BaseMapper<CourseCataSubjectDraft> {

    @Select("SELECT * FROM course_cata_subject_draft WHERE course_id = #{courseId} ORDER BY c_index")
    List<CourseCataSubjectDraft> getByCourseId(@Param("courseId") Long courseId);

    @Delete("DELETE FROM course_cata_subject_draft WHERE course_id = #{courseId}")
    int deleteByCourseId(@Param("courseId") Long courseId);

    /**
     * 批量统计「每个节点下有几道题」（草稿表版本）。
     *
     * <p>与正式表同名方法配对：目录树在编辑期要用草稿的数字，
     * 上架后要用正式表的数字，取错表就会出现「明明刚加了题，列表还是 0」。</p>
     */
    @Select("""
            <script>
            SELECT cata_id AS cataId, COUNT(*) AS num
            FROM course_cata_subject_draft
            WHERE cata_id IN
            <foreach collection="cataIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY cata_id
            </script>
            """)
    List<CataSubjectCount> countByCataIds(@Param("cataIds") List<Long> cataIds);

    /**
     * 删掉「已经被删掉的目录节点」上挂的题目关联。
     *
     * <p>教练保存目录时删掉了某个训练动作，它下面挂的题目关联就变成孤儿了——
     * 这份数据没有课程id之外的锚点，留着只会在管理端查询时冒出来。</p>
     *
     * @param cataIds 当前课程仍然存在的节点 id 列表
     */
    @Delete("""
            <script>
            DELETE FROM course_cata_subject_draft
            WHERE course_id = #{courseId}
            <if test="cataIds != null and cataIds.size() > 0">
              AND cata_id NOT IN
              <foreach collection="cataIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            </script>
            """)
    int deleteNotInCataIdList(@Param("courseId") Long courseId, @Param("cataIds") List<Long> cataIds);

    /**
     * 查出一批题目里「已经被关联在某个节点上」的那些（删题目的前置检查）。
     *
     * <p>草稿和正式表都要查：课程可能上架（关联在正式表）也可能在编辑（关联在草稿表）。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT subject_id
            FROM course_cata_subject_draft
            WHERE subject_id IN
            <foreach collection="subjectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> queryReferencedSubjectIds(@Param("subjectIds") List<Long> subjectIds);

    /**
     * 下架：正式表关联反向复制回草稿表。
     *
     * <p>与 course_draft / course_catalogue_draft 的反向复制配套，同一个事务里执行。</p>
     */
    @Insert("""
            INSERT INTO course_cata_subject_draft
                (id, course_id, cata_id, subject_id, c_index, create_time, update_time)
            SELECT id, course_id, cata_id, subject_id, c_index, create_time, update_time
            FROM course_cata_subject
            WHERE course_id = #{courseId}
            """)
    int insertFromShelf(@Param("courseId") Long courseId);
}
