-- ============================================================
-- M5：数据中心（fm-data）
--
-- 设计主线：**数据来自 MQ 事件，不直连业务库**。
-- 所以这里的表只有"统计结果"，没有任何业务实体。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `fm_data`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `fm_data`;

-- ==================== 1. 事件去重表（★ 全模块最重要的一张） ====================
-- MQ 是 at-least-once：同一条消息可能被投递多次（网络抖动、消费者重启、
-- 手动重投都会触发）。如果直接 INCR 计数，成交额就会虚高。
--
-- 所以先插这张表插成功（唯一索引通过）才去计数 ——
-- 与站内信的 uk_user_source、积分的 uk_user_type_biz 是同一套思路：
-- **把幂等交给数据库约束，而不是靠"应该不会重复吧"**。
CREATE TABLE IF NOT EXISTS `data_event_record`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_type`  VARCHAR(32)  NOT NULL COMMENT '事件类型（见 DataEventType）',
    `biz_id`      VARCHAR(64)  NOT NULL COMMENT '业务唯一标识（同一事件类型内不重复）',
    `user_id`     BIGINT                DEFAULT NULL COMMENT '关联用户（可为空，如订单事件）',
    `stat_date`   INT          NOT NULL COMMENT '归属日期 yyyyMMdd（★ 按业务发生时间，不是消息到达时间）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- ★★★ 幂等的唯一保证
    UNIQUE KEY `uk_event_biz` (`event_type`, `biz_id`),
    KEY `idx_stat_date` (`stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '数据事件去重表（防止 MQ 重复投递导致重复计数）';

-- ==================== 2. 每日全局汇总 ====================
-- 由定时任务从 Redis 落库。Redis 负责"高频累加"，MySQL 负责"可查历史"。
-- 与 M3-2 积分榜（Redis ZSet 实时 + 分表落库）是同一套模式。
CREATE TABLE IF NOT EXISTS `data_daily`
(
    `id`             BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stat_date`      INT     NOT NULL COMMENT '统计日期 yyyyMMdd',
    `sign_in_count`  INT     NOT NULL DEFAULT 0 COMMENT '签到次数',
    `learn_count`    INT     NOT NULL DEFAULT 0 COMMENT '完成训练次数',
    `practice_count` INT     NOT NULL DEFAULT 0 COMMENT '小测答对次数',
    `order_count`    INT     NOT NULL DEFAULT 0 COMMENT '成交订单数',
    `order_amount`   BIGINT  NOT NULL DEFAULT 0 COMMENT '成交额（分）',
    `refund_count`   INT     NOT NULL DEFAULT 0 COMMENT '退款笔数',
    `refund_amount`  BIGINT  NOT NULL DEFAULT 0 COMMENT '退款额（分）',
    `assess_count`   INT     NOT NULL DEFAULT 0 COMMENT '体测次数',
    `create_time`    DATETIME         DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stat_date` (`stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '每日运营数据汇总';

-- ==================== 3. 每日课程维度（销量榜的数据源） ====================
-- 为什么单独一张而不是塞进 data_daily：
-- 全局汇总一天只有一行，课程维度一天 N 行 —— 粒度不同，硬塞进去
-- 就要么冗余要么拆 JSON，两种都不好查。
CREATE TABLE IF NOT EXISTS `data_course_daily`
(
    `id`            BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stat_date`     INT     NOT NULL COMMENT '统计日期 yyyyMMdd',
    `course_id`     BIGINT  NOT NULL COMMENT '课程id',
    `order_count`   INT     NOT NULL DEFAULT 0 COMMENT '销量',
    `order_amount`  BIGINT  NOT NULL DEFAULT 0 COMMENT '销售额（分）',
    `refund_count`  INT     NOT NULL DEFAULT 0 COMMENT '退款笔数',
    `create_time`   DATETIME         DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_course` (`stat_date`, `course_id`),
    KEY `idx_date_sales` (`stat_date`, `order_count`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '每日课程销量统计';

-- ============================================================
-- 校验
-- ============================================================
SELECT TABLE_NAME AS `表`, TABLE_COMMENT AS `说明`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'fm_data'
ORDER BY TABLE_NAME;
