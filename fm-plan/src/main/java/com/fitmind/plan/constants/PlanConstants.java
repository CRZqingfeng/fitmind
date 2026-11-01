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
 * 所以左侧栏 = 全部 + 分类 + 部位。数据库里 category 仍然有"力量"这个值，它不参与左侧栏筛选。</p>
 *
 * <p>★★ <b>P3（2026-11-01）之后这些 List 只负责排序</b>：动作字典已改成动态 DISTINCT，
 * 库里没有值的枚举会自动消失。理由见 "字典" 那一节的注释。</p>
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
    // ★★ 2026-11-01（P3 数据全量导入）起，下面三个 List **不再是"内容来源"，只是"排序参考"**：
    //    动作字典改成 `SELECT DISTINCT ... WHERE status=1`（与食物字典同一口径），
    //    库里没有值的枚举会自动消失（热身 0 条、史密斯/悍马机 0 条 → 左侧栏不再有死入口）。
    //    ⚠️ 所以：**改这三行加不了分类**，要去改数据 / 改导入映射表（scripts/import-exercise-db.py）。
    //    这三行现在唯一的作用是"让左侧栏顺序稳定"（先按这里的顺序，未列出的追加在后）。

    /** 分类常量：字符串散落各处迟早写错（"力量 "带空格就静默不匹配） */
    String CATEGORY_STRENGTH = "力量";
    String CATEGORY_STRETCH = "拉伸";
    String CATEGORY_CARDIO = "有氧";

    /** 星期一 = 1 ... 星期日 = 7（与 plan_day.weekday 对齐） */
    List<String> WEEKDAY_LABELS = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");

    /** 左侧栏的"分类"（不含"力量"，力量类动作按部位切，见类注释）—— 仅用于**排序** */
    List<String> FILTER_CATEGORIES = List.of("热身", "拉伸", "有氧");

    /** 数据库里 category 的全集 —— 仅用于校验/文档，不再喂给字典 */
    List<String> ALL_CATEGORIES = List.of("热身", "拉伸", "有氧", "力量");

    /** 12 个部位（★ 含"其他"：数据集里 neck 那 8 条必须有着落，否则它们会变成孤儿）—— 仅用于**排序** */
    List<String> BODY_PARTS = List.of("胸", "背", "腿", "肩", "二头", "三头", "小腿", "前臂", "臀部", "腹部", "斜方肌", "其他");

    /**
     * 6 类器械 —— 仅用于**排序**。
     * ★ 数据库里没有"史密斯""悍马机"：开源动作数据集的 equipment 只有 13 个英文值，
     *   根本没有这两类机器（machine/cable 已并入"固定器械"）。
     *   旧计划说"P3 全量导入时按子类补"——**已被实测推翻，补不了，也不许编**；
     *   正解是字典动态化，让这两个空枚举自己消失（已落地）。
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
