package com.fitmind.user.constants;

import java.time.Duration;

/**
 * 用户模块常量。
 */
public class UserConstants {

    private UserConstants() {
    }

    /** 新建用户（员工/教练）的默认密码 */
    public static final String DEFAULT_PASSWORD = "123456";

    /** 验证码在 Redis 中的 key 前缀 */
    public static final String USER_VERIFY_CODE_KEY = "user:verify:code:";

    /** 验证码有效期：5 分钟 */
    public static final Duration USER_VERIFY_CODE_TTL = Duration.ofMinutes(5);

    /** 验证码发送频率限制的 key 前缀 */
    public static final String USER_VERIFY_CODE_LIMIT_KEY = "user:verify:limit:";

    /** 同一手机号发送验证码的最小间隔：60 秒 */
    public static final Duration USER_VERIFY_CODE_INTERVAL = Duration.ofSeconds(60);

    /** 验证码位数 */
    public static final int VERIFY_CODE_LENGTH = 4;

    /**
     * 角色 id。
     *
     * <p>M1 阶段角色体系简化为「管理员 / 学员 / 教练」，固定 id；
     * 完整的角色-权限（RBAC）体系在管理端里程碑引入。</p>
     */
    public static final Long ADMIN_ROLE_ID = 0L;
    public static final Long STUDENT_ROLE_ID = 0L;
    public static final Long COACH_ROLE_ID = 0L;

    /** 角色名称 */
    public static final String ADMIN_ROLE_NAME = "管理员";
    public static final String STUDENT_ROLE_NAME = "学员";
    public static final String COACH_ROLE_NAME = "教练";
}
