package com.fitmind.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fitmind.common.enums.UserType;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户详情表。
 *
 * <p>与 {@link User} 是 1:1 关系，主键相同。</p>
 *
 * <p>健身场景适配：去掉了常见实现中教育强相关的「岗位 job」「部门 depId」。</p>
 */
@Data
@Accessors(chain = true)
@TableName("user_detail")
public class UserDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 id（与 user.id 一致，非自增） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 用户类型：1-管理员，2-学员，3-教练 */
    private UserType type;

    /** 姓名 / 昵称 */
    private String name;

    /** 性别：0-男性，1-女性 */
    private Integer gender;

    /** 头像地址 */
    private String icon;

    /** 邮箱 */
    private String email;

    /** QQ 号码 */
    private String qq;

    /** 生日 */
    private LocalDate birthday;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区 */
    private String district;

    /** 个人介绍 / 健身宣言 */
    private String intro;

    /** 形象照地址 */
    private String photo;

    /** 角色 id（管理端 RBAC 使用） */
    private Long roleId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 创建者 id */
    private Long creater;

    /** 更新者 id */
    private Long updater;

    // ==================== 非数据库字段（联表查询时填充） ====================

    @TableField(exist = false)
    private String cellPhone;

    @TableField(exist = false)
    private String username;

    @TableField(exist = false)
    private Integer status;
}
