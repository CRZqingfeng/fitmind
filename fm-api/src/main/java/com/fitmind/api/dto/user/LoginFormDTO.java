package com.fitmind.api.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录表单。
 */
@Data
@Schema(description = "登录表单实体")
public class LoginFormDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登录方式：1-密码登录 */
    public static final int LOGIN_TYPE_PASSWORD = 1;
    /** 登录方式：2-验证码登录 */
    public static final int LOGIN_TYPE_CODE = 2;

    @Schema(description = "登录方式：1-密码登录，2-验证码登录", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "登录方式不能为空")
    private Integer type;

    @Schema(description = "用户名（密码登录用）", example = "13800010001")
    private String username;

    @Schema(description = "手机号", example = "13800010001")
    private String cellPhone;

    @Schema(description = "密码（密码登录）或验证码（验证码登录）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "密码或验证码不能为空")
    private String password;

    @Schema(description = "7天免密登录", example = "false")
    private Boolean rememberMe = false;
}
