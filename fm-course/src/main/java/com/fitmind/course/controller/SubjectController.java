package com.fitmind.course.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.course.domain.dto.SubjectFormDTO;
import com.fitmind.course.domain.query.SubjectPageQuery;
import com.fitmind.course.domain.vo.SubjectVO;
import com.fitmind.course.service.ISubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动作小测题目接口（教练 / 管理端）。
 *
 * <p>这一组接口<b>都带答案与解析</b>，属于出题侧；
 * 学员侧拉题在 {@link PracticeController}（那一组不含答案）。</p>
 *
 * <p>路径与常见实现的差异：常见实现是 {@code POST /subjects/add} + 独立的
 * {@code /questions}（在 测评服务 模块）。我们收在一处，且改成规范的
 * {@code POST /subjects}（新增）/ {@code PUT /subjects/{id}}（修改）——
 * {@code POST /xxx/add} 里的 add 是多余的（POST 本身就是新增语义）。</p>
 */
@Validated
@RestController
@RequestMapping("/subjects")
@RequiredArgsConstructor
@Tag(name = "动作小测题目接口")
public class SubjectController {

    private final ISubjectService subjectService;

    @Operation(summary = "新增题目")
    @PostMapping
    public Long save(@RequestBody @Valid SubjectFormDTO dto) {
        dto.setId(null);
        return subjectService.saveSubject(dto);
    }

    @Operation(summary = "修改题目")
    @Parameters(@Parameter(name = "id", description = "题目id"))
    @PutMapping("/{id}")
    public void update(@PathVariable("id") Long id, @RequestBody @Valid SubjectFormDTO dto) {
        dto.setId(id);
        subjectService.saveSubject(dto);
    }

    @Operation(summary = "分页查询题目（含答案与解析）")
    @GetMapping("/page")
    public PageDTO<SubjectVO> page(SubjectPageQuery query) {
        return subjectService.querySubjectPage(query);
    }

    @Operation(summary = "查询题目详情（含答案与解析）")
    @Parameters(@Parameter(name = "id", description = "题目id"))
    @GetMapping("/{id}")
    public SubjectVO detail(@PathVariable("id") Long id) {
        return subjectService.querySubjectDetail(id);
    }

    @Operation(summary = "删除题目（已被训练动作关联时会被拒绝）")
    @Parameters(@Parameter(name = "id", description = "题目id"))
    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        subjectService.deleteSubject(id);
    }
}
