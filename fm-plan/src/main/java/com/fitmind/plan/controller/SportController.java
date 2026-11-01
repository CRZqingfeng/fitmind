package com.fitmind.plan.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.plan.domain.query.SportQuery;
import com.fitmind.plan.domain.vo.SportDictVO;
import com.fitmind.plan.domain.vo.SportVO;
import com.fitmind.plan.service.ISportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运动库接口（只读）。
 *
 * <p><b>为什么和 {@link SportLogController} 拆成两个类</b>：设计稿里查询是复数
 * {@code /sports}、写记录是单数 {@code /sport/logs}。类上只能写一个 {@code @RequestMapping}，
 * 硬塞进一个类的后果是两个路径必然有一个与设计稿不一致 ——
 * 而 P1 的验收脚本是按设计稿路径写的。</p>
 */
@RestController
@RequestMapping("/sports")
@RequiredArgsConstructor
@Tag(name = "运动库接口")
public class SportController {

    private final ISportService sportService;

    @Operation(summary = "运动项目分页（category / keyword）")
    @GetMapping
    public PageDTO<SportVO> page(SportQuery query) {
        return sportService.queryPage(query);
    }

    @Operation(summary = "字典：有数据的分类 + 左侧入口（含「动作库」跳转入口）")
    @GetMapping("/dict")
    public SportDictVO dict() {
        return sportService.queryDict();
    }
}
