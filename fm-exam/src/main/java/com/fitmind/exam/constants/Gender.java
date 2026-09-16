package com.fitmind.exam.constants;

import lombok.Getter;

/**
 * 性别。
 *
 * <p>取值与 {@code fm_user.user_detail.gender} 保持一致（<b>0-男 1-女</b>），
 * 另外定义了 {@link #UNKNOWN}，用于"档案里没填"的情况。</p>
 *
 * <p><b>★ 为什么性别在这个模块里是核心参数而不是可选信息</b>：
 * 三项评估标准的取值都依赖它 ——</p>
 * <ul>
 *   <li>体脂率的正常区间：男 15–20%，女 20–25%</li>
 *   <li>腰臀比（WHR）的中心性肥胖阈值：男 ≥0.9，女 ≥0.85</li>
 *   <li>基础代谢（BMR）公式的常数项：男 +5，女 −161</li>
 * </ul>
 *
 * <p>所以"性别未知"不能简单地当成男性算（那样会给出一个看似精确实则错误的结论），
 * 见 {@code BodyMetricsUtil} 的处理方式。</p>
 */
@Getter
public enum Gender {

    MALE(0, "男"),
    FEMALE(1, "女"),
    UNKNOWN(2, "未知"),
    ;

    private final int value;
    private final String desc;

    Gender(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public static Gender of(Integer value) {
        if (value == null) {
            return UNKNOWN;
        }
        for (Gender g : values()) {
            if (g.value == value) {
                return g;
            }
        }
        return UNKNOWN;
    }

    public boolean isMale() {
        return this == MALE;
    }

    public boolean isFemale() {
        return this == FEMALE;
    }

    /** 是否已知（未知时评估要用"中性标准"并在结论里注明） */
    public boolean known() {
        return this != UNKNOWN;
    }
}
