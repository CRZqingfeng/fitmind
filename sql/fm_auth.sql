-- ============================================================
-- FitMind AI 智能健身平台 —— 认证库表结构（fm_auth）
-- ------------------------------------------------------------
-- 说明：管理端的 RBAC 表（role / menu / privilege / account_role）
--       留到管理端里程碑（M8）再建，M1 只建登录记录表。
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p1234 fm_auth < fm_auth.sql
-- ============================================================

USE `fm_auth`;

-- ------------------------------------------------------------
-- 登录信息记录表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `login_record`;
CREATE TABLE `login_record` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT      NOT NULL COMMENT '用户id',
    `cell_phone`  VARCHAR(16) DEFAULT NULL COMMENT '手机号',
    `login_time`  DATETIME    DEFAULT NULL COMMENT '登录时间',
    `logout_time` DATETIME    DEFAULT NULL COMMENT '登出时间',
    `login_date`  DATE        DEFAULT NULL COMMENT '登录日期',
    `duration`    BIGINT      DEFAULT NULL COMMENT '登录时长（秒）',
    `ipv4`        VARCHAR(32) DEFAULT NULL COMMENT 'ip地址',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_login_date` (`login_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='登录信息记录表';

-- ------------------------------------------------------------
-- 校验
-- ------------------------------------------------------------
SELECT TABLE_NAME AS `表名`, TABLE_COMMENT AS `说明`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'fm_auth'
ORDER BY TABLE_NAME;
