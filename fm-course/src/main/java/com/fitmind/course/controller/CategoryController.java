package com.fitmind.course.controller;

import com.fitmind.course.domain.dto.CategoryAddDTO;
import com.fitmind.course.domain.dto.CategoryDisableOrEnableDTO;
import com.fitmind.course.domain.dto.CategoryListDTO;
import com.fitmind.course.domain.dto.CategoryUpdateDTO;
import com.fitmind.course.domain.vo.CategoryVO;
import com.fitmind.course.service.ICategoryService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程分类接口。
 *
 * <p>三级树：训练目标 → 训练部位 → 器械场景。</p>
 */
@Validated
@RestController
@RequestMapping("/categorys")
@RequiredArgsConstructor
@Tag(name = "课程分类相关接口")
public class CategoryController {

    private final ICategoryService categoryService;

    @Operation(summary = "查询课程分类（默认返回三级树）")
    @GetMapping("/list")
    public List<CategoryVO> list(CategoryListDTO dto) {
        return categoryService.list(dto);
    }

    @Operation(summary = "查询全部分类（树形，供下拉选择）")
    @GetMapping("/all")
    public List<CategoryVO> all() {
        return categoryService.getAll();
    }

    @Operation(summary = "按层级查询分类（平铺）")
    @GetMapping("/getAllOfOneLevel")
    public List<CategoryVO> getAllOfOneLevel(
            @Parameter(description = "层级：1-训练目标 2-训练部位 3-器械场景", example = "1")
            @RequestParam("level") Integer level) {
        return categoryService.getAllOfOneLevel(level);
    }

    @Operation(summary = "获取分类详情")
    @GetMapping("/{id}")
    public CategoryVO get(
            @Parameter(description = "分类id", example = "1") @PathVariable("id") Long id) {
        return categoryService.getCategoryById(id);
    }

    @Operation(summary = "新增分类")
    @PostMapping("/add")
    public void add(@Valid @RequestBody CategoryAddDTO dto) {
        categoryService.add(dto);
    }

    @Operation(summary = "更新分类")
    @PutMapping("/update")
    public void update(@Valid @RequestBody CategoryUpdateDTO dto) {
        categoryService.update(dto);
    }

    @Operation(summary = "启用或停用分类")
    @PutMapping("/disableOrEnable")
    public void disableOrEnable(@Valid @RequestBody CategoryDisableOrEnableDTO dto) {
        categoryService.disableOrEnable(dto);
    }

    @Operation(summary = "删除分类（有子分类或已被课程使用时拒绝）")
    @DeleteMapping("/{id}")
    public void delete(
            @Parameter(description = "分类id", example = "1") @PathVariable("id") Long id) {
        categoryService.delete(id);
    }
}
