package com.fitmind.plan.constants;

/**
 * 饮食模块错误提示。
 *
 * <p>写法要求与 {@link PlanErrorInfo} 一致：**每条都要能指导用户下一步**。
 * "参数错误"等于把排查成本丢给用户。</p>
 */
public class DietErrorInfo {

    private DietErrorInfo() {
    }

    public static class Msg {

        private Msg() {
        }

        // ==================== 食物 ====================

        public static final String FOOD_NOT_EXISTS = "这个食物不存在，可能已被下架，回饮食库重新选一个";

        public static final String FOOD_NOT_OWNER = "只能删除自己添加的食物";

        public static final String FOOD_NAME_DUPLICATE = "已经有同名食物了，换个名字或者直接在搜索里找它";

        // ==================== 饮食记录 ====================

        public static final String LOG_NOT_EXISTS = "这条记录已经不在了，刷新一下看看";

        public static final String LOG_NOT_OWNER = "这不是你的饮食记录";

        public static final String MEAL_TYPE_ILLEGAL = "餐次只能是 1早餐 / 2午餐 / 3晚餐 / 4加餐";

        public static final String AMOUNT_ILLEGAL = "份量要大于 0";

        public static final String UNIT_ILLEGAL = "这个食物不支持该单位，刷新饮食库再试";

        // ==================== 运动 ====================

        public static final String SPORT_NOT_EXISTS = "这个运动项目不存在，重新选一个";

        public static final String SPORT_DURATION_ILLEGAL = "运动时长要在 1~600 分钟之间";

        public static final String SPORT_LOG_NOT_EXISTS = "这条运动记录已经不在了，刷新一下看看";

        public static final String SPORT_LOG_NOT_OWNER = "这不是你的运动记录";

        // ==================== 体征 / 预算 ====================

        public static final String WEIGHT_ILLEGAL = "体重要在 20~300 公斤之间";

        public static final String HEIGHT_ILLEGAL = "身高要在 100~250 厘米之间";

        /** 参数：无 */
        public static final String HEIGHT_REQUIRED = "第一次记录体重需要填身高（用来算 BMI 和热量预算）";

        public static final String PROFILE_NEEDED = "先记录一次体重，才有每日热量预算";

        // ==================== 饮食计划 ====================

        public static final String DIET_PLAN_NOT_EXISTS = "饮食计划不存在，它可能已经下架了";

        public static final String DIET_PLAN_DAY_ILLEGAL = "这个计划没有第 %s 天，换一天看看";
    }
}
