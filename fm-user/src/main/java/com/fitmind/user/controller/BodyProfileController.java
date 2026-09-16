package com.fitmind.user.controller;

import com.fitmind.common.domain.R;
import com.fitmind.common.exceptions.UnauthorizedException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.user.domain.dto.BodyProfileFormDTO;
import com.fitmind.user.domain.vo.BodyProfileVO;
import com.fitmind.user.service.IBodyProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 身体档案接口 ★。
 *
 * <p>网关视角路径：{@code /us/users/me/profile}，需要登录。</p>
 */
@RestController
@RequestMapping("/users/me/profile")
@Tag(name = "身体档案接口")
@RequiredArgsConstructor
public class BodyProfileController {

    private final IBodyProfileService bodyProfileService;

    @Operation(summary = "获取当前用户的身体档案（未填写时 data 为 null）")
    @GetMapping
    public R<BodyProfileVO> getMyProfile() {
        return R.ok(bodyProfileService.getByUserId(currentUserId()));
    }

    @Operation(summary = "保存当前用户的身体档案（首次为新增，之后为更新）")
    @PutMapping
    public R<BodyProfileVO> saveMyProfile(@RequestBody BodyProfileFormDTO dto) {
        Long userId = currentUserId();
        bodyProfileService.saveOrUpdateProfile(userId, dto);
        // 返回保存后的完整档案（含后端算好的 BMI）
        return R.ok(bodyProfileService.getByUserId(userId), "档案已保存");
    }

    private Long currentUserId() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }
}
