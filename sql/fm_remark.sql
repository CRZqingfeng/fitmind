-- ============================================================
-- fm_remark 库（M3-3：统一点赞服务）
-- ------------------------------------------------------------
-- 设计要点：
--   1. liked_record 是点赞明细的「永久凭证」—— 常见实现的 Redis 版实现
--      完全不写这张表（Redis 是唯一数据源），Redis 一挂点赞记录就蒸发，
--      而且 SCARD 归零后同步任务会把业务表的 liked_times 写成 0（数据损坏）。
--      我们让 Redis 管热（判重 + 计数），这张表管冷（可重建）。
--   2. uk_user_biz 唯一索引是落库幂等的最后一道防线：
--      SADD 返回 1 但 DB 已有行 → 说明 Redis 与 DB 不一致，会打 WARN 日志（不一致探针）。
--   3. idx_biz 支撑「从 DB 重算计数」（rebuild 内部接口、以及 Redis 丢失后的恢复）。
--
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p < sql/fm_remark.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS `fm_remark`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `fm_remark`;

DROP TABLE IF EXISTS `liked_record`;
CREATE TABLE `liked_record`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '点赞人id',
    `biz_id`      BIGINT   NOT NULL COMMENT '业务id（回答id / 笔记id）',
    `biz_type`    VARCHAR(16) NOT NULL COMMENT '业务类型：QA-问答回答 NOTE-笔记',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_biz` (`user_id`, `biz_type`, `biz_id`) COMMENT '点赞幂等：同一人同一业务只有一条',
    KEY `idx_biz` (`biz_type`, `biz_id`) COMMENT '支撑从 DB 重算某条业务的点赞数'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '点赞明细（Redis 为热数据，本表为永久凭证）';
