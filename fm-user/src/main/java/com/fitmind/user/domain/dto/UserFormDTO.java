package com.fitmind.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 更新当前登录用户资料的表单。
 */
@Data
@Schema(description = "用户资料表单")
public class UserFormDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "姓名 / 昵称")
    private String name;

    @Schema(description = "性别：0-男性，1-女性")
    private Integer gender;

    @Schema(description = "头像地址")
    private String icon;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "QQ号码")
    private String qq;

    @Schema(description = "生日")
    private LocalDate birthday;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "区")
    private String district;

    @Schema(description = "个人介绍 / 健身宣言")
    private String intro;

    @Schema(description = "形象照地址")
    private String photo;

    @Schema(description = "原密码（修改密码时必填）")
    private String oldPassword;

    @Schema(description = "新密码（修改密码时必填）")
    private String password;
}
