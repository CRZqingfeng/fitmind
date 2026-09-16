package com.fitmind.exam.util;

import com.fitmind.exam.constants.BodyLevel;
import com.fitmind.exam.constants.Gender;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;

/**
 * ★ 身体指标计算（本模块的核心算法）。
 *
 * <p><b>为什么全部写成静态纯函数</b>：
 * 这类"输入几个数、输出一个结论"的逻辑最容易出错，
 * 而错误又最难发现（算出来的数看着总是有道理的）。
 * 写成无状态、无依赖的纯函数之后，任何边界值都能被单独测到 ——
 * 验收脚本会对男女各测一遍、每个临界值各测一遍。</p>
 *
 * <p><b>★ 公式与标准的选择理由（面试能讲的部分）</b>：</p>
 * <ol>
 *   <li><b>BMI 分级用中国标准</b>：超重 24、肥胖 28（WHO 是 25 / 30）。
 *       亚裔人群在同等 BMI 下体脂比例更高，用 WHO 会低估风险。</li>
 *   <li><b>BMR 用 Mifflin-St Jeor 公式</b>：
 *       {@code 10×体重 + 6.25×身高 − 5×年龄 + s}（男 s=+5，女 s=−161）。
 *       它比更早的 Harris-Benedict 公式准确度更高，是目前的通行做法。</li>
 *   <li><b>TDEE = BMR × 活动系数</b>：系数由"每周训练次数"推出来
 *       （久坐 1.2 → 高强度 1.9）。这是减脂/增肌热量目标的基准。</li>
 * </ol>
 */
public final class BodyMetricsUtil {

    private BodyMetricsUtil() {
    }

    /** 一位小数 */
    private static final int SCALE_1 = 1;
    /** 两位小数（腰臀比） */
    private static final int SCALE_2 = 2;

    // ==================== BMI ====================

    /**
     * BMI = 体重(kg) / 身高(m)²。
     *
     * @throws IllegalArgumentException 身高非法时抛（而不是返回 0 ——
     *         0 会让后面的分级把它判成"偏瘦"，那是个看起来正常实则错误的结论）
     */
    public static BigDecimal bmi(BigDecimal heightCm, BigDecimal weightKg) {
        requirePositive(heightCm, "身高");
        requirePositive(weightKg, "体重");
        double h = heightCm.doubleValue() / 100;
        return BigDecimal.valueOf(weightKg.doubleValue() / (h * h))
                .setScale(SCALE_1, RoundingMode.HALF_UP);
    }

    public static int bmiLevel(BigDecimal bmi) {
        return BodyLevel.bmiLevel(bmi.doubleValue()).getValue();
    }

    // ==================== 腰臀比 WHR ====================

    /**
     * 腰臀比 = 腰围 / 臀围。
     *
     * <p>它和 BMI 看的是不同的事：BMI 反映总量，WHR 反映<b>脂肪分布</b>。
     * 腹部堆积（苹果型）比四肢堆积（梨型）的心血管风险高得多，
     * 所以"BMI 正常但 WHR 超标"的人并不少见 —— 这正是值得单独算一项的原因。</p>
     */
    public static BigDecimal whr(BigDecimal waistCm, BigDecimal hipCm) {
        requirePositive(waistCm, "腰围");
        requirePositive(hipCm, "臀围");
        return waistCm.divide(hipCm, SCALE_2, RoundingMode.HALF_UP);
    }

    public static int whrLevel(Gender gender, BigDecimal whr) {
        return BodyLevel.whrLevel(gender, whr.doubleValue()).getValue();
    }

    public static int fatLevel(Gender gender, BigDecimal fatRate) {
        return BodyLevel.fatLevel(gender, fatRate.doubleValue()).getValue();
    }

    // ==================== 基础代谢 BMR / 每日总消耗 TDEE ====================

    /**
     * 基础代谢（Mifflin-St Jeor）。
     *
     * <pre>
     *   男：10×体重(kg) + 6.25×身高(cm) − 5×年龄 + 5
     *   女：10×体重(kg) + 6.25×身高(cm) − 5×年龄 − 161
     * </pre>
     *
     * @param age 年龄；为 null 时返回 null（该项不参与评估，
     *            而不是拿一个默认年龄硬算 —— 年龄差 20 岁，BMR 差 100 kcal）
     */
    public static Integer bmr(Gender gender, BigDecimal weightKg, BigDecimal heightCm, Integer age) {
        if (age == null || age <= 0) {
            return null;
        }
        requirePositive(weightKg, "体重");
        requirePositive(heightCm, "身高");
        double base = 10 * weightKg.doubleValue() + 6.25 * heightCm.doubleValue() - 5 * age;
        double bmr = gender.isFemale() ? base - 161 : base + 5;
        return (int) Math.round(bmr);
    }

