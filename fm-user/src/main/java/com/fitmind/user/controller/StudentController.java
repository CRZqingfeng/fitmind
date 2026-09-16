package com.fitmind.user.controller;

import com.fitmind.user.domain.dto.StudentFormDTO;
import com.fitmind.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学员接口。
 *
 * <p>网关视角路径：{@code /us/students/**}。注册接口免登录。</p>
 */
@RestController
@RequestMapping("/students")
@Tag(name = "学员接口")
@RequiredArgsConstructor
public class StudentController {

    private final IUserService userService;

    @Operation(summary = "学员注册（手机号 + 验证码 + 密码）")
    @PostMapping("/register")
    public void registerStudent(@Valid @RequestBody StudentFormDTO dto) {
        userService.registerStudent(dto);
    }

    @Operation(summary = "通过验证码重置密码（忘记密码）")
    @PutMapping("/password")
    public void resetPassword(@Valid @RequestBody StudentFormDTO dto) {
        userService.resetPasswordByCode(dto);
    }
}
