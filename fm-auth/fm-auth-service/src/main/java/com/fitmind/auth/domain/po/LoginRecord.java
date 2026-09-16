package com.fitmind.auth.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 登录信息记录表。
 */
@Data
@Accessors(chain = true)
@TableName("login_record")
public class LoginRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 id */
    private Long userId;

    /** 手机号 */
    private String cellPhone;

    /** 登录时间 */
    private LocalDateTime loginTime;

    /** 登出时间 */
    private LocalDateTime logoutTime;

    /** 登录日期 */
    private LocalDate loginDate;

    /** 登录时长（秒） */
    private Long duration;

    /** ip 地址 */
    private String ipv4;
}
