package com.fitmind.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.learning.domain.po.LearningRecord;
import com.fitmind.learning.domain.vo.LearningProgressVO;

/**
 * 学习记录服务（动作级进度）。
 */
public interface ILearningRecordService extends IService<LearningRecord> {

    /**
     * 查询某课程的学习进度（断点续播入口）。
     *
     * @param courseId 课程id
     */
    LearningProgressVO queryProgress(Long courseId);

    /**
     * 上报学习进度。
     *
     * <p>未过半：写缓存 + ZSet 延迟任务（合并落库）；过半：直接完成并推进课表。</p>
     */
    void addRecord(Long lessonId, Long sectionId, Integer moment, Integer duration);
}
