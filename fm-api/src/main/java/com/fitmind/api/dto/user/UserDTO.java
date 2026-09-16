package com.fitmind.api.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 用户信息 DTO（跨服务传输）。
 */
@Data
@Schema(description = "用户信息")
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户id")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "手机号")
    private String cellPhone;

    @Schema(description = "姓名 / 昵称")
    private String name;

    @Schema(description = "头像")
    private String icon;

    @Schema(description = "用户类型：1-管理员，2-学员，3-教练")
    private Integer type;

    @Schema(description = "用户状态：0-禁用，1-正常")
    private Integer status;

    @Schema(description = "★ 性别：0-男 1-女（M5 体测评估需要；体脂/腰臀比/基础代谢的标准都依赖它）")
    private Integer gender;

    @Schema(description = "★ 生日（M5 体测评估需要：基础代谢公式要用年龄）")
    private LocalDate birthday;
}
