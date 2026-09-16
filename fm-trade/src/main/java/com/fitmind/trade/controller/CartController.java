package com.fitmind.trade.controller;

import com.fitmind.trade.domain.dto.CartAddDTO;
import com.fitmind.trade.domain.vo.CartVO;
import com.fitmind.trade.service.ICartService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口。
 */
@Validated
@RestController
@RequestMapping("/carts")
@RequiredArgsConstructor
@Tag(name = "购物车接口")
public class CartController {

    private final ICartService cartService;

    @Operation(summary = "加入购物车")
    @PostMapping
    public void add(@RequestBody @Valid CartAddDTO dto) {
        cartService.addCourse(dto.getCourseId());
    }

    @Operation(summary = "查询我的购物车（价格取课程最新值，失效课程会被标注）")
    @GetMapping
    public List<CartVO> myCarts() {
        return cartService.queryMyCarts();
    }

    @Operation(summary = "移出购物车（幂等）")
    @Parameters(@Parameter(name = "courseId", description = "课程id"))
    @DeleteMapping("/{courseId}")
    public void remove(@PathVariable("courseId") Long courseId) {
        cartService.removeCourse(courseId);
    }

    @Operation(summary = "清空购物车")
    @DeleteMapping
    public void clear() {
        cartService.clear();
    }
}
