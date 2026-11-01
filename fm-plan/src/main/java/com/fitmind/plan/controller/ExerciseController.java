package com.fitmind.plan.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.plan.domain.query.ExerciseQuery;
import com.fitmind.plan.domain.vo.DictVO;
import com.fitmind.plan.domain.vo.ExerciseDetailVO;
import com.fitmind.plan.domain.vo.ExerciseVO;
import com.fitmind.plan.service.IExerciseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动作库接口。
 */
@RestController
@RequestMapping("/exercises")
@RequiredArgsConstructor
@Tag(name = "动作库接口")
public class ExerciseController {

    private final IExerciseService exerciseService;

    @Operation(summary = "动作库分页（category / bodyPart / equipment 为 AND 关系，keyword 匹配中英文名）")
    @GetMapping
    public PageDTO<ExerciseVO> page(ExerciseQuery query) {
        return exerciseService.queryPage(query);
    }

    @Operation(summary = "动作详情（含肌群与步骤；heat / gif 数据集没有，返回 null）")
    @GetMapping("/{id}")
    public ExerciseDetailVO detail(
            @Parameter(description = "动作id", example = "1") @PathVariable("id") Long id) {
        return exerciseService.queryDetail(id);
    }

    @Operation(summary = "字典：分类 / 11 部位 / 8 器械 / 目标 / 难度 / 组类型 / 星期（前端不许硬编码）")
    @GetMapping("/dict")
    public DictVO dict() {
        return exerciseService.queryDict();
    }
}
