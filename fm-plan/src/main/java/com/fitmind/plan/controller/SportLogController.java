package com.fitmind.plan.controller;

import com.fitmind.plan.domain.dto.SportLogCreateDTO;
import com.fitmind.plan.domain.vo.SportLogResultVO;
import com.fitmind.plan.service.ISportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运动记录接口（写）。
 *
 * <p>返回的 {@code kcal} 只用于弹层即时显示"本次消耗 XXX 千卡"；
 * 首页的总数仍然重新拉 {@code /diet/today}（口径 8）。</p>
 */
@RestController
@RequestMapping("/sport")
@RequiredArgsConstructor
@Tag(name = "运动记录接口")
public class SportLogController {

    private final ISportService sportService;

    @Operation(summary = "记一次运动，返回 {logId, kcal}")
    @PostMapping("/logs")
    public SportLogResultVO createLog(@Valid @RequestBody SportLogCreateDTO dto) {
        return sportService.createLog(dto);
    }

    @Operation(summary = "删一条运动记录")
    @DeleteMapping("/logs/{id}")
    public void removeLog(
            @Parameter(description = "运动记录id", example = "1") @PathVariable("id") Long id) {
        sportService.removeLog(id);
    }
}
