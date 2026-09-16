package com.fitmind.user.controller;

import com.fitmind.api.dto.user.CoachDTO;
import com.fitmind.api.dto.user.LoginFormDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.domain.R;
import com.fitmind.common.domain.dto.LoginUserDTO;
import com.fitmind.common.utils.AssertUtils;
import com.fitmind.user.constants.UserErrorInfo;
import com.fitmind.user.domain.dto.UserFormDTO;
import com.fitmind.user.domain.po.User;
import com.fitmind.user.domain.po.UserDetail;
import com.fitmind.user.domain.vo.UserDetailVO;
import com.fitmind.user.service.IUserDetailService;
import com.fitmind.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 用户接口。
 *
 * <p>网关视角路径：{@code /us/users/**}。</p>
 */
@RestController
@RequestMapping("/users")
@Tag(name = "用户管理接口")
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;
    private final IUserDetailService detailService;

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public UserDetailVO me() {
        return userService.myInfo();
    }

    @Operation(summary = "更新当前登录用户资料，可修改密码")
    @PutMapping("/me")
    public void updateCurrentUser(@Valid @RequestBody UserFormDTO dto) {
        userService.updateCurrentUser(dto);
    }

    @Operation(summary = "检查手机号是否可用（未注册）")
    @GetMapping("/checkCellphone")
    public Boolean checkCellPhone(
            @Parameter(description = "手机号") @RequestParam("cellphone") String cellPhone) {
        return userService.isCellPhoneAvailable(cellPhone);
    }

    // ==================== 以下为服务间调用接口（供 fm-auth 通过 Feign 调用） ====================

    @Operation(summary = "登录校验：查询并校验用户信息", hidden = true)
    @PostMapping("/detail/{isStaff}")
    public LoginUserDTO queryUserDetail(@Valid @RequestBody LoginFormDTO loginDTO,
                                        @PathVariable("isStaff") boolean isStaff) {
        return userService.queryUserDetail(loginDTO, isStaff);
    }

    @Operation(summary = "根据 id 查询用户信息", hidden = true)
    @GetMapping("/{id}")
    public UserDTO queryUserById(@PathVariable("id") Long id) {
        UserDetail detail = detailService.queryById(id);
        AssertUtils.isNotNull(detail, UserErrorInfo.Msg.USER_ID_NOT_EXISTS);
        UserDTO dto = new UserDTO();
        dto.setId(detail.getId());
        dto.setUsername(detail.getUsername());
        dto.setCellPhone(detail.getCellPhone());
        dto.setName(detail.getName());
        dto.setIcon(detail.getIcon());
        dto.setStatus(detail.getStatus());
        dto.setType(detail.getType() == null ? null : detail.getType().getValue());
        return dto;
    }

    @Operation(summary = "批量查询用户信息", hidden = true)
    @GetMapping("/list")
    public List<UserDTO> queryUserByIds(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return detailService.queryByIds(ids).stream().map(detail -> {
            UserDTO dto = new UserDTO();
            dto.setId(detail.getId());
            dto.setUsername(detail.getUsername());
            dto.setCellPhone(detail.getCellPhone());
            dto.setName(detail.getName());
            dto.setIcon(detail.getIcon());
            dto.setStatus(detail.getStatus());
            dto.setType(detail.getType() == null ? null : detail.getType().getValue());
            return dto;
        }).toList();
    }

    @Operation(summary = "根据手机号查询用户 id", hidden = true)
    @GetMapping("/ids")
    public Long exchangeUserIdWithPhone(@RequestParam("phone") String phone) {
        User user = userService.lambdaQuery().eq(User::getCellPhone, phone).one();
        AssertUtils.isNotNull(user, UserErrorInfo.Msg.USER_ID_NOT_EXISTS);
        return user.getId();
    }

    @Operation(summary = "查询用户类型", hidden = true)
    @GetMapping("/{id}/type")
    public Integer queryUserType(@PathVariable("id") Long id) {
        User user = userService.getById(id);
        AssertUtils.isNotNull(user, UserErrorInfo.Msg.USER_ID_NOT_EXISTS);
        return user.getType() == null ? null : user.getType().getValue();
    }

    @Operation(summary = "重置密码为默认密码", hidden = true)
    @PutMapping("/{id}/password/default")
    public void resetPassword(@PathVariable("id") Long userId) {
        userService.resetPassword(userId);
    }

    @Operation(summary = "【内部调用】批量查询教练信息", hidden = true)
    @GetMapping("/coaches")
    public List<CoachDTO> queryCoaches(
            @Parameter(description = "教练用户id集合") @RequestParam("ids") List<Long> ids) {
        return userService.queryCoaches(ids);
    }

    @Operation(summary = "健康检查", hidden = true)
    @GetMapping("/health")
    public R<String> health() {
        return R.ok("fm-user is up");
    }
}
