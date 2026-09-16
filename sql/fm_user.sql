-- ============================================================
-- FitMind AI 智能健身平台 —— 用户库表结构（fm_user）
-- ------------------------------------------------------------
-- 表结构由常见实现 PO 实体反向生成，并按健身场景做适配：
--   user              账号表
--   user_detail       用户详情表
--   user_body_profile 身体档案表（健身场景新增）★
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p1234 fm_user < fm_user.sql
-- ============================================================

USE `fm_user`;

-- ------------------------------------------------------------
-- 1. 账号表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(32)  NOT NULL COMMENT '用户名（默认与手机号一致）',
    `cell_phone`  VARCHAR(16)  NOT NULL COMMENT '手机号',
    `password`    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '账户状态：0-禁用，1-正常',
    `type`        TINYINT      NOT NULL DEFAULT 2 COMMENT '用户类型：1-管理员，2-学员，3-教练',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `creater`     BIGINT       DEFAULT NULL COMMENT '创建者id',
    `updater`     BIGINT       DEFAULT NULL COMMENT '更新者id',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cell_phone` (`cell_phone`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户账号表';

-- ------------------------------------------------------------
-- 2. 用户详情表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user_detail`;
CREATE TABLE `user_detail` (
    `id`          BIGINT       NOT NULL COMMENT '用户id（与 user.id 一致）',
    `type`        TINYINT      NOT NULL DEFAULT 2 COMMENT '用户类型：1-管理员，2-学员，3-教练',
    `name`        VARCHAR(32)  DEFAULT NULL COMMENT '姓名 / 昵称',
    `gender`      TINYINT      DEFAULT NULL COMMENT '性别：0-男性，1-女性',
    `icon`        VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
    `email`       VARCHAR(64)  DEFAULT NULL COMMENT '邮箱',
    `qq`          VARCHAR(32)  DEFAULT NULL COMMENT 'QQ号码',
    `birthday`    DATE         DEFAULT NULL COMMENT '生日',
    `province`    VARCHAR(32)  DEFAULT NULL COMMENT '省',
    `city`        VARCHAR(32)  DEFAULT NULL COMMENT '市',
    `district`    VARCHAR(32)  DEFAULT NULL COMMENT '区',
    `intro`       VARCHAR(500) DEFAULT NULL COMMENT '个人介绍 / 健身宣言',
    `photo`       VARCHAR(255) DEFAULT NULL COMMENT '形象照地址',
    `role_id`     BIGINT       DEFAULT NULL COMMENT '角色id（管理端 RBAC 使用）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `creater`     BIGINT       DEFAULT NULL COMMENT '创建者id',
    `updater`     BIGINT       DEFAULT NULL COMMENT '更新者id',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户详情表';

-- ------------------------------------------------------------
-- 3. 身体档案表（健身场景新增）★
--    BMI 由后端根据 身高/体重 自动计算，不接收前端传值
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user_body_profile`;
CREATE TABLE `user_body_profile` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          BIGINT       NOT NULL COMMENT '用户id',
    `height`           DECIMAL(5, 1) DEFAULT NULL COMMENT '身高（cm）',
    `weight`           DECIMAL(5, 1) DEFAULT NULL COMMENT '当前体重（kg）',
    `target_weight`    DECIMAL(5, 1) DEFAULT NULL COMMENT '目标体重（kg）',
    `bmi`              DECIMAL(4, 1) DEFAULT NULL COMMENT 'BMI（后端自动计算）',
    `body_fat_rate`    DECIMAL(4, 1) DEFAULT NULL COMMENT '体脂率（%）',
    `waist`            DECIMAL(4, 1) DEFAULT NULL COMMENT '腰围（cm）',
    `chest`            DECIMAL(4, 1) DEFAULT NULL COMMENT '胸围（cm）',
    `arm`              DECIMAL(4, 1) DEFAULT NULL COMMENT '臂围（cm）',
    `hip`              DECIMAL(4, 1) DEFAULT NULL COMMENT '臀围（cm）',
    `goal_type`        TINYINT       DEFAULT NULL COMMENT '训练目标：1-增肌，2-减脂，3-塑形，4-保持',
    `training_level`   TINYINT       DEFAULT NULL COMMENT '训练水平：1-零基础，2-初级，3-中级，4-高级',
    `weekly_frequency` TINYINT       DEFAULT NULL COMMENT '每周可训练次数',
    `injury_history`   VARCHAR(500)  DEFAULT NULL COMMENT '伤病史（AI 教练推荐课程时会参考）',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户身体档案表';

-- ------------------------------------------------------------
-- 校验
-- ------------------------------------------------------------
SELECT TABLE_NAME AS `表名`, TABLE_COMMENT AS `说明`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'fm_user'
ORDER BY TABLE_NAME;
