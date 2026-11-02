package com.fitmind.plan.constants;

/**
 * 计划服务错误提示。
 *
 * <p>写法要求（不是洁癖，是体验）：**每条都要能指导用户下一步做什么**。
 * "参数错误"/"操作失败"这种提示等于把排查成本丢给用户；
 * 而"「周一练胸」还没有动作，先加一个动作再执行"用户看一眼就知道点哪。</p>
 *
 * <p>占位符统一用 {@code %s}，由 Service 用 {@link String#format} 填训练日名。</p>
 */
public class PlanErrorInfo {

    private PlanErrorInfo() {
    }

    public static class Msg {

        private Msg() {
        }

        // ==================== 计划 ====================

        public static final String PLAN_NOT_EXISTS = "计划不存在，它可能已经被删除了";

        public static final String PLAN_NOT_ONLINE = "这个计划已下架，换一个推荐计划试试";

        public static final String PLAN_NOT_OWNER = "只能编辑自己创建的计划；官方计划请先「执行本计划」再复制一份";

        public static final String PLAN_NO_DAY = "这个计划还没有训练日，先添加训练日再执行";

        /** 参数：训练日名 */
        public static final String DAY_NO_ITEM = "「%s」还没有动作，先给它加一个动作再执行";

        // ==================== P2：AI 定制 ====================
        public static final String AI_NEED_PROFILE = "先记录一次体重，我才能算出你的热量预算和达成时间";
        public static final String AI_PREVIEW_EXPIRED = "预览已过期，请重新生成";
        public static final String AI_PREVIEW_NOT_MINE = "这不是你的预览";

        // ==================== 训练日 ====================

        public static final String DAY_NOT_EXISTS = "训练日不存在，它可能已经被删除了";

        public static final String WEEKDAYS_EMPTY = "至少选一天（周一~周日）";

        public static final String WEEKDAY_ILLEGAL = "星期只能是 1~7（周一=1，周日=7）";

        public static final String DAY_NAME_TOO_LONG = "训练日名字最多 32 个字";

        public static final String DAY_ALREADY_EXISTS = "这几天已经有训练日了，换几天试试";

        /**
         * 删训练日时，这一天已经练过 —— 拒绝删除。
         *
         * <p>为什么不是"删掉但保留历史"：{@code workout_session.plan_day_id} 指向训练日，
         * 删了它历史记录就成了孤儿，首页"累计训练"、详情页的训练日名、将来按部位统计
         * 全得加 null 分支。数据一旦失去归属，后面每个读的地方都要兜一次。</p>
         *
         * <p>参数：训练日名、已练次数</p>
         */
        public static final String DAY_HAS_SESSION = "「%s」已经练过 %d 次，删掉会让这些记录失去归属；"
                + "想换星期几可以另建一个训练日，练过的这一天留着";

        // ==================== 动作 ====================

        public static final String ITEM_NOT_EXISTS = "这个动作已经不在训练日里了，刷新一下看看";

        public static final String ITEM_NOT_IN_DAY = "这个动作不属于该训练日，刷新一下再试";

        public static final String EXERCISE_IDS_EMPTY = "至少选一个动作";

        public static final String EXERCISE_NOT_EXISTS = "有动作不存在（可能已下架），去动作库重新选一次";

        // ==================== 执行 / 打卡 ====================

        public static final String USER_PLAN_NOT_EXISTS = "还没有执行这个计划，先在计划详情点「执行本计划」";

        /** 一个文案覆盖"已完成"和"已放弃"：对用户来说下一步动作是同一个（重新执行） */
        public static final String USER_PLAN_NOT_RUNNING = "这个计划已经结束了，重新「执行本计划」就能接着练";

        /**
         * 已经练完的计划不让「停止执行」。
         *
         * <p>为什么不允许：允许的话"已完成"会被改写成"已放弃" —— 两个语义落到同一个字段，
         * 进度、徽标、统计全都要重新解释一遍。练完了就是练完了，没有"停下来"这个动作。</p>
         */
        public static final String USER_PLAN_FINISHED_CANNOT_ABANDON = "这个计划已经全部练完了，没有要停的执行；想练新计划直接去定制或挑一个";

        public static final String SESSION_NOT_EXISTS = "找不到这次训练，它可能已被清理";

        public static final String SESSION_NOT_OWNER = "这不是你的训练记录";

        public static final String SESSION_FINISHED = "这次训练已经完成了；想再练一次请回首页重新开始";

        public static final String DAY_NOT_IN_RUNNING_PLAN = "这个训练日不属于你正在执行的计划，回首页看看今天的安排";

        public static final String SET_NO_ILLEGAL = "组号从 1 开始";

        public static final String SET_ITEM_ILLEGAL = "记录的这一组不属于本次训练的训练日";

        /** 取消打勾时，这一组根本没打过勾 */
        public static final String SET_NOT_EXISTS = "这一组还没打过勾，刷新一下看看";

        public static final String LOGIN_REQUIRED = "登录信息已失效，请重新登录";
    }
}
