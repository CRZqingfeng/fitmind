package com.fitmind.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.dto.user.CoachDTO;
import com.fitmind.api.dto.user.LoginFormDTO;
import com.fitmind.common.domain.dto.LoginUserDTO;
import com.fitmind.common.enums.UserType;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.exceptions.ForbiddenException;
import com.fitmind.common.exceptions.UnauthorizedException;
import com.fitmind.common.utils.AssertUtils;
import com.fitmind.common.utils.UserContext;
import com.fitmind.user.constants.UserConstants;
import com.fitmind.user.constants.UserErrorInfo;
import com.fitmind.user.domain.dto.StudentFormDTO;
import com.fitmind.user.domain.dto.UserFormDTO;
import com.fitmind.user.domain.po.User;
import com.fitmind.user.domain.po.UserDetail;
import com.fitmind.user.domain.vo.UserDetailVO;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import com.fitmind.user.enums.UserStatus;
import com.fitmind.user.mapper.UserMapper;
import com.fitmind.user.security.LoginAttemptGuard;
import com.fitmind.user.service.ICodeService;
import com.fitmind.user.service.IUserDetailService;
import com.fitmind.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户账号服务实现。
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final PasswordEncoder passwordEncoder;
    private final ICodeService codeService;
    private final IUserDetailService detailService;
    /** ★ P0-7：登录失败计数（Redis） */
    private final LoginAttemptGuard loginAttemptGuard;

    public UserServiceImpl(PasswordEncoder passwordEncoder,
                           ICodeService codeService,
                           IUserDetailService detailService,
                           LoginAttemptGuard loginAttemptGuard) {
        this.passwordEncoder = passwordEncoder;
        this.codeService = codeService;
        this.detailService = detailService;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    // ==================== 登录校验 ====================

    @Override
    public LoginUserDTO queryUserDetail(LoginFormDTO loginDTO, boolean isStaff) {
        Integer type = loginDTO.getType();
        if (type == null) {
            throw new BadRequestException(UserErrorInfo.Msg.ILLEGAL_LOGIN_TYPE);
        }
        User user;
        if (LoginFormDTO.LOGIN_TYPE_PASSWORD == type) {
            user = loginByPasswordGuarded(loginDTO);
        } else if (LoginFormDTO.LOGIN_TYPE_CODE == type) {
            user = loginByVerifyCode(loginDTO.getCellPhone(), loginDTO.getPassword());
        } else {
            throw new BadRequestException(UserErrorInfo.Msg.ILLEGAL_LOGIN_TYPE);
        }

        // 校验登录端与账号类型是否匹配（管理端 / 学员端）
        if (isStaff ^ (user.getType() != UserType.STUDENT)) {
            throw new BadRequestException(
                    isStaff ? UserErrorInfo.Msg.NON_STAFF : UserErrorInfo.Msg.NON_STUDENT);
        }

        LoginUserDTO result = new LoginUserDTO();
        result.setUserId(user.getId());
        result.setRoleId(roleIdOf(user.getType()));
        return result;
    }

    /**
     * 密码登录 + 失败计数（P0-7）。
     *
     * <p>★ 为什么在 {@link #loginByPassword} <b>外面</b>包一层，而不是把计数写进它里面：
     * 那个方法在中途还会抛"账号被冻结""参数缺失"等异常，
     * 那些<b>不该算作"密码试错"</b>（冻结是既成事实，再试几次也不会变）。
     * 包一层之后，被统计的只有这个方法的抛出——语义干净。</p>
     *
     * <p>★ 锁定期间<b>不校验密码</b>：直接拒绝。否则攻击者仍能从"响应耗时"
     * （BCrypt 校验约 100ms）侧信道判断密码对不对，计数就白做了。</p>
     */
    private User loginByPasswordGuarded(LoginFormDTO loginDTO) {
        String key = loginAttemptGuard.keyOf(loginDTO.getUsername(), loginDTO.getCellPhone());
        if (loginAttemptGuard.isBlocked(key)) {
            throw new BadRequestException(UserErrorInfo.Msg.LOGIN_TOO_MANY_ATTEMPTS);
        }
        try {
            User user = loginByPassword(loginDTO);
            // 成功即清零：否则"多年累计的几次手滑"会突然把正常用户锁住
            loginAttemptGuard.onSuccess(key);
            return user;
        } catch (BadRequestException e) {
            loginAttemptGuard.onFailure(key);
            throw e;
        }
    }

    /** 密码登录 */
    private User loginByPassword(LoginFormDTO loginDTO) {
        String username = loginDTO.getUsername();
        String cellPhone = loginDTO.getCellPhone();
        if (!StringUtils.hasText(username) && !StringUtils.hasText(cellPhone)) {
            throw new BadRequestException(UserErrorInfo.Msg.INVALID_UN);
        }
        User user = lambdaQuery()
                .eq(StringUtils.hasText(username), User::getUsername, username)
                .eq(StringUtils.hasText(cellPhone), User::getCellPhone, cellPhone)
                .one();
        AssertUtils.isNotNull(user, UserErrorInfo.Msg.INVALID_UN_OR_PW);
        checkNotFrozen(user);
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BadRequestException(UserErrorInfo.Msg.INVALID_UN_OR_PW);
        }
        return user;
    }

    /** 验证码登录 */
    private User loginByVerifyCode(String phone, String code) {
        AssertUtils.isTrue(StringUtils.hasText(phone), UserErrorInfo.Msg.INVALID_UN);
        codeService.verifyCode(phone, code);
        User user = lambdaQuery().eq(User::getCellPhone, phone).one();
        AssertUtils.isNotNull(user, UserErrorInfo.Msg.PHONE_NOT_EXISTS);
        checkNotFrozen(user);
        return user;
    }

    private void checkNotFrozen(User user) {
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException(UserErrorInfo.Msg.USER_FROZEN);
        }
    }

    private Long roleIdOf(UserType type) {
        if (type == null) {
            return UserConstants.STUDENT_ROLE_ID;
        }
        return switch (type) {
            case ADMIN -> UserConstants.ADMIN_ROLE_ID;
            case COACH -> UserConstants.COACH_ROLE_ID;
            case STUDENT -> UserConstants.STUDENT_ROLE_ID;
        };
    }

    // ==================== 用户信息 ====================

    @Override
    public UserDetailVO myInfo() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            return null;
        }
        UserDetail detail = detailService.queryById(userId);
        AssertUtils.isNotNull(detail, UserErrorInfo.Msg.USER_ID_NOT_EXISTS);

        UserDetailVO vo = BeanUtil.toBean(detail, UserDetailVO.class);
        // 枚举字段手动转换，避免类型转换歧义
        vo.setType(detail.getType() == null ? null : detail.getType().getValue());
        vo.setStatus(detail.getStatus());
        vo.setRoleName(roleNameOf(detail.getType()));
        return vo;
    }

    private String roleNameOf(UserType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case ADMIN -> UserConstants.ADMIN_ROLE_NAME;
            case COACH -> UserConstants.COACH_ROLE_NAME;
            case STUDENT -> UserConstants.STUDENT_ROLE_NAME;
        };
    }

    // ==================== 注册与密码 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void registerStudent(StudentFormDTO dto) {
        // 1.校验验证码
        codeService.verifyCode(dto.getCellPhone(), dto.getCode());
        // 2.手机号唯一性校验
        if (!isCellPhoneAvailable(dto.getCellPhone())) {
            throw new BadRequestException(UserErrorInfo.Msg.PHONE_ALREADY_EXISTS);
        }
        // 3.写入账号
        User user = new User()
                .setUsername(dto.getCellPhone())
                .setCellPhone(dto.getCellPhone())
                .setPassword(passwordEncoder.encode(dto.getPassword()))
                .setStatus(UserStatus.NORMAL)
                .setType(UserType.STUDENT);
        save(user);
        // 4.写入详情
        String name = StringUtils.hasText(dto.getName())
                ? dto.getName()
                : "学员" + dto.getCellPhone().substring(dto.getCellPhone().length() - 4);
        UserDetail detail = new UserDetail()
                .setId(user.getId())
                .setType(UserType.STUDENT)
                .setName(name)
                .setRoleId(UserConstants.STUDENT_ROLE_ID);
        detailService.save(detail);
        log.info("学员注册成功：userId={}，phone={}", user.getId(), dto.getCellPhone());
    }

    @Override
    public void resetPasswordByCode(StudentFormDTO dto) {
        // 1.校验验证码
        codeService.verifyCode(dto.getCellPhone(), dto.getCode());
        // 2.查询用户
        User old = lambdaQuery().eq(User::getCellPhone, dto.getCellPhone()).one();
        AssertUtils.isNotNull(old, UserErrorInfo.Msg.PHONE_NOT_EXISTS);
        // 3.更新密码
        updateById(new User()
                .setId(old.getId())
                .setPassword(passwordEncoder.encode(dto.getPassword())));
        log.info("用户 {} 通过验证码重置密码", old.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentUser(UserFormDTO dto) {
        Long userId = UserContext.getUser();
        AssertUtils.isNotNull(userId, UserErrorInfo.Msg.USER_ID_NOT_EXISTS);

        // 1.如提交了新密码，则校验原密码后更新
        if (StringUtils.hasText(dto.getPassword())) {
            User user = getById(userId);
            AssertUtils.isNotNull(user, UserErrorInfo.Msg.USER_ID_NOT_EXISTS);
            if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
                throw new UnauthorizedException(UserErrorInfo.Msg.INVALID_UN_OR_PW);
            }
            updateById(new User()
                    .setId(userId)
                    .setPassword(passwordEncoder.encode(dto.getPassword())));
        }

        // 2.更新详情（不允许通过该接口改类型与角色）
        UserDetail detail = BeanUtil.toBean(dto, UserDetail.class);
        detail.setId(userId);
        detail.setType(null);
        detail.setRoleId(null);
        detailService.updateById(detail);
    }

    @Override
    public boolean isCellPhoneAvailable(String cellPhone) {
        return lambdaQuery().eq(User::getCellPhone, cellPhone).count() <= 0;
    }

    @Override
    public void resetPassword(Long userId) {
        User user = getById(userId);
        AssertUtils.isNotNull(user, UserErrorInfo.Msg.USER_ID_NOT_EXISTS);
        updateById(new User()
                .setId(userId)
                .setPassword(passwordEncoder.encode(UserConstants.DEFAULT_PASSWORD)));
        log.info("已将用户 {} 的密码重置为默认密码", userId);
    }

    @Override
    public List<CoachDTO> queryCoaches(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        // 教练的姓名、头像、简介、形象照都在 user_detail 表里（user 表只有账号信息），
        // 而且 user_detail.id 就是 user.id，所以直接按 id 批量查即可
        List<UserDetail> details = detailService.listByIds(ids);
        if (details == null || details.isEmpty()) {
            return new ArrayList<>();
        }
        return details.stream().map(d -> {
            CoachDTO dto = new CoachDTO();
            dto.setId(d.getId());
            dto.setName(d.getName());
            dto.setIcon(d.getIcon());
            dto.setPhoto(d.getPhoto());
            dto.setIntro(d.getIntro());
            return dto;
        }).collect(Collectors.toList());
    }
}
