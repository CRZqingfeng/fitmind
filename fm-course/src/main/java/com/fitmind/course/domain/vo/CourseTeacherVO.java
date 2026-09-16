package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 课程教练信息（含从 fm-user 取到的姓名与简介）。
 */
@Data
@Schema(description = "课程教练")
public class CourseTeacherVO {

    @Schema(description = "教练用户id")
    private Long id;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "头像")
    private String icon;

    @Schema(description = "形象照")
    private String photo;

    @Schema(description = "个人简介")
    private String intro;

    @Schema(description = "是否在详情页展示")
    private Boolean isShow;
}
