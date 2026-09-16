package com.fitmind.search.service;

import java.util.List;

/**
 * 用户兴趣服务。
 *
 * <p>用户勾选感兴趣的分类（多为二级分类 = 训练部位），用于个性化推荐。</p>
 */
public interface IInterestsService {

    /** 最多能选几个分类 */
    int MAX_INTERESTS = 10;

    /** 保存我的兴趣（全量覆盖） */
    void saveMyInterests(List<Long> categoryIds);

    /** 查询我的兴趣 */
    List<Long> queryMyInterests();

    /** 按分类查课程（兴趣推荐用） */
    List<com.fitmind.search.domain.vo.CourseSearchVO> queryCoursesByCategory(Long categoryId, int size);
}
