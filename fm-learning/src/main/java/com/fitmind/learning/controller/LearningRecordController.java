package com.fitmind.learning.controller;

import com.fitmind.learning.domain.dto.LearningRecordFormDTO;
import com.fitmind.learning.domain.vo.LearningProgressVO;
import com.fitmind.learning.service.ILearningRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习记录接口。
 */
@RestController
@RequestMapping("/learning-records")
@RequiredArgsConstructor
@Tag(name = "学习记录相关接口")
public class LearningRecordController {

    private final ILearningRecordService recordService;

    @Operation(summary = "查询课程学习进度（断点续播）")
    @GetMapping("/course/{courseId}")
    public LearningProgressVO queryProgress(
            @Parameter(description = "课程id", example = "1") @PathVariable("courseId") Long courseId) {
        return recordService.queryProgress(courseId);
    }

    @Operation(summary = "上报学习进度（未过半合并落库，过半直接完成）")
    @PostMapping
    public void addRecord(@Valid @RequestBody LearningRecordFormDTO dto) {
        recordService.addRecord(dto.getLessonId(), dto.getSectionId(), dto.getMoment(), dto.getDuration());
    }
}
