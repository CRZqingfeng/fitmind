package com.fitmind.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.api.dto.user.CoachDTO;
import com.fitmind.api.dto.user.LoginFormDTO;
import com.fitmind.common.domain.dto.LoginUserDTO;
import com.fitmind.user.domain.dto.StudentFormDTO;
import com.fitmind.user.domain.dto.UserFormDTO;
import com.fitmind.user.domain.po.User;
import com.fitmind.user.domain.vo.UserDetailVO;

import java.util.List;

/**
 * 用户账号服务。
 */
public interface IUserService extends IService<User> {

    /**
     * 登录校验（供认证服务通过 Feign 调用）。
     *
     * <p>根据 {@code loginDTO.type} 选择密码登录或验证码登录，
     * 校验通过后返回 {@link LoginUserDTO}（含 userId、roleId）。</p>
     */
    LoginUserDTO queryUserDetail(LoginFormDTO loginDTO, boolean isStaff);

    /**
     * 获取当前登录用户的详细信息
     */
    UserDetailVO myInfo();

    /**
     * 学员注册（手机号 + 验证码 + 密码）
     */
    void registerStudent(StudentFormDTO dto);

    /**
     * 通过验证码重置密码（忘记密码场景）
     */
    void resetPasswordByCode(StudentFormDTO dto);

    /**
     * 更新当前登录用户资料，可同时修改密码
     */
    void updateCurrentUser(UserFormDTO dto);

    /**
     * 手机号是否可用（未注册）
     */
    boolean isCellPhoneAvailable(String cellPhone);

    /**
     * 将指定用户的密码重置为默认密码
     */
    void resetPassword(Long userId);

    /**
     * 批量查询教练信息（供课程服务展示课程教练用）。
     *
     * <p>教练的姓名、头像、简介、形象照全在 {@code user_detail} 表里，
     * 普通的按 id 查用户拿不到这些字段。</p>
     */
    List<CoachDTO> queryCoaches(List<Long> ids);
}
