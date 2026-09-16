package com.fitmind.user.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 训练水平。
 *
 * <p>AI 教练推荐训练课程时会参考该字段，避免给零基础用户推荐高强度课表。</p>
 */
@Getter
public enum TrainingLevel implements IEnum<Integer> {

    /** 零基础 */
    BEGINNER(1, "零基础"),

    /** 初级 */
    NOVICE(2, "初级"),

    /** 中级 */
    INTERMEDIATE(3, "中级"),

    /** 高级 */
    ADVANCED(4, "高级"),
    ;

    private final int value;

    private final String desc;

    TrainingLevel(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public Integer getValue() {
        return value;
    }

    public static TrainingLevel of(int value) {
        for (TrainingLevel level : values()) {
            if (level.value == value) {
                return level;
            }
        }
        throw new IllegalArgumentException("训练水平不合法：" + value);
    }
}
