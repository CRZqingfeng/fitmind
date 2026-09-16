-- ============================================================
-- fm_learning 库 · M3-3 增量（互动问答 + 笔记）
-- ------------------------------------------------------------
-- 表结构对照常见实现设计，两处主动差异：
--   1. 主键用 AUTO 自增（常见实现用雪花 ASSIGN_ID）—— 与 M3-1 的
--      learning_lesson / learning_record 保持一致，也避开 JS 精度问题
--   2. note 表补 `liked_times` —— 常见实现缺这个字段，导致「笔记点赞」
--      整条链路静默不存在（互动服务 声明了 NOTE 类型，但没人消费）
--
-- ⚠️ 顶层回答的 answer_id 固定存 0（不是 NULL）：
--    分页查询用 .eq(answerId, isQueryAnswer ? 0L : answerId) 一把梭，
--    避免 IS NULL 与 = 0 两套写法混用导致漏查。常见实现就是这个约定。
--
-- ⚠️ 不加 deleted 逻辑删除位：本项目其他表（learning_lesson 等）都是物理删除，
--    并且没有配 MyBatis-Plus 的 logic-delete-field —— 加了反而会让删除行为不一致。
--
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p < sql/fm_learning_m3_3.sql
-- ============================================================

USE `fm_learning`;

-- ==================== 互动提问 ====================
DROP TABLE IF EXISTS `interaction_question`;
CREATE TABLE `interaction_question`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`            VARCHAR(60)  NOT NULL COMMENT '标题',
    `description`      VARCHAR(500) NOT NULL COMMENT '问题描述',
    `course_id`        BIGINT       NOT NULL COMMENT '课程id',
    `chapter_id`       BIGINT       NOT NULL COMMENT '训练日id（目录一级）',
    `section_id`       BIGINT       NOT NULL COMMENT '训练动作id（目录二级）',
    `user_id`          BIGINT       NOT NULL COMMENT '提问学员id',
    `latest_answer_id` BIGINT       NULL COMMENT '最新一条回答id',
    `answer_times`     INT          NOT NULL DEFAULT 0 COMMENT '回答数量',
    `anonymity`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否匿名',
    `hidden`           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否被隐藏（管理端）',
    `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '管理端状态：0-未查看 1-已查看',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_course_id` (`course_id`),
    KEY `idx_section_id` (`section_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '互动提问';

-- ==================== 回答与评论 ====================
DROP TABLE IF EXISTS `interaction_reply`;
CREATE TABLE `interaction_reply`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_id`     BIGINT       NOT NULL COMMENT '问题id',
    `answer_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '上级回答id；顶层回答固定为 0',
    `user_id`         BIGINT       NOT NULL COMMENT '回答者id',
    `content`         VARCHAR(500) NOT NULL COMMENT '回答/评论内容',
    `target_user_id`  BIGINT       NULL COMMENT '回复的目标用户id',
    `target_reply_id` BIGINT       NULL COMMENT '回复的目标回复id',
    `reply_times`     INT          NOT NULL DEFAULT 0 COMMENT '下属评论数量（原子累加）',
    `liked_times`     INT          NOT NULL DEFAULT 0 COMMENT '点赞数（由 fm-remark 异步同步，覆盖更新）',
    `hidden`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否被隐藏（管理端）',
    `anonymity`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否匿名',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_question_id` (`question_id`),
    KEY `idx_answer_id` (`answer_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '互动回答与评论';

-- ==================== 笔记 ====================
DROP TABLE IF EXISTS `note`;
CREATE TABLE `note`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          BIGINT        NOT NULL COMMENT '持有者id（采集后是采集者）',
    `author_id`        BIGINT        NOT NULL COMMENT '原作者id',
    `course_id`        BIGINT        NOT NULL COMMENT '课程id',
    `chapter_id`       BIGINT        NOT NULL COMMENT '训练日id',
    `section_id`       BIGINT        NOT NULL COMMENT '训练动作id',
    `note_moment`      INT           NOT NULL COMMENT '记录笔记时的视频播放秒数（点笔记跳回该时刻）',
    `content`          VARCHAR(2000) NOT NULL COMMENT '笔记内容',
    `is_private`       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否隐私笔记',
    `hidden`           TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否被折叠（管理端）',
    `hidden_reason`    VARCHAR(200)  NULL COMMENT '被折叠原因',
    `gathered_note_id` BIGINT        NULL COMMENT '被采集的原笔记id',
    `is_gathered`      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否采集他人的笔记',
    `liked_times`      INT           NOT NULL DEFAULT 0 COMMENT '点赞数（★ 常见实现缺此字段）',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_course_id` (`course_id`),
    KEY `idx_section_id` (`section_id`),
    KEY `idx_gathered_note_id` (`gathered_note_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '学习笔记';
