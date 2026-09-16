package com.fitmind.course.controller;

import com.fitmind.course.domain.dto.CataSubjectSaveDTO;
import com.fitmind.course.domain.vo.CataSubjectVO;
import com.fitmind.course.service.ICourseCataSubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「训练节点 ↔ 题目」关联接口（教练 / 管理端）。
 *
 * <p>路径沿用常见实现的 {@code /courses/subjects/save/{id}} 与
 * {@code /courses/subjects/get/{id}}，便于和源码对照。</p>
 */
@Validated
@RestController
@RequestMapping("/courses/subjects")
@RequiredArgsConstructor
@Tag(name = "动作小测关联接口")
public class CourseSubjectController {

    private final ICourseCataSubjectService courseCataSubjectService;

    @Operation(summary = "保存节点-题目关联（全量覆盖）")
    @Parameters(@Parameter(name = "id", description = "课程id"))
    @PostMapping("/save/{id}")
    public void save(@PathVariable("id") Long id,
                     @RequestBody @Valid List<CataSubjectSaveDTO> dtos) {
        courseCataSubjectService.save(id, dtos);
    }

    @Operation(summary = "查询课程的节点-题目关联（用于编辑回显）")
    @Parameters({
            @Parameter(name = "id", description = "课程id"),
            @Parameter(name = "see", description = "是否用于查看：true-优先看已上架，false-只看草稿（默认）")
    })
    @GetMapping("/get/{id}")
    public List<CataSubjectVO> get(@PathVariable("id") Long id,
                                   @RequestParam(value = "see", required = false, defaultValue = "false") Boolean see) {
        return courseCataSubjectService.queryCataSubjects(id, see);
    }
}
