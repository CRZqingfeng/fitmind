package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 保存课程教练请求。
 */
@Data
@Schema(description = "保存课程教练请求")
public class CourseTeacherSaveDTO {

    @Schema(description = "课程id", example = "1")
    @NotNull(message = "课程id不能为空")
    private Long id;

    @Schema(description = "教练列表（按顺序展示）")
    private List<TeacherInfo> teachers;

    @Data
    @Schema(description = "单个教练")
    public static class TeacherInfo {

        @Schema(description = "教练用户id", example = "100")
        @NotNull(message = "教练id不能为空")
        private Long id;

        @Schema(description = "是否在课程详情页展示", example = "true")
        private Boolean isShow = true;
    }
}
