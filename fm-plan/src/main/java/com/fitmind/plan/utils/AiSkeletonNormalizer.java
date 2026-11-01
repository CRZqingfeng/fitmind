package com.fitmind.plan.utils;

import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.domain.dto.AiPreviewDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 骨架的钳制器 —— 把"模型可能胡说的 5 个字段"变成本服务敢用的值。
 *
 * <p><b>本类最容易写错的地方：把 clamp 做成"校验"。</b>
 * 校验失败就报错的话，模型输出一次格式不对（{@code weeklyFrequency=99}）
 * 用户就整页失败；而正确的产品语义是"照最接近的合法值给你排一份"。
 * 所以这里<b>只回落、不报错</b>，并把"改了什么"记进 {@code notes} 让人看得见。</p>
 *
 * <p>★ 为什么骨架必须由服务端复核：骨架来自模型，也可能被前端改动。
 * 它<b>不是</b>权限凭证，只是一组偏好参数 —— 所以只认枚举与范围，
 * 不做字符串拼接、更不做 SQL 拼接（全部走 MyBatis 参数绑定）。</p>
 */
public final class AiSkeletonNormalizer {

    public static final String DIVISION_THREE = "三分化";
    public static final String DIVISION_FIVE = "五分化";
    public static final String DIVISION_UPPER_LOWER = "上下肢分化";
    private static final List<String> DIVISIONS = List.of(DIVISION_THREE, DIVISION_FIVE, DIVISION_UPPER_LOWER);

    public static final String INTENSITY_LOW = "LOW";
    public static final String INTENSITY_MEDIUM = "MEDIUM";
    public static final String INTENSITY_HIGH = "HIGH";
    private static final List<String> INTENSITIES = List.of(INTENSITY_LOW, INTENSITY_MEDIUM, INTENSITY_HIGH);

    /** 周频缺失时的兜底：一周三练是"能坚持下来"的最大公约数 */
    private static final int DEFAULT_FREQUENCY = 3;
    /** 默认六餐分配（对齐参考 App：早/加/午/加/晚/加） */
    private static final double[] DEFAULT_RATIOS = {0.25, 0.05, 0.30, 0.05, 0.30, 0.05};
    private static final int[] DEFAULT_TYPES = {1, 4, 2, 4, 3, 4};

    private AiSkeletonNormalizer() {
    }

    /** 一餐：第几餐（mealType）+ 该类型里的第几顿（mealIndex，加餐靠它区分）+ 热量占比 */
    public record Slot(int mealType, int mealIndex, double ratio) {
    }

    /** 归一化后的骨架 */
    public record Skeleton(String division, int weeklyFrequency, String intensity,
                           List<Slot> mealSplit, List<String> notes) {
    }

    /**
     * 归一化。
     *
     * @param dto              模型/前端给的骨架（字段可空）
     * @param profileFrequency 用户档案里的"每周可训练次数"，可为空
     * @param goal             "增肌" / "减脂"（由目标体重推出来的）
     */
    public static Skeleton normalize(AiPreviewDTO dto, Integer profileFrequency, String goal) {
        List<String> notes = new ArrayList<>();

        int frequency = normalizeFrequency(dto.getWeeklyFrequency(), profileFrequency, notes);
        String division = normalizeDivision(dto.getDivision(), frequency, notes);
        String intensity = normalizeIntensity(dto.getIntensity(), goal, notes);
        List<Slot> slots = normalizeMealSplit(dto.getMealSplit(), notes);

        return new Skeleton(division, frequency, intensity, slots, notes);
    }

    private static int normalizeFrequency(Integer given, Integer profileFrequency, List<String> notes) {
        if (given != null) {
            // ★ 是 **clamp 到边界**（99 → 7），不是"非法就丢回默认 3"：
            //   模型说"一周 99 练"，用户的意思显然是"练得很勤"，给 7 比给 3 更接近他的期望；
            //   丢回 3 会让人以为自己的输入被无视了。
            int clamped = Math.max(1, Math.min(7, given));
            if (clamped != given) {
                notes.add("每周训练次数 " + given + " 超出 1~7，已按 " + clamped + " 处理");
            }
            return clamped;
        }
        if (profileFrequency != null && profileFrequency >= 1 && profileFrequency <= 7) {
            return profileFrequency;
        }
        notes.add("没有档案里的训练频次，按一周 " + DEFAULT_FREQUENCY + " 练生成");
        return DEFAULT_FREQUENCY;
    }

