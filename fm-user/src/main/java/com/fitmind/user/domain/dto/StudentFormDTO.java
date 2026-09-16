package com.fitmind.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;

/**
 * 学员注册 / 改密表单。
 */
@Data
@Schema(description = "学员注册表单")
public class StudentFormDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "手机号", example = "13800010001")
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String cellPhone;

    @Schema(description = "验证码", example = "1234")
    @NotBlank(message = "验证码不能为空")
    private String code;

    @Schema(description = "密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;

    @Schema(description = "昵称", example = "新学员")
    private String name;
}
