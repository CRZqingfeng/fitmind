package com.fitmind.course.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 课程状态。
 *
 * <p>状态流转：</p>
 * <pre>
 *   保存草稿 ──► 待上架(1) ──上架──► 已上架(2)
 *                  ▲                    │
 *                  └──────下架──────────┘
 * </pre>
 */
@Getter
@AllArgsConstructor
public enum CourseStatus {

    /** 待上架：草稿还没发布过 */
    NO_UP_SHELF(1, "待上架"),

    /** 已上架：线上可见 */
    SHELF(2, "已上架"),

    /** 下架：从线上撤下，数据回到草稿表 */
    DOWN_SHELF(3, "下架"),

    /** 已完结：不再开放 */
    FINISHED(4, "已完结"),
    ;

    private final Integer status;

    private final String desc;

    public static String desc(Integer status) {
        for (CourseStatus s : values()) {
            if (s.status.equals(status)) {
                return s.desc;
            }
        }
        return null;
    }

    public boolean equals(Integer status) {
        return this.status.equals(status);
    }
}
