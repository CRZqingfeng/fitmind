package com.fitmind.search.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.search.domain.po.CourseDoc;
import com.fitmind.search.domain.query.CourseSearchQuery;
import com.fitmind.search.domain.vo.CourseSearchVO;
import com.fitmind.search.repository.CourseRepository;
import com.fitmind.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索与推荐实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    private final CourseRepository courseRepository;

    @Override
    public PageDTO<CourseSearchVO> search(CourseSearchQuery query) {
        CourseRepository.SearchPage page = courseRepository.search(query);

        List<CourseSearchVO> vos = new ArrayList<>(page.docs().size());
        for (CourseDoc doc : page.docs()) {
            vos.add(toVO(doc, page.highlight().get(doc.getId())));
        }
        long pages = query.getPageSize() == null || query.getPageSize() == 0
                ? 0 : (page.total() + query.getPageSize() - 1) / query.getPageSize();
        log.debug("检索完成：keyword={}, total={}", query.getKeyword(), page.total());
        return new PageDTO<>(page.total(), pages, vos);
    }

    @Override
    public List<Long> queryIdsByKeyword(String keyword, int size) {
        return courseRepository.queryIdsByKeyword(keyword, size);
    }

    @Override
    public List<CourseSearchVO> topBest(int size) {
        return toVOs(courseRepository.topBest(size));
    }

    @Override
    public List<CourseSearchVO> topNew(int size) {
        return toVOs(courseRepository.topNew(size));
    }

    @Override
    public List<CourseSearchVO> topFree(int size) {
        return toVOs(courseRepository.topFree(size));
    }

    @Override
    public List<CourseSearchVO> topByCategories(List<Long> categoryIds, int size) {
        return toVOs(courseRepository.topByCategories(categoryIds, size));
    }

    @Override
    public CourseSearchVO toVO(CourseDoc doc, String highlightName) {
        CourseSearchVO vo = BeanUtil.copyProperties(doc, CourseSearchVO.class);
        vo.setHighlightName(highlightName);
        return vo;
    }

    private List<CourseSearchVO> toVOs(List<CourseDoc> docs) {
        return docs.stream().map(d -> toVO(d, null)).toList();
    }
}
