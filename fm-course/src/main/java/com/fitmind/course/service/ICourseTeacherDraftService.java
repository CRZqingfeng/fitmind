package com.fitmind.course.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.course.domain.dto.CourseTeacherSaveDTO;
import com.fitmind.course.domain.po.CourseTeacherDraft;
import com.fitmind.course.domain.vo.CourseTeacherVO;

import java.util.List;

/**
 * 课程教练服务。
 */
public interface ICourseTeacherDraftService extends IService<CourseTeacherDraft> {

    /** 保存课程教练（全量覆盖，并推进 step 到「教练」步） */
    void save(CourseTeacherSaveDTO dto);

    /**
     * 查询课程教练。
     *
     * @param see true-优先看已上架的（用户端），false-只看草稿（管理端编辑）
     */
    List<CourseTeacherVO> queryTeacherOfCourse(Long courseId, Boolean see);

    /** 上架：草稿教练同步到正式表，然后删草稿 */
    void copyToShelf(Long courseId, Boolean isFirstShelf);

    /** 下架：正式表教练反向复制回草稿 */
    void copyFromShelf(Long courseId);

    /**
     * 删除课程时清理教练关联（<b>草稿表 + 正式表一起清</b>）。
     *
     * <p>⚠️ 这一步以前是<b>漏的</b>：删课程只清了 course / content / catalogue /
     * cata_subject / subject，独独漏了教练。后果是 {@code course_teacher} 里
     * 留下大量孤儿行（本项目累积到 268 条），而且因为 {@code course} 是
     * <b>自增 id、会复用</b>，新课程有可能"继承"上一门课的教练关联 ——
     * 不是纯脏数据，是会造成错误展示的缺陷。</p>
     */
    void deleteByCourseId(Long courseId);
}
