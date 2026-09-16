package com.fitmind.learning.controller;

import com.fitmind.learning.domain.vo.SignRecordVO;
import com.fitmind.learning.domain.vo.SignResultVO;
import com.fitmind.learning.service.ISignRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到接口。
 */
@RestController
@RequestMapping("/sign-records")
@RequiredArgsConstructor
@Tag(name = "签到相关接口")
public class SignRecordController {

    private final ISignRecordService signRecordService;

    @Operation(summary = "每日签到（返回连续天数与奖励）")
    @PostMapping
    public SignResultVO sign() {
        return signRecordService.sign();
    }

    @Operation(summary = "查询本月签到记录")
    @GetMapping
    public SignRecordVO querySignRecords() {
        return signRecordService.querySignRecords();
    }
}
