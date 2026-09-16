package com.fitmind.api.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 教练信息（课程详情展示用）。
 *
 * <p>字段来自 fm-user 的两张表：</p>
 * <ul>
 *   <li>{@code user} 表 —— name（姓名）、icon（头像）</li>
 *   <li>{@code user_detail} 表 —— intro（简介）、photo（形象照）</li>
 * </ul>
 */
@Data
@Schema(description = "教练信息")
public class CoachDTO implements Serializable {

    private static final long serialVersionUID = 1L;

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
}
