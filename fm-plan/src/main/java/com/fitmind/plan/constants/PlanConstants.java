package com.fitmind.plan.constants;

import java.util.List;

/**
 * 计划模块常量：状态、默认值、字典。
 *
 * <p>为什么字典也放这里，而不是让前端硬编码：前端的 15 个左侧分类、8 个器械 chips
 * 如果写死在 .vue 里，改一个分类就要发一次前端；更要紧的是**前后端枚举会漂**——
 * 后端存的是"固定器械"，前端写的是"器械"，筛选永远查不到东西。</p>
 *
 * <p>★ 注意 {@link #FILTER_CATEGORIES} 只有 3 项（热身 / 拉伸 / 有氧）：
 * "力量"类动作在界面上是按**部位**切的（胸 / 背 / 腿…），
 * 所以左侧栏 = 全部 + 热身 + 拉伸 + 有氧 + 11 个部位 = 15 项，正好对上设计稿。
 * 数据库里 category 仍然有"力量"这个值，它不参与左侧栏筛选。</p>
 */
public interface PlanConstants {

    // ==================== plan.source ====================
    /** 官方计划（owner_id = 0，任何用户可执行、不可编辑） */
    int SOURCE_OFFICIAL = 0;
    int SOURCE_USER = 1;
    int SOURCE_AI = 2;

    // ==================== plan.status ====================
    int PLAN_DRAFT = 0;
    int PLAN_PUBLISHED = 1;
    int PLAN_OFFLINE = 2;

    // ==================== user_plan.status ====================
    int USER_PLAN_RUNNING = 0;
    int USER_PLAN_FINISHED = 1;
    int USER_PLAN_GIVE_UP = 2;

    // ==================== workout_session.status ====================
    int SESSION_RUNNING = 0;
    int SESSION_FINISHED = 1;
    int SESSION_GIVE_UP = 2;

    // ==================== plan_day_item.group_type ====================
    int GROUP_FORMAL = 0;
    int GROUP_WARMUP = 1;
    int GROUP_STRETCH = 2;

    // ==================== 今日训练三态（前端三张脸，别合成一个 boolean） ====================
    /** 没有正在执行的计划 */
    int TODAY_NO_PLAN = 0;
    /** 有计划，但今天没有安排训练日 */
    int TODAY_REST = 1;
    /** 有计划且今天要练 */
    int TODAY_TRAIN = 2;

    // ==================== 默认值（★ 由后端给，前端不许拼字符串） ====================
    String DEFAULT_PLAN_TITLE = "未命名计划";
    String DEFAULT_DAY_NAME = "未命名训练日";

    /** 往训练日批量加动作时的默认安排：4 组 × 8 次，组休 60 秒 */
    int DEFAULT_SETS = 4;
    int DEFAULT_REPS = 8;
    int DEFAULT_REST_SEC = 60;

    /** 次数型动作的单组耗时估算系数：1 次 ≈ 3 秒（口径见 M11-2 方案 §4.2） */
    int SECONDS_PER_REP = 3;

    // ==================== 字典 ====================
    /** 星期一 = 1 ... 星期日 = 7（与 plan_day.weekday 对齐） */
    List<String> WEEKDAY_LABELS = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");

    /** 左侧栏的"分类"（不含"力量"，力量类动作按部位切，见类注释） */
    List<String> FILTER_CATEGORIES = List.of("热身", "拉伸", "有氧");

    /** 数据库里 category 的全集 */
    List<String> ALL_CATEGORIES = List.of("热身", "拉伸", "有氧", "力量");

    /** 11 个部位（+ "其他" 兜数据集里 adductors/neck/abductors 那 29 条，见 sql/fm_plan.sql 注释） */
    List<String> BODY_PARTS = List.of("胸", "背", "腿", "肩", "二头", "三头", "小腿", "前臂", "臀部", "腹部", "斜方肌");

    /**
     * 8 类器械。
     * ⚠️ 开源数据集只有 machine / cable 这种粗粒度值，**没有"史密斯""悍马机"** ——
     * 这两类 P0 是空的（点了没结果），不是 bug；等 P3 全量导入时按器械子类补。
     */
    List<String> EQUIPMENTS = List.of("徒手", "哑铃", "杠铃", "史密斯", "悍马机", "固定器械", "弹力带", "其他");

    /** ★ 目标字符串抽成常量：P2 的目标推算、骨架回落都要判断"是增肌还是减脂"，
     *  散落各处的字面量迟早会有一次写成"减脂 "（带空格）而静默不匹配 */
    String GOAL_GAIN = "增肌";
    String GOAL_LOSS = "减脂";

    List<String> GOALS = List.of(GOAL_GAIN, GOAL_LOSS);

    /** 难度标签，下标即 difficulty 值 */
    List<String> DIFFICULTY_LABELS = List.of("新手", "进阶", "专业");

    /** 组类型标签，下标即 group_type 值 */
    List<String> GROUP_TYPE_LABELS = List.of("正式组", "热身组", "拉伸组");

    // ==================== 工具方法 ====================

    /** 1~7 → "周一"..."周日"；越界返回 "未指定"（宁可显示"未指定"，也不要抛异常让整页崩） */
    static String weekdayLabel(Integer weekday) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            return "未指定";
        }
        return WEEKDAY_LABELS.get(weekday - 1);
    }

    static String difficultyLabel(Integer difficulty) {
        if (difficulty == null || difficulty < 0 || difficulty >= DIFFICULTY_LABELS.size()) {
            return DIFFICULTY_LABELS.get(0);
        }
        return DIFFICULTY_LABELS.get(difficulty);
    }

    static String groupTypeLabel(Integer groupType) {
        if (groupType == null || groupType < 0 || groupType >= GROUP_TYPE_LABELS.size()) {
            return GROUP_TYPE_LABELS.get(GROUP_FORMAL);
        }
        return GROUP_TYPE_LABELS.get(groupType);
    }
}
