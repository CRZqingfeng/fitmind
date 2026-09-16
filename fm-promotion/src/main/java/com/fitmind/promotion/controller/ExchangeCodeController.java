package com.fitmind.promotion.controller;

import com.fitmind.promotion.service.IExchangeCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 兑换码管理（运营侧）。
 */
@RestController
@RequestMapping("/exchange-codes")
@RequiredArgsConstructor
@Tag(name = "兑换码管理（运营）")
public class ExchangeCodeController {

    private final IExchangeCodeService exchangeCodeService;

    @Operation(summary = "★ 为一张券批量生成兑换码（算法生成，不预先占位）")
    @Parameters(@Parameter(name = "couponId", description = "券id"))
    @GetMapping("/generate/{couponId}")
    public List<String> generate(@PathVariable("couponId") Long couponId) {
        return exchangeCodeService.generateCodes(couponId);
    }
}
