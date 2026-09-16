package com.fitmind.search.service;

/**
 * 课程索引同步服务。
 *
 * <p>命名上加了个 {@code Index}，是为了和 fm-course 里的课程服务区分开：
 * 这里<b>不产生业务数据</b>，只负责把 fm-course 的数据翻译成 ES 文档。</p>
 */
public interface ICourseIndexService {

    /**
     * 同步一条课程到索引。
     *
     * @param courseId 课程id
     */
    void saveCourseIndex(Long courseId);

    /**
     * 从索引中删除一条课程。
     *
     * @param courseId 课程id
     */
    void deleteCourseIndex(Long courseId);
}
