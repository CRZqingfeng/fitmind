package com.fitmind.search.controller;

import com.fitmind.search.domain.vo.CourseSearchVO;
import com.fitmind.search.service.ISearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程榜单推荐。
 *
 * <p>三个榜单对应前端首页最常见的三个位置：猜你喜欢 / 新课上架 / 免费专区。</p>
 */
@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
@Tag(name = "课程推荐相关接口")
public class RecommendController {

    private static final int DEFAULT_SIZE = 10;

    private final ISearchService searchService;

    /**
     * 综合最优：评分 + 报名数加权。
     *
     * <p>报名数当前恒为 0（M4 交易模块上线后才有真实值），所以现阶段实际是按评分排。</p>
     */
    @Operation(summary = "热门推荐（综合最优）")
    @GetMapping("/best")
    public List<CourseSearchVO> best(
            @Parameter(description = "返回条数", example = "10")
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return searchService.topBest(size > 0 ? size : DEFAULT_SIZE);
    }

    @Operation(summary = "最新上架")
    @GetMapping("/new")
    public List<CourseSearchVO> newest(
            @Parameter(description = "返回条数", example = "10")
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return searchService.topNew(size > 0 ? size : DEFAULT_SIZE);
    }

    @Operation(summary = "免费好课")
    @GetMapping("/free")
    public List<CourseSearchVO> free(
            @Parameter(description = "返回条数", example = "10")
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return searchService.topFree(size > 0 ? size : DEFAULT_SIZE);
    }
}
