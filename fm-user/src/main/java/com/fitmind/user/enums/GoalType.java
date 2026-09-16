package com.fitmind.user.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 训练目标。
 */
@Getter
public enum GoalType implements IEnum<Integer> {

    /** 增肌 */
    BUILD_MUSCLE(1, "增肌"),

    /** 减脂 */
    LOSE_FAT(2, "减脂"),

    /** 塑形 */
    SHAPE(3, "塑形"),

    /** 保持 */
    KEEP_FIT(4, "保持"),
    ;

    private final int value;

    private final String desc;

    GoalType(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public Integer getValue() {
        return value;
    }

    public static GoalType of(int value) {
        for (GoalType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("训练目标不合法：" + value);
    }
}
