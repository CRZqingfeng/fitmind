package com.fitmind.learning.enums;

import lombok.Getter;

/**
 * 积分类型。
 *
 * <p>{@code maxPoints} 是该类型的<b>每日上限</b>（0 表示不限）——
 * 防刷的核心：光靠"完成动作 +10"没有上限的话，刷视频就能刷分。</p>
 *
 * <p>砍掉了常见实现的 {@code COMMENT(课程评价)}：评价属于 fm-remark（后续里程碑）。</p>
 */
@Getter
public enum PointsRecordType {

    LEARNING(1, "课程学习", 50),
    SIGN(2, "每日签到", 0),
    QA(3, "课程问答", 20),
    NOTE(4, "课程笔记", 20),
    /**
     * 动作小测（M2-2c）。
     *
     * <p>答对一题 +2，每日上限 20 —— 也就是一天最多靠小测拿 10 次分。
     * 上限必须有：题目分值由教练设置，没有上限的话出一道 100 分的题就能刷满榜。</p>
     *
     * <p>幂等 bizId 是 {@code courseId:cataId:subjectId}（按<b>题</b>），
     * 所以同一个动作答对 3 道题能拿 3 份分，同一道题重复答对只算一次。</p>
     */
    PRACTICE(5, "动作小测", 20),
    ;

    /** 数据库存储值（用 int 而不是枚举名，保持与前端/DB 的约定简单） */
    private final int value;

    private final String desc;

    /** 每日上限，0 = 不限 */
    private final int maxPoints;

    PointsRecordType(int value, String desc, int maxPoints) {
        this.value = value;
        this.desc = desc;
        this.maxPoints = maxPoints;
    }

    public static PointsRecordType of(Integer value) {
        if (value == null) {
            return null;
        }
        for (PointsRecordType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}
