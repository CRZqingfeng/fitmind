package com.fitmind.learning.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.learning.domain.vo.LearningLessonVO;
import com.fitmind.learning.domain.vo.LearningPlanPageVO;
import com.fitmind.learning.domain.dto.LearningPlanDTO;
import com.fitmind.learning.service.ILearningLessonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习课表接口。
 *
 * <p>网关前缀 {@code /ls/**}，对外是 {@code /ls/lessons/**}。全部需要登录。</p>
 */
@RestController
@RequestMapping("/lessons")
@RequiredArgsConstructor
@Tag(name = "学习课表相关接口")
public class LearningLessonController {

    private final ILearningLessonService lessonService;

    @Operation(summary = "报名课程（免费课；重复报名幂等）")
    @PostMapping("/{courseId}")
    public void enroll(
            @Parameter(description = "课程id", example = "1") @PathVariable("courseId") Long courseId) {
        lessonService.enroll(courseId);
    }

    @Operation(summary = "我的课程分页")
    @GetMapping("/page")
    public PageDTO<LearningLessonVO> queryMyLessons(
            @Parameter(description = "课程状态：0-未学习 1-学习中 2-已学完 3-已过期")
            @RequestParam(value = "status", required = false) Integer status,
            PageQuery query) {
        return lessonService.queryMyLessons(status, query);
    }

    @Operation(summary = "最近在学的一门课（继续训练）")
    @GetMapping("/now")
    public LearningLessonVO queryMyCurrentLesson() {
        return lessonService.queryMyCurrentLesson();
    }

    @Operation(summary = "查询某课的学习状态")
    @GetMapping("/{courseId}")
    public LearningLessonVO queryLessonByCourseId(
            @Parameter(description = "课程id", example = "1") @PathVariable("courseId") Long courseId) {
        return lessonService.queryLessonByCourseId(courseId);
    }

    @Operation(summary = "从课表移除课程")
    @DeleteMapping("/{courseId}")
    public void removeCourse(
            @Parameter(description = "课程id", example = "1") @PathVariable("courseId") Long courseId) {
        lessonService.removeCourse(courseId);
    }

    @Operation(summary = "创建/修改学习计划")
    @PostMapping("/plans")
    public void createPlan(@Valid @RequestBody LearningPlanDTO dto) {
        lessonService.createPlan(dto.getCourseId(), dto.getFreq());
    }

    @Operation(summary = "我的计划统计（本周已练/计划）")
    @GetMapping("/plans")
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        return lessonService.queryMyPlans(query);
    }

    /**
     * 【内部】手动触发过期扫描（XXL-JOB 任务的手动兜底，验收也用它）。
     */
    @Operation(summary = "【内部】手动触发课表过期扫描", hidden = true)
    @GetMapping("/internal/expire-scan")
    public int expireScan() {
        return lessonService.expireScan();
    }
}
