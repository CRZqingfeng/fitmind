package com.fitmind.course.controller;

import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.api.dto.course.CataSimpleDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.course.CourseSearchInfoDTO;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.course.constants.CourseStatus;
import com.fitmind.course.domain.dto.CataSaveDTO;
import com.fitmind.course.domain.dto.CourseBaseInfoSaveDTO;
import com.fitmind.course.domain.dto.CourseIdDTO;
import com.fitmind.course.domain.dto.CourseMediaDTO;
import com.fitmind.course.domain.dto.CourseTeacherSaveDTO;
import com.fitmind.course.domain.query.CoursePageQuery;
import com.fitmind.course.domain.vo.CataVO;
import com.fitmind.course.domain.vo.CourseBaseInfoVO;
import com.fitmind.course.domain.vo.CoursePageVO;
import com.fitmind.course.domain.vo.CourseSaveVO;
import com.fitmind.course.domain.vo.CourseTeacherVO;
import com.fitmind.course.domain.vo.NameExistVO;
import com.fitmind.course.service.ICourseCatalogueDraftService;
import com.fitmind.course.service.ICourseDraftService;
import com.fitmind.course.service.ICourseService;
import com.fitmind.course.service.ICourseTeacherDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程管理接口（管理端）。
 *
 * <p>核心是「草稿-发布」：保存只写草稿表，点「上架」才整体同步到正式表。</p>
 */
@Validated
@RestController
@RequestMapping("/courses")
@RequiredArgsConstructor
@Tag(name = "课程管理相关接口")
public class CourseController {

    private final ICourseDraftService courseDraftService;
    private final ICourseService courseService;
    private final ICourseCatalogueDraftService courseCatalogueDraftService;
    private final ICourseTeacherDraftService courseTeacherDraftService;

    // ==================== 训练目录 ====================

