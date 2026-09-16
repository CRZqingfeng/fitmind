-- ============================================================
-- fm_learning：学习服务数据库（M3-1）
-- ------------------------------------------------------------
-- 课表与学习记录。
-- 学习进度的"高频上报合并"不走表，走 Redis（见 LearningRecordDelayTaskHandler），
-- 表里只存"断点值"和"完成态"。
-- ============================================================
CREATE DATABASE IF NOT EXISTS `fm_learning`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `fm_learning`;

-- ==================== 学习课表 ====================
-- 一人一课一行（唯一索引 uk_user_course）。
-- status 流转：0-未学习 →（首次学习）1-学习中 →（全部动作完成）2-已学完 /（过期任务）3-已过期
CREATE TABLE IF NOT EXISTS `learning_lesson` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`           BIGINT      NOT NULL COMMENT '学员id，关联 fm_user.user.id',
    `course_id`         BIGINT      NOT NULL COMMENT '课程id，关联 fm_course.course.id',
    `status`            TINYINT     NOT NULL DEFAULT 0 COMMENT '课程状态：0-未学习 1-学习中 2-已学完 3-已过期',
    `week_freq`         TINYINT              DEFAULT NULL COMMENT '每周训练频率（学习计划），null=未设置',
    `plan_status`       TINYINT     NOT NULL DEFAULT 0 COMMENT '计划状态：0-没有计划 1-计划进行中',
    `learned_sections`  INT         NOT NULL DEFAULT 0 COMMENT '已完成的训练动作数（原子累加）',
    `latest_section_id` BIGINT               DEFAULT NULL COMMENT '最近学习的动作id（断点续播入口）',
    `latest_learn_time` DATETIME             DEFAULT NULL COMMENT '最近学习时间（「继续训练」排序用）',
    `create_time`       DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '报名时间',
    `expire_time`       DATETIME             DEFAULT NULL COMMENT '到期时间 = 报名时间 + 课程 validDuration 天',
    `update_time`       DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_course` (`user_id`, `course_id`),
    KEY `idx_expire` (`expire_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学习课表';

-- ==================== 学习记录（动作级进度） ====================
-- 一个动作一条记录（唯一索引 uk_lesson_section），重复上报走 UPDATE。
-- moment = 断点秒数；看满一半（moment*2 >= duration）置 finished=1 并记 finish_time。
CREATE TABLE IF NOT EXISTS `learning_record` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `lesson_id`   BIGINT      NOT NULL COMMENT '课表id',
    `section_id`  BIGINT      NOT NULL COMMENT '训练动作id，关联 fm_course.course_catalogue.id',
    `user_id`     BIGINT      NOT NULL COMMENT '学员id',
    `moment`      INT         NOT NULL DEFAULT 0 COMMENT '上次播放进度，单位秒',
    `finished`    TINYINT     NOT NULL DEFAULT 0 COMMENT '是否完成：0-否 1-是',
    `create_time` DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `finish_time` DATETIME             DEFAULT NULL COMMENT '完成时间（M3-2 周统计的依据）',
    `update_time` DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lesson_section` (`lesson_id`, `section_id`),
    KEY `idx_user_finish` (`user_id`, `finished`, `finish_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学习记录（动作级进度）';
