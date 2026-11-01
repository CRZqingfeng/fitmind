package com.fitmind.learning.constants;

/**
 * 学习模块常量：状态、判定阈值、Redis key。
 *
 * <p>散落在代码里的魔法值是排查问题的天敌，全部收在这里。</p>
 */
public interface LearningConstants {

    // ==================== 课表状态 ====================

    int LESSON_NOT_BEGIN = 0;
    int LESSON_LEARNING = 1;
    int LESSON_FINISHED = 2;
    int LESSON_EXPIRED = 3;

    // ==================== 计划状态 ====================

    int PLAN_NO = 0;
    int PLAN_RUNNING = 1;

    // ==================== 看完判定 ====================

    /**
     * 看完判定：moment * 2 >= duration（播放过半算完成）。
     *
     * <p>沿用常见实现规则。训练视频要求完整示范，"跟练过半"即视为完成过该动作；
     * 做成常量便于后续调整（比如严格场景改为看完 90%）。</p>
     */
    int FINISH_RATIO_DENOMINATOR = 2;

    /** 每周训练频率的合法范围 */
    int WEEK_FREQ_MIN = 1;

    int WEEK_FREQ_MAX = 7;

    // ==================== Redis key ====================

    /** 进度缓存 key 前缀，实际 key = learning:record:{lessonId}:{sectionId} */
    String RECORD_CACHE_KEY = "learning:record:";

    /** 延迟任务 ZSet 的 key，member = {lessonId}:{sectionId}，score = 到期时间戳毫秒 */
    String RECORD_DELAY_KEY = "learning:record:delay";

    /**
     * 落库重试计数 key 前缀，实际 key = learning:record:retry:{lessonId}:{sectionId}。
     *
     * <p>落库失败要把任务放回 ZSet 重试，这个计数用来兜住"MySQL 长时间不可用"时的重试风暴。</p>
     */
    String RECORD_RETRY_KEY = "learning:record:retry:";

    // ==================== 积分榜分表 ====================

    /** 榜单逻辑表名（PO 上 @TableName 的值），动态表名拦截器据此做替换 */
    String POINTS_BOARD_TABLE = "points_board";

    /** 榜单分表前缀，物理表名 = 前缀 + 赛季id */
    String POINTS_BOARD_TABLE_PREFIX = "points_board_";

    // ==================== 签到 ====================

    /** 签到基础分 */
    int SIGN_BASE_POINTS = 1;

    /** 连续签到奖励的天数与积分：第 7/14/28 天 */
    int SIGN_REWARD_DAY_7 = 7;
    int SIGN_REWARD_DAY_14 = 14;
    int SIGN_REWARD_DAY_28 = 28;
    int SIGN_REWARD_POINTS_7 = 10;
    int SIGN_REWARD_POINTS_14 = 20;
    int SIGN_REWARD_POINTS_28 = 40;
}
