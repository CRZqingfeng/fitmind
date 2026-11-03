-- ============================================================
-- FitMind M12-1 上传增强：断点续传 + 秒传 —— 增量迁移
-- ------------------------------------------------------------
-- 前置：已执行 sql/fm_media.sql（M2-1 的 file / media 两张表）
-- 幂等：本脚本可重复执行（每一步都先查 information_schema 再决定做不做）
--
-- ★★ 本脚本含一个**不可逆**动作：DROP INDEX `uk_key`
--    原因：秒传要求"多条 file 记录共享同一份物理对象"，
--          而 uk_key 强制一个 key 只能有一行。
--    唯一性没有消失，只是**上移**到 file_object 的
--          uk_object_key（对象唯一）与 uk_biz_hash（内容唯一）。
--    执行前务必备份：mysqldump fm_media > fm_media_xxx.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS fm_media DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE fm_media;

-- ==================== ① 物理对象表（秒传的基础） ====================
-- 一个文件内容（hash）一行，被 N 条 file 记录引用（ref_count）。
-- ★ 秒传 = "再插一条 file 记录，然后 ref_count + 1"，不是"复制一份文件"。
CREATE TABLE IF NOT EXISTS `file_object` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，物理对象id',
    `file_hash`   CHAR(64)    NOT NULL                COMMENT '文件内容 SHA-256（小写 hex）',
    `biz_type`    VARCHAR(32) NOT NULL                COMMENT '业务类型，决定对象前缀与可访问策略',
    `object_key`  VARCHAR(255) NOT NULL               COMMENT '对象存储中的 key（由 hash 派生，可预测）',
    `size`        BIGINT      NOT NULL                COMMENT '文件大小，单位字节',
    `ref_count`   INT         NOT NULL DEFAULT 1      COMMENT '被多少条 file 记录引用，归零才删物理对象',
    `creater`     BIGINT               DEFAULT NULL   COMMENT '首个上传者',
    `create_time` DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- ★ 秒传的判定键：同一业务前缀 + 同一内容。**故意不加唯一约束在 hash 上**
    --   —— 跨前缀复用会让"私有前缀"的对象披上"公开前缀"的身份（见方案 D3）
    UNIQUE KEY `uk_biz_hash` (`biz_type`, `file_hash`),
    -- 第二个唯一键是"并发 complete 同一文件"的最后一道闸
    UNIQUE KEY `uk_object_key` (`object_key`),
    KEY `idx_creater` (`creater`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '物理对象表，秒传与引用计数的载体';

-- ==================== ② 上传任务表（断点续传的进度） ====================
CREATE TABLE IF NOT EXISTS `file_upload_task` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，任务id',
    `file_hash`       CHAR(64)     NOT NULL                COMMENT '文件内容 SHA-256',
    `biz_type`        VARCHAR(32)  NOT NULL                COMMENT '业务类型',
    `target`          VARCHAR(16)  NOT NULL DEFAULT 'file' COMMENT '合并后产出什么：file-通用文件 / media-视频媒资',
    `filename`        VARCHAR(255)          DEFAULT NULL   COMMENT '原始文件名',
    `size`            BIGINT       NOT NULL                COMMENT '文件总大小',
    `chunk_size`      INT          NOT NULL                COMMENT '分片大小（字节，≥5MiB）',
    `chunk_total`     INT          NOT NULL                COMMENT '分片总数',
    `object_key`      VARCHAR(255) NOT NULL                COMMENT '目标对象 key（由 hash 派生）',
    `status`          TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1-上传中 2-已完成 3-已取消',
    `uploaded_chunks` INT          NOT NULL DEFAULT 0      COMMENT '已成功上传的分片数（快照，非真相源）',
    `uploaded_bytes`  BIGINT       NOT NULL DEFAULT 0      COMMENT '已上传字节数（快照）',
    `creater`         BIGINT                DEFAULT NULL   COMMENT '上传者',
    `create_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    -- 断点恢复的查询键：同一人 + 同一内容 + 同一前缀 + 仍在传 → 复用旧任务
    KEY `idx_lookup` (`file_hash`, `biz_type`, `size`, `creater`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分片上传任务表';

-- ==================== ③ 分片进度表 ====================
CREATE TABLE IF NOT EXISTS `file_upload_part` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`     BIGINT      NOT NULL                COMMENT '所属任务id',
    `part_number` INT         NOT NULL                COMMENT '分片序号，从 1 开始',
    `etag`        VARCHAR(128)         DEFAULT NULL   COMMENT '对象存储返回的 ETag（合并时要用）',
    `size`        BIGINT      NOT NULL                COMMENT '该片大小',
    `create_time` DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 同一片重复传 → upsert 覆盖，天然幂等
    UNIQUE KEY `uk_task_part` (`task_id`, `part_number`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分片上传进度表';

-- ==================== ③-2 任务表补 target 列（幂等） ====================
-- 已存在的库是"先建表、后加列"的，必须单独补一步；新库由上面的 CREATE TABLE 自带。
SET @db := DATABASE();

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file_upload_task' AND COLUMN_NAME = 'target') = 0,
    'ALTER TABLE `file_upload_task` ADD COLUMN `target` VARCHAR(16) NOT NULL DEFAULT ''file'' '
    'COMMENT ''合并后产出什么：file-通用文件 / media-视频媒资'' AFTER `biz_type`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ==================== ④ file 表扩展（幂等） ====================

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file' AND COLUMN_NAME = 'file_hash') = 0,
    'ALTER TABLE `file` ADD COLUMN `file_hash` CHAR(64) NULL COMMENT ''文件内容 SHA-256（秒传用；老数据为 NULL）'' AFTER `url`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file' AND COLUMN_NAME = 'object_id') = 0,
    'ALTER TABLE `file` ADD COLUMN `object_id` BIGINT NULL COMMENT ''引用的物理对象id（老数据为 NULL）'' AFTER `file_hash`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file' AND INDEX_NAME = 'uk_key') > 0,
    'ALTER TABLE `file` DROP INDEX `uk_key`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file' AND INDEX_NAME = 'idx_file_hash') = 0,
    'ALTER TABLE `file` ADD KEY `idx_file_hash` (`file_hash`)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file' AND INDEX_NAME = 'idx_key') = 0,
    'ALTER TABLE `file` ADD KEY `idx_key` (`key`)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ==================== ⑤ media 表扩展（视频也要能秒传） ====================
-- ★★ 与 file 表同理：`uk_file_id` 强制"一个对象只能对应一条媒资记录"，
--    而秒传要求"同一份视频被多条记录引用"（每个学员/每个课程各一条，删互不影响）。
--    唯一性上移到 file_object，media 侧改为普通索引。
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'media' AND COLUMN_NAME = 'object_id') = 0,
    'ALTER TABLE `media` ADD COLUMN `object_id` BIGINT NULL COMMENT ''引用的物理对象id（M12-1 秒传；老数据为 NULL）'' AFTER `file_id`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'media' AND INDEX_NAME = 'uk_file_id') > 0,
    'ALTER TABLE `media` DROP INDEX `uk_file_id`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'media' AND INDEX_NAME = 'idx_file_id') = 0,
    'ALTER TABLE `media` ADD KEY `idx_file_id` (`file_id`)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
