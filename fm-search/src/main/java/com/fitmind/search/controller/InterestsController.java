package com.fitmind.search.controller;

import com.fitmind.common.utils.UserContext;
import com.fitmind.search.domain.vo.CourseSearchVO;
import com.fitmind.search.service.IInterestsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户兴趣。
 *
 * <p>用户勾选感兴趣的训练部位（二级分类），用于个性化推荐。</p>
 *
 * <p>⚠️ 这里全部需要登录（用户身份来自 {@link UserContext}），
 * 已在 application.yml 的 {@code includeLoginPaths} 里配了 {@code /interests/**}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/interests")
@RequiredArgsConstructor
@Tag(name = "用户兴趣相关接口")
public class InterestsController {

    private final IInterestsService interestsService;

    @Operation(summary = "保存我的兴趣（全量覆盖）")
    @PostMapping
    public void saveMyInterests(@Valid @RequestBody List<Long> categoryIds) {
        interestsService.saveMyInterests(categoryIds);
    }

    @Operation(summary = "查询我的兴趣")
    @GetMapping
    public List<Long> queryMyInterests() {
        return interestsService.queryMyInterests();
    }

    /**
     * 按分类查课程。
     *
     * <p>传的是<b>分类id</b>（不是用户id）——这个接口的用途是
     * 「看看这个感兴趣的分类下有哪些课」，首页点兴趣标签时调它。</p>
     */
    @Operation(summary = "按兴趣分类查课程")
    @GetMapping("/{id}/courses")
    public List<CourseSearchVO> queryCoursesOfCategory(
            @Parameter(description = "分类id", example = "2") @PathVariable("id") Long categoryId,
            @Parameter(description = "返回条数", example = "10")
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return interestsService.queryCoursesByCategory(categoryId, size);
    }
}
