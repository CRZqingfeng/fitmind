package com.fitmind.course.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 课程类型（健身场景重新定义）。
 *
 * <p>常见实现是「1-录播 2-直播 3-其他」，与健身无关，这里改成按内容形态划分。</p>
 */
@Getter
@AllArgsConstructor
public enum CourseType {

    /** 训练计划课：按天组织的完整计划，如「21 天增肌训练营」 */
    TRAINING_PLAN(1, "训练计划课"),

    /** 单动作教学：讲一个动作的标准做法，如「哑铃卧推怎么做」 */
    SINGLE_ACTION(2, "单动作教学"),

    /** 理论饮食课：健身知识、饮食搭配 */
    THEORY(3, "理论饮食课"),
    ;

    private final Integer value;

    private final String desc;
}
