package com.fitmind.search.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.search.constants.SearchErrorInfo;
import com.fitmind.search.domain.po.Interests;
import com.fitmind.search.domain.vo.CourseSearchVO;
import com.fitmind.search.mapper.InterestsMapper;
import com.fitmind.search.service.IInterestsService;
import com.fitmind.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户兴趣实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterestsServiceImpl extends ServiceImpl<InterestsMapper, Interests> implements IInterestsService {

    private static final String SEPARATOR = ",";

    private final ISearchService searchService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMyInterests(List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            throw new BizIllegalException(SearchErrorInfo.Msg.INTERESTS_EMPTY);
        }
        List<Long> distinct = categoryIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.size() > MAX_INTERESTS) {
            throw new BizIllegalException(SearchErrorInfo.Msg.INTERESTS_TOO_MANY);
        }

        Long userId = UserContext.getUser();
        Interests entity = new Interests()
                .setId(userId)
                .setInterests(distinct.stream().map(String::valueOf).collect(Collectors.joining(SEPARATOR)));
        // id 就是 userId，所以是"有则更新、无则插入"
        saveOrUpdate(entity);
        log.info("用户兴趣已保存：userId={}, interests={}", userId, entity.getInterests());
    }

    @Override
    public List<Long> queryMyInterests() {
        Interests entity = getById(UserContext.getUser());
        if (entity == null || entity.getInterests() == null || entity.getInterests().isBlank()) {
            return List.of();
        }
        return parse(entity.getInterests());
    }

    @Override
    public List<CourseSearchVO> queryCoursesByCategory(Long categoryId, int size) {
        if (categoryId == null) {
            return List.of();
        }
        return searchService.topByCategories(List.of(categoryId), size);
    }

    private static List<Long> parse(String interests) {
        List<Long> ids = new ArrayList<>();
        for (String part : interests.split(SEPARATOR)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    ids.add(Long.valueOf(trimmed));
                } catch (NumberFormatException e) {
                    log.warn("兴趣数据里有非法值，已跳过：{}", trimmed);
                }
            }
        }
        return ids;
    }
}
