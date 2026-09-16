package com.fitmind.course.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.course.domain.dto.PracticeSubmitDTO;
import com.fitmind.course.domain.dto.SubjectFormDTO;
import com.fitmind.course.domain.po.Subject;
import com.fitmind.course.domain.query.SubjectPageQuery;
import com.fitmind.course.domain.vo.PracticeResultVO;
import com.fitmind.course.domain.vo.SubjectSimpleVO;
import com.fitmind.course.domain.vo.SubjectVO;

import java.util.List;

/**
 * 动作小测题目服务。
 */
public interface ISubjectService extends IService<Subject> {

    /**
     * 保存题目（新增 / 修改）。
     *
     * @return 题目 id
     */
    Long saveSubject(SubjectFormDTO dto);

    /** 题目分页（教练 / 管理端，含答案解析） */
    PageDTO<SubjectVO> querySubjectPage(SubjectPageQuery query);

    /** 题目详情（教练 / 管理端，含答案解析） */
    SubjectVO querySubjectDetail(Long id);

    /** 删除题目（已被节点关联时拒绝） */
    void deleteSubject(Long id);

    /**
     * 删除课程时清理该课程的全部题目。
     *
     * <p>与 {@link #deleteSubject(Long)} 不同：这里<b>不做</b>"被关联就不许删"的检查——
     * 调用方（删除课程）已经先把题目关联一并清掉了，顺序上不存在悬挂引用。</p>
     */
    void deleteByCourseId(Long courseId);

    /**
     * 学员拉题——<b>不含答案与解析</b>。
     *
     * @param cataId 训练动作 / 测评节点 id
     */
    List<SubjectSimpleVO> queryPractice(Long cataId);

    /**
     * 提交作答并判分。
     *
     * <p>服务端判分（不是前端自己对答案），答对的题发积分事件。</p>
     *
     * @param userId 当前学员 id
     */
    PracticeResultVO submitPractice(PracticeSubmitDTO dto, Long userId);
}
