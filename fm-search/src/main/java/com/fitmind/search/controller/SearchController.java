package com.fitmind.search.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.search.domain.query.CourseSearchQuery;
import com.fitmind.search.domain.vo.CourseSearchVO;
import com.fitmind.search.service.ICourseIndexService;
import com.fitmind.search.service.ISearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程搜索接口。
 *
 * <p>网关前缀 {@code /ss/**}，即对外是 {@code /ss/courses/portal}。</p>
 *
 * <p>⚠️ 搜索接口是<b>公开的</b>（游客也能逛课程），所以这些路径没有配进登录拦截；
 * 只有 {@code /interests/**}、{@code /recommend/**} 需要登录。</p>
 */
@Slf4j
@RestController
@RequestMapping("/courses")
@RequiredArgsConstructor
@Tag(name = "课程搜索相关接口")
public class SearchController {

    private final ISearchService searchService;
    private final ICourseIndexService courseIndexService;

    @Operation(summary = "课程搜索（关键词 + 筛选 + 分页 + 高亮）")
    @GetMapping("/portal")
    public PageDTO<CourseSearchVO> search(CourseSearchQuery query) {
        return searchService.search(query);
    }

    @Operation(summary = "按关键词查课程id（供其他服务做匹配）")
    @GetMapping("/name")
    public List<Long> queryIdsByName(
            @Parameter(description = "关键词", example = "增肌") @RequestParam("keyword") String keyword,
            @Parameter(description = "最多返回多少条", example = "20")
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return searchService.queryIdsByKeyword(keyword, size);
    }

    /**
     * 【内部接口】批量同步课程到索引。
     *
     * <p>正常情况下索引由 MQ 消息驱动同步，这个接口用于<b>人工补数据</b>：
     * 比如 ES 换了实例、索引被人删了，或者消息丢了，都可以调它重建。</p>
     */
    @Operation(summary = "【内部】批量上架同步索引", hidden = true)
    @PostMapping("/up")
    public void up(@RequestBody List<Long> courseIds) {
        if (courseIds == null) {
            return;
        }
        for (Long id : courseIds) {
            try {
                courseIndexService.saveCourseIndex(id);
            } catch (Exception e) {
                // 批量里挂一条不影响其他条，但要留痕
                log.error("批量同步索引失败：courseId={}，原因：{}", id, e.getMessage());
            }
        }
    }

    /**
     * 【内部接口】批量删除索引。
     */
    @Operation(summary = "【内部】批量下架删除索引", hidden = true)
    @PostMapping("/down")
    public void down(@RequestBody List<Long> courseIds) {
        if (courseIds == null) {
            return;
        }
        for (Long id : courseIds) {
            try {
                courseIndexService.deleteCourseIndex(id);
            } catch (Exception e) {
                log.error("批量删除索引失败：courseId={}，原因：{}", id, e.getMessage());
            }
        }
    }
}