    private static String normalizeDivision(String given, int frequency, List<String> notes) {
        if (given != null && DIVISIONS.contains(given.trim())) {
            return given.trim();
        }
        String byFreq = frequency >= 5 ? DIVISION_FIVE
                : frequency == 4 ? DIVISION_UPPER_LOWER : DIVISION_THREE;
        notes.add("分化方式按一周 " + frequency + " 练定为「" + byFreq + "」");
        return byFreq;
    }

    private static String normalizeIntensity(String given, String goal, List<String> notes) {
        if (given != null) {
            String up = given.trim().toUpperCase();
            if (INTENSITIES.contains(up)) {
                return up;
            }
        }
        // 减脂起步偏轻（先建立习惯），增肌直接中等
        String byGoal = PlanConstants.GOAL_LOSS.equals(goal) ? INTENSITY_LOW : INTENSITY_MEDIUM;
        notes.add("训练强度未给出有效值，按目标定为 " + byGoal);
        return byGoal;
    }

    /**
     * 餐次比例归一化。
     *
     * <p>★ 合法性判据（缺一条就回落默认，而不是"修一修继续用"）：
     * 非空、1~8 项、{@code mealType ∈ [1,4]}、{@code ratio ∈ (0,1]}、
     * 总和 ∈ [0.99, 1.01]、同一 {@code mealType} 最多 3 次（加餐① ② ③）。</p>
     *
     * <p>为什么"同一类型最多 3 次"：`diet_plan_meal` 的唯一索引是
     * {@code (day, meal_type, meal_index)}，而 UI 只画得下 3 顿加餐；
     * 让模型给出 5 顿加餐，详情页会变成一长串"加餐④⑤"，不是用户能执行的东西。</p>
     */
    private static List<Slot> normalizeMealSplit(List<AiPreviewDTO.MealSplitDTO> split, List<String> notes) {
        if (split == null || split.isEmpty()) {
            notes.add("餐次比例未给出，按每日六餐（早/加/午/加/晚/加）分配");
            return defaults();
        }
        if (split.size() > 8) {
            notes.add("餐次数超过 8 项，已按默认六餐分配");
            return defaults();
        }
        double sum = 0;
        Map<Integer, Integer> indexCounter = new LinkedHashMap<>();
        List<Slot> slots = new ArrayList<>(split.size());
        for (AiPreviewDTO.MealSplitDTO s : split) {
            if (s == null || s.getMealType() == null || s.getMealType() < 1 || s.getMealType() > 4) {
                notes.add("餐次类型非法，已按默认六餐分配");
                return defaults();
            }
            double ratio = s.getRatio() == null ? 0 : s.getRatio();
            if (ratio <= 0 || ratio > 1) {
                notes.add("餐次比例不在 (0,1]，已按默认六餐分配");
                return defaults();
            }
            int idx = indexCounter.merge(s.getMealType(), 1, Integer::sum);
            if (idx > 3) {
                notes.add("同一餐次超过 3 顿，已按默认六餐分配");
                return defaults();
            }
            slots.add(new Slot(s.getMealType(), idx, ratio));
            sum += ratio;
        }
        if (sum < 0.99 || sum > 1.01) {
            notes.add(String.format("餐次比例合计 %.2f ≠ 1，已按默认六餐分配", sum));
            return defaults();
        }
        // 比例和可能略偏（0.995），按比例放大到正好 1，避免"每餐都少 0.5%"累积成全天少 10 千卡
        List<Slot> scaled = new ArrayList<>(slots.size());
        for (Slot s : slots) {
            scaled.add(new Slot(s.mealType(), s.mealIndex(), s.ratio() / sum));
        }
        return scaled;
    }

    private static List<Slot> defaults() {
        Map<Integer, Integer> counter = new LinkedHashMap<>();
        List<Slot> slots = new ArrayList<>(DEFAULT_RATIOS.length);
        for (int i = 0; i < DEFAULT_RATIOS.length; i++) {
            int idx = counter.merge(DEFAULT_TYPES[i], 1, Integer::sum);
            slots.add(new Slot(DEFAULT_TYPES[i], idx, DEFAULT_RATIOS[i]));
        }
        return slots;
    }
}
