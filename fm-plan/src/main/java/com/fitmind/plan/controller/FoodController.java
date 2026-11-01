package com.fitmind.plan.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.plan.domain.dto.FoodCreateDTO;
import com.fitmind.plan.domain.query.FoodQuery;
import com.fitmind.plan.domain.vo.FoodDictVO;
import com.fitmind.plan.domain.vo.FoodVO;
import com.fitmind.plan.service.IFoodService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 食物库接口。
 *
 * <p>★ {@code scope}（常见/收藏/我的）与 {@code category}（主食/蔬果…）是**两个维度**，
 * 能叠加：收藏里的主食 = {@code scope=favorite&category=主食}。
 * 把它们塞进同一个参数里，就再也表达不了这种组合。</p>
 */
@RestController
@RequestMapping("/foods")
@RequiredArgsConstructor
@Tag(name = "食物库接口")
public class FoodController {

    private final IFoodService foodService;

    @Operation(summary = "食物分页（scope: common 常见 / favorite 我的收藏 / mine 我添加的）")
    @GetMapping
    public PageDTO<FoodVO> page(FoodQuery query) {
        return foodService.queryPage(query);
    }

    @Operation(summary = "字典：有数据的分类 + 餐次 + 左侧入口（标明哪个是分类、哪个是过滤）")
    @GetMapping("/dict")
    public FoodDictVO dict() {
        return foodService.queryDict();
    }

    @Operation(summary = "创建自定义食物，返回 foodId")
    @PostMapping
    public Long create(@Valid @RequestBody FoodCreateDTO dto) {
        return foodService.createFood(dto);
    }

    @Operation(summary = "删除自定义食物（只能删自己建的）")
    @DeleteMapping("/{id}")
    public void remove(
            @Parameter(description = "食物id", example = "1") @PathVariable("id") Long id) {
        foodService.removeFood(id);
    }

    @Operation(summary = "收藏食物（重复收藏幂等）")
    @PostMapping("/{id}/favorite")
    public void favorite(
            @Parameter(description = "食物id", example = "1") @PathVariable("id") Long id) {
        foodService.favorite(id);
    }

    @Operation(summary = "取消收藏（没收藏过也返回成功，天然幂等）")
    @DeleteMapping("/{id}/favorite")
    public void unfavorite(
            @Parameter(description = "食物id", example = "1") @PathVariable("id") Long id) {
        foodService.unfavorite(id);
    }
}
