package com.fitmind.user.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当前登录用户信息展示对象。
 */
@Data
@Schema(description = "用户信息")
public class UserDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户id")
    private Long id;

    @Schema(description = "手机号")
    private String cellPhone;

    @Schema(description = "用户名")
    private String username;

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

    @Schema(description = "用户类型：1-管理员，2-学员，3-教练")
    private Integer type;

    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "用户状态：0-禁用，1-正常")
    private Integer status;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;
}
