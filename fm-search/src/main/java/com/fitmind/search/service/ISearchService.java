package com.fitmind.search.service;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.search.domain.po.CourseDoc;
import com.fitmind.search.domain.query.CourseSearchQuery;
import com.fitmind.search.domain.vo.CourseSearchVO;

import java.util.List;

/**
 * 搜索与推荐服务。
 */
public interface ISearchService {

    /** 关键词检索（含筛选、排序、分页、高亮） */
    PageDTO<CourseSearchVO> search(CourseSearchQuery query);

    /** 按关键词只查课程id（供其他服务做匹配） */
    List<Long> queryIdsByKeyword(String keyword, int size);

    /** 综合最优（评分 + 报名数加权） */
    List<CourseSearchVO> topBest(int size);

    /** 最新上架 */
    List<CourseSearchVO> topNew(int size);

    /** 免费好课 */
    List<CourseSearchVO> topFree(int size);

    /** 按分类取课程（兴趣推荐用） */
    List<CourseSearchVO> topByCategories(List<Long> categoryIds, int size);

    /** 文档 → 检索结果 VO */
    CourseSearchVO toVO(CourseDoc doc, String highlightName);
}
