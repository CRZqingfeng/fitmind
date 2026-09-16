package com.fitmind.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fitmind.common.enums.UserType;
import com.fitmind.user.enums.UserStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户账号表。
 *
 * <p>只存「账号」层面的信息（登录凭据、状态、类型）；
 * 姓名头像等资料在 {@link UserDetail}，健身数据在 {@link UserBodyProfile}。</p>
 */
@Data
@Accessors(chain = true)
@TableName("user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户名（默认与手机号一致） */
    private String username;

    /** 手机号 */
    private String cellPhone;

    /** 密码（BCrypt 加密） */
    private String password;

    /** 账户状态：0-禁用，1-正常 */
    private UserStatus status;

    /** 用户类型：1-管理员，2-学员，3-教练 */
    private UserType type;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 创建者 id */
    private Long creater;

    /** 更新者 id */
    private Long updater;
}
