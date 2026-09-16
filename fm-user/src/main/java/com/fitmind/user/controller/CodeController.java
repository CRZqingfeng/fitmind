package com.fitmind.user.controller;

import com.fitmind.common.domain.R;
import com.fitmind.user.service.ICodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 验证码接口。
 *
 * <p><b>说明</b>：常见实现中 {@code ICodeService.sendVerifyCode()} 只有实现、
 * 没有任何 Controller 暴露它，前端无法调用。本接口属于<b>我们补全</b>的部分。</p>
 *
 * <p>网关视角路径：{@code POST /us/codes}（免登录，在网关免鉴权名单中）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/codes")
@Tag(name = "验证码接口")
@RequiredArgsConstructor
public class CodeController {

    private final ICodeService codeService;

    @Operation(summary = "发送短信验证码")
    @PostMapping
    public R<String> sendVerifyCode(
            @Parameter(description = "手机号", example = "13800010001")
            @RequestParam("phone") String phone) {
        String code = codeService.sendVerifyCode(phone);
        // 仅 Mock 模式（本地开发）下把验证码回传，方便调试；生产环境返回 null
        return R.ok(codeService.isSmsMock() ? code : null, "验证码已发送");
    }
}
