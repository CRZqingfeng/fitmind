package com.fitmind.user.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 用户状态。
 */
@Getter
public enum UserStatus implements IEnum<Integer> {

    /** 禁用 */
    FROZEN(0, "禁用"),

    /** 正常 */
    NORMAL(1, "正常"),
    ;

    private final int value;

    private final String desc;

    UserStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public Integer getValue() {
        return value;
    }

    public static UserStatus of(int value) {
        for (UserStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("用户状态不合法：" + value);
    }
}
