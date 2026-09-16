package com.fitmind.learning.enums;

import lombok.Getter;

/**
 * 管理端问题状态。
 *
 * <p>存 Integer 而不是用 MyBatis-Plus 的枚举映射：本项目其他表的状态字段
 * （课表 status、积分类型 type）都是 Integer，保持一致、少一层配置。</p>
 */
@Getter
public enum QuestionStatus {

    /** 未查看：新提问、或学生刚补了新回答，需要管理员过一遍 */
    UN_CHECK(0, "未查看"),

    /** 已查看：管理员已处理 */
    CHECKED(1, "已查看"),
    ;

    private final int value;

    private final String desc;

    QuestionStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
