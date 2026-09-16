package com.fitmind.exam.constants;

import lombok.Getter;

/**
 * 身体指标分级。
 *
 * <p>三组标准（BMI / 体脂 / 腰臀比）共用这一个枚举，用 {@code category} 区分，
 * 好处是"分级 + 文案"只有一处定义，不会出现"接口返回 3，前端以为是 4"这类错位。</p>
 *
 * <p><b>★ 三组标准里藏着三个容易抄错的领域知识</b>：</p>
 * <ol>
 *   <li><b>BMI 用中国标准</b>：超重切点是 <b>24</b>（不是 WHO 的 25），
 *       肥胖切点是 <b>28</b>。亚裔人群在较低 BMI 时体脂比例就更高，
 *       直接套 WHO 标准会低估风险。</li>
 *   <li><b>体脂率的正常区间男女不同</b>：男 15–20%，女 20–25%
 *       （女性生理性体脂更高，用同一张表会把正常女性判成偏高）。</li>
 *   <li><b>腰臀比（WHR）的阈值男女不同</b>：男 ≥0.9、女 ≥0.85 为中心性肥胖。
 *       WHR 反映的是脂肪分布（腹部堆积），是心血管风险的独立指标，
 *       BMI 正常但 WHR 偏高的人并不少见。</li>
 * </ol>
 */
@Getter
public enum BodyLevel {

    // ==================== BMI（★ 中国标准，非 WHO） ====================
    BMI_THIN(1, 1, "偏瘦"),
    BMI_NORMAL(1, 2, "正常"),
    BMI_OVERWEIGHT(1, 3, "超重"),
    BMI_OBESE(1, 4, "肥胖"),

    // ==================== 体脂率（★ 男女标准不同） ====================
    FAT_LOW(2, 1, "偏低"),
    FAT_ATHLETE(2, 2, "运动员水平"),
    FAT_NORMAL(2, 3, "正常"),
    FAT_HIGH(2, 4, "偏高"),
    FAT_OBESE(2, 5, "肥胖"),

    // ==================== 腰臀比 WHR（★ 男女阈值不同） ====================
    WHR_NORMAL(3, 1, "正常"),
    WHR_RISK(3, 2, "中心性肥胖（心血管风险）"),
    ;

    /** 1-BMI 2-体脂率 3-腰臀比 */
    private final int category;
    private final int value;
    private final String desc;

    BodyLevel(int category, int value, String desc) {
        this.category = category;
        this.value = value;
        this.desc = desc;
    }

    /** 按类别与值取枚举（渲染结论时用） */
    public static BodyLevel of(int category, Integer value) {
        if (value == null) {
            return null;
        }
        for (BodyLevel l : values()) {
            if (l.category == category && l.value == value) {
                return l;
            }
        }
        return null;
    }

    public static String descOf(int category, Integer value) {
        BodyLevel l = of(category, value);
        return l == null ? "未评估" : l.desc;
    }

    /** BMI 分级专用：按中国标准切分 */
    public static BodyLevel bmiLevel(double bmi) {
        if (bmi < 18.5) {
            return BMI_THIN;
        }
        if (bmi < 24) {
            return BMI_NORMAL;
        }
        if (bmi < 28) {
            return BMI_OVERWEIGHT;
        }
        return BMI_OBESE;
    }

    /**
     * 体脂率分级（★ 男女标准不同）。
     *
     * <p>男性：&lt;5 偏低 / 5–13 运动员 / 14–20 正常 / 21–25 偏高 / ≥26 肥胖<br>
     * 女性：&lt;13 偏低 / 14–20 运动员 / 21–28 正常 / 29–33 偏高 / ≥34 肥胖</p>
     *
     * <p>性别未知时取<b>男女区间的中间值</b>作为中性标准
     * （偏低 &lt;9 / 运动员 9–17 / 正常 17–24 / 偏高 25–29 / ≥30 肥胖），
     * 并在结论里注明 —— 绝不静默按某一性别算。</p>
     */
    public static BodyLevel fatLevel(Gender gender, double rate) {
        if (gender.isMale()) {
            if (rate < 5) return FAT_LOW;
            if (rate < 14) return FAT_ATHLETE;
            if (rate <= 20) return FAT_NORMAL;
            if (rate <= 25) return FAT_HIGH;
            return FAT_OBESE;
        }
        if (gender.isFemale()) {
            if (rate < 13) return FAT_LOW;
            if (rate < 21) return FAT_ATHLETE;
            if (rate <= 28) return FAT_NORMAL;
            if (rate <= 33) return FAT_HIGH;
            return FAT_OBESE;
        }
        // 未知：中性（男女区间的中间）
        if (rate < 9) return FAT_LOW;
        if (rate < 17) return FAT_ATHLETE;
        if (rate <= 24) return FAT_NORMAL;
        if (rate <= 29) return FAT_HIGH;
        return FAT_OBESE;
    }

    /**
     * 腰臀比分级（★ 男女阈值不同）。
     *
     * <p>男 ≥0.90、女 ≥0.85 即为中心性肥胖；性别未知时取中间值 0.875。</p>
     */
    public static BodyLevel whrLevel(Gender gender, double whr) {
        double threshold = gender.isMale() ? 0.90 : (gender.isFemale() ? 0.85 : 0.875);
        return whr >= threshold ? WHR_RISK : WHR_NORMAL;
    }
}