    /**
     * 每日总消耗 = BMR × 活动系数。
     *
     * <p>系数按"每周训练次数"取（比让用户自己选"轻度/中度/重度活动"更客观，
     * 因为训练次数已经在身体档案里了）：</p>
     * <pre>
     *   0 次 → 1.2（久坐）   1-2 次 → 1.375（轻度）
     *   3-4 次 → 1.55（中度） 5-6 次 → 1.725（较高）
     *   7 次及以上 → 1.9（高强度）
     * </pre>
     */
    public static Integer tdee(Integer bmr, Integer weeklyFrequency) {
        if (bmr == null) {
            return null;
        }
        int freq = weeklyFrequency == null ? 0 : weeklyFrequency;
        double factor;
        if (freq <= 0) {
            factor = 1.2;
        } else if (freq <= 2) {
            factor = 1.375;
        } else if (freq <= 4) {
            factor = 1.55;
        } else if (freq <= 6) {
            factor = 1.725;
        } else {
            factor = 1.9;
        }
        return (int) Math.round(bmr * factor);
    }

    // ==================== 身体成分 ====================

    /** 脂肪重量 = 体重 × 体脂率 */
    public static BigDecimal fatMass(BigDecimal weightKg, BigDecimal fatRate) {
        if (weightKg == null || fatRate == null) {
            return null;
        }
        return weightKg.multiply(fatRate)
                .divide(BigDecimal.valueOf(100), SCALE_1, RoundingMode.HALF_UP);
    }

    /** 去脂体重 = 体重 − 脂肪重量（肌肉、骨骼、水分等） */
    public static BigDecimal leanMass(BigDecimal weightKg, BigDecimal fatMass) {
        if (weightKg == null || fatMass == null) {
            return null;
        }
        return weightKg.subtract(fatMass).setScale(SCALE_1, RoundingMode.HALF_UP);
    }

    // ==================== 年龄 ====================

    /** 按生日算周岁；birthday 为空返回 null */
    public static Integer age(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        LocalDate now = LocalDate.now();
        if (birthday.isAfter(now)) {
            return null;
        }
        return Period.between(birthday, now).getYears();
    }

    // ==================== 综合评分 ====================

    /**
     * 综合评分（0–100）。
     *
     * <p><b>★ 关键设计：只按"已评估的项"加权换算成百分制</b>，
     * 而不是"缺项就当 0 分"。否则一个没填体脂率的人会被判成不及格 ——
     * 那是在惩罚"填得少"，而不是在评价身体状态。</p>
     *
     * <p>三项权重（满分 100）：BMI 40 / 体脂 30 / 腰臀比 30。</p>
     */
    public static int score(Integer bmiLevel, Integer fatLevel, Integer whrLevel) {
        int total = 0;
        int max = 0;
        if (bmiLevel != null) {
            total += bmiScore(bmiLevel);
            max += 40;
        }
        if (fatLevel != null) {
            total += fatScore(fatLevel);
            max += 30;
        }
        if (whrLevel != null) {
            total += whrScore(whrLevel);
            max += 30;
        }
        if (max == 0) {
            return 0;
        }
        // 按已评项等比放大到百分制
        return (int) Math.round(total * 100.0 / max);
    }

    /**
     * BMI 得分（满分 40）。
     *
     * <p>⚠️ 三组分级的"第 1 档"语义<b>并不相同</b> —— BMI 的 1 档是"偏瘦"（不好），
     * 体脂的 1 档是"偏低"（尚可），腰臀比的 1 档是"正常"（好）。
     * 所以必须<b>分别映射</b>，不能抽象成一个通用的"第几档得几分"，
     * 否则会把偏瘦判成优秀、把正常判成低分。</p>
     */
    private static int bmiScore(int level) {
        return switch (level) {
            case 2 -> 40;       // 正常
            case 3 -> 24;       // 超重
            case 1 -> 20;       // 偏瘦（同样是营养不良，不该给高分）
            default -> 10;      // 肥胖
        };
    }

    /** 体脂得分（满分 30）：运动员 / 正常都算好，偏低和偏高次之，肥胖最低 */
    private static int fatScore(int level) {
        return switch (level) {
            case 2, 3 -> 30;    // 运动员水平 / 正常
            case 1, 4 -> 18;    // 偏低 / 偏高
            default -> 8;       // 肥胖
        };
    }

    /** 腰臀比得分（满分 30）：只有两档，风险档几乎不给分 */
    private static int whrScore(int level) {
        return level == 1 ? 30 : 6;
    }

    // ==================== 参数校验 ====================

    private static void requirePositive(BigDecimal v, String name) {
        if (v == null || v.doubleValue() <= 0) {
            throw new IllegalArgumentException(name + "必须是正数");
        }
    }
}
