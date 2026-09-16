package com.fitmind.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 用户类型。
 *
 * <p>实现 MyBatis-Plus 的 {@link IEnum}，写入数据库时自动存 value，读取时自动转回枚举。</p>
 *
 * <p>与常见实现的差异：健身平台把「老师」改为「教练」，并去掉「其他员工」。</p>
 */
@Getter
public enum UserType implements IEnum<Integer> {

    /** 管理员 */
    ADMIN(1, "管理员"),

    /** 学员 */
    STUDENT(2, "学员"),

    /** 教练 */
    COACH(3, "教练"),
    ;

    private final int value;

    private final String desc;

    UserType(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public Integer getValue() {
        return value;
    }

    public static UserType of(int value) {
        for (UserType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("用户类型不合法：" + value);
    }
}
