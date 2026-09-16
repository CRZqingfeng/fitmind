package com.fitmind.search.constants;

/**
 * ES 相关常量。
 *
 * <p>字段名必须用常量而不是到处写字符串——用 RestHighLevelClient 时
 * mapping、查询条件、结果解析三处的字段名是<b>分别手写</b>的，
 * 拼错一个字母不会报错，只会查询无结果（静默失效）。</p>
 */
public interface EsConstants {

    /** 默认索引名（可通过配置覆盖） */
    String DEFAULT_COURSE_INDEX = "fitmind_course";

    /** IK 分词器：索引时用（切得细，召回全） */
    String ANALYZER_INDEX = "ik_max_word";

    /** IK 分词器：检索时用（切得粗，精度高） */
    String ANALYZER_SEARCH = "ik_smart";

    /** 索引的分片数与副本数：单节点环境副本必须为 0，否则集群健康是 yellow */
    int SHARDS = 1;

    int REPLICAS = 0;

    // ==================== 字段名 ====================

    interface Field {
        String ID = "id";
        String NAME = "name";
        String CATEGORY_ID_LV1 = "categoryIdLv1";
        String CATEGORY_ID_LV2 = "categoryIdLv2";
        String CATEGORY_ID_LV3 = "categoryIdLv3";
        String CATEGORY_PATH = "categoryPath";
        String FREE = "free";
        String TYPE = "type";
        String PRICE = "price";
        String SCORE = "score";
        String SOLD = "sold";
        String TEACHER_IDS = "teacherIds";
        String TEACHER_NAMES = "teacherNames";
        String SECTIONS = "sections";
        String COVER_URL = "coverUrl";
        String PUBLISH_TIME = "publishTime";
        String UPDATE_TIME = "updateTime";

        /** ★ 健身化：训练目标 */
        String GOAL_TYPE = "goalType";
        /** ★ 健身化：适用水平 */
        String TRAINING_LEVEL = "trainingLevel";
        /** ★ 健身化：所需器械（keyword，可精确筛选「徒手/哑铃/单杠」） */
        String EQUIPMENT = "equipment";
        /** ★ 健身化：训练周期总天数 */
        String TOTAL_DAYS = "totalDays";
        /** ★ 健身化：视频总时长（秒） */
        String MEDIA_DURATION = "mediaDuration";
        /** ★ 健身化：训练日名称 */
        String TRAINING_DAYS = "trainingDays";
        /** ★ 健身化：训练动作名称（让用户能直接搜「哑铃卧推」找课程） */
        String ACTION_NAMES = "actionNames";
    }
}