    @Operation(summary = "保存训练目录（训练日 + 训练动作，全量覆盖）")
    @PostMapping("/catas/save/{id}/{step}")
    public void saveCatas(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "当前步骤：2-目录 4-习题", example = "2") @PathVariable("step") Integer step,
            @Valid @RequestBody List<CataSaveDTO> dtos) {
        courseCatalogueDraftService.save(id, dtos, step);
    }

    @Operation(summary = "查询训练目录（树形）")
    @GetMapping("/catas/{id}")
    public List<CataVO> queryCatas(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "true-优先看已上架的（用户端）") @RequestParam(value = "see", required = false, defaultValue = "false") Boolean see) {
        return courseCatalogueDraftService.queryCourseCatalogues(id, see);
    }

    @Operation(summary = "保存训练动作信息（绑定训练视频 + 设置组数/次数/休息/负重）")
    @PostMapping("/media/save/{id}")
    public void saveMediaInfo(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody List<CourseMediaDTO> dtos) {
        courseCatalogueDraftService.saveMediaInfo(id, dtos);
    }

    @Operation(summary = "用户端：查询课程训练目录")
    @GetMapping("/{id}/catalogs")
    public List<CataVO> catalogs(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id) {
        return courseCatalogueDraftService.queryCourseCatalogues(id, true);
    }

    // ==================== 课程教练 ====================

    @Operation(summary = "保存课程教练")
    @PostMapping("/teachers/save")
    public void saveTeachers(@Valid @RequestBody CourseTeacherSaveDTO dto) {
        courseTeacherDraftService.save(dto);
    }

    @Operation(summary = "查询课程教练")
    @GetMapping("/teachers/{id}")
    public List<CourseTeacherVO> queryTeachers(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "true-优先看已上架的（用户端）") @RequestParam(value = "see", required = false, defaultValue = "false") Boolean see) {
        return courseTeacherDraftService.queryTeacherOfCourse(id, see);
    }

    // ==================== 基本信息 ====================

    @Operation(summary = "保存课程基本信息（含正文），返回课程id与当前填写进度")
    @PostMapping("/baseInfo/save")
    public CourseSaveVO save(@Valid @RequestBody CourseBaseInfoSaveDTO dto) {
        return courseDraftService.save(dto);
    }

    @Operation(summary = "查询课程基本信息（优先取草稿）")
    @GetMapping("/baseInfo/{id}")
    public CourseBaseInfoVO baseInfo(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id) {
        return courseDraftService.getCourseBaseInfo(id);
    }

    @Operation(summary = "课程名称是否已存在")
    @GetMapping("/checkName")
    public NameExistVO checkName(
            @Parameter(description = "课程名称") @RequestParam("name") String name,
            @Parameter(description = "课程id，编辑时传入以排除自己") @RequestParam(value = "id", required = false) Long id) {
        return courseDraftService.checkName(name, id);
    }

    // ==================== 上架 / 下架 ====================

    @Operation(summary = "课程上架（草稿同步到正式表，并发 MQ 通知搜索服务）")
    @PostMapping("/upShelf")
    public void upShelf(@Valid @RequestBody CourseIdDTO dto) {
        courseDraftService.upShelf(dto.getId());
    }

    @Operation(summary = "上架前校验（不落库，供前端提前提示）")
    @GetMapping("/checkBeforeUpShelf/{id}")
    public void checkBeforeUpShelf(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id) {
        courseDraftService.checkBeforeUpShelf(id);
    }

    @Operation(summary = "课程下架（正式数据反向复制回草稿，并发 MQ）")
    @PostMapping("/downShelf")
    public void downShelf(@Valid @RequestBody CourseIdDTO dto) {
        courseDraftService.downShelf(dto.getId());
    }

    // ==================== 列表与删除 ====================

    /**
     * 分页查询课程。
     *
     * <p><b>为什么按状态切换数据源</b>：课程数据在两处——
     * 「待上架 / 下架」在草稿表，「已上架 / 已完结」在正式表。
     * 上架时草稿被删、下架时草稿被重建，所以同一时刻一份数据只在一张表里，
     * 按状态路由就不会漏也不会重。</p>
     */
    @Operation(summary = "分页查询课程（管理端）")
    @GetMapping("/page")
    public PageDTO<CoursePageVO> queryForPage(CoursePageQuery query) {
        if (CourseStatus.SHELF.equals(query.getStatus()) || CourseStatus.FINISHED.equals(query.getStatus())) {
            // 已上架 / 已完结 → 正式表
            return courseService.queryForPage(query);
        }
        // 待上架 / 下架 / 未指定状态 → 草稿表
        return courseDraftService.queryForPage(query);
    }

    @Operation(summary = "删除课程（草稿与正式一起删）")
    @DeleteMapping("/delete/{id}")
    public void delete(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id) {
        courseDraftService.delete(id);
    }

    // ==================== 内部接口（服务间调用） ====================

    /**
     * 查询课程检索信息，供 fm-search 组装 ES 文档。
     *
     * <p><b>为什么放在 fm-course 而不是让 fm-search 自己拼</b>：
     * 一份 ES 文档要课程 + 三级分类名 + 教练 + 训练目录四份数据。
     * 若由 fm-search 逐个去调，一次索引同步会膨胀成 4~5 次跨服务调用；
     * 让数据归属方一次聚合好，调用方只做"翻译成 ES 文档"这一件事。</p>
     *
     * <p>路径为 {@code /courses/internal/**}，已在 application.yml 的
     * {@code excludeLoginPaths} 白名单中（内部调用不带 token）。</p>
     */
    @Operation(summary = "查询课程检索信息（内部）", hidden = true)
    @GetMapping("/internal/{id}/searchInfo")
    public CourseSearchInfoDTO searchInfo(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id) {
        return courseService.querySearchInfo(id);
    }

    /**
     * 查询课程全文，供 fm-learning 报名校验与学习进度计算使用。
     */
    @Operation(summary = "查询课程全文（内部）", hidden = true)
    @GetMapping("/internal/{id}/full")
    public CourseFullDTO courseFull(
            @Parameter(description = "课程id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "是否带训练目录") @RequestParam(value = "withCatalogues", defaultValue = "false") boolean withCatalogues) {
        return courseService.queryCourseFull(id, withCatalogues);
    }

    /**
     * 批量查询课程简要信息，供 fm-learning 课表分页 / 计划列表（避免 N+1 单查）。
     */
    @Operation(summary = "批量查询课程简要信息（内部）", hidden = true)
    @GetMapping("/internal/simple")
    public List<CourseSimpleDTO> simpleList(
            @Parameter(description = "课程id 集合，逗号分隔") @RequestParam("ids") List<Long> ids) {
        return courseService.querySimpleList(ids);
    }

    /**
     * 批量查询训练目录名，供 fm-learning 问答/笔记管理端展示（避免 N+1 单查）。
     */
    @Operation(summary = "批量查询训练目录名（内部）", hidden = true)
    @GetMapping("/internal/catas/simple")
    public List<CataSimpleDTO> cataSimpleList(
            @Parameter(description = "目录id 集合，逗号分隔") @RequestParam("ids") List<Long> ids) {
        return courseService.queryCataSimpleList(ids);
    }
}
