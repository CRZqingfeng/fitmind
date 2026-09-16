-- ============================================================
-- FitMind AI 智能健身平台 —— 媒资库 fm_media
-- ------------------------------------------------------------
-- M2-1 训练媒资服务（fm-media）
-- 表：
--   file  文件表，图片等通用小文件（头像 / 课程封面 / 动作封面）
--   media 媒资表，训练视频
--
-- 说明：
--   1. `key` 是 MySQL 保留字，所有地方都用反引号包裹
--   2. 对象存储为 MinIO（bucket: fitmind），库里只存 key，不存完整 URL
--   3. 所有访问必须走预签名 URL，bucket 是私有的
-- ============================================================

CREATE DATABASE IF NOT EXISTS fm_media DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE fm_media;

-- ==================== 文件表：图片等通用文件 ====================
CREATE TABLE IF NOT EXISTS `file` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，文件id',
    `key`         VARCHAR(255) NOT NULL                COMMENT '对象存储中的唯一key，如 avatar/2026/09/uuid.jpg',
    `filename`    VARCHAR(255)          DEFAULT NULL   COMMENT '上传时的原始文件名',
    `biz_type`    VARCHAR(32)  NOT NULL DEFAULT 'common' COMMENT '业务类型：avatar-头像 course-cover-课程封面 action-cover-动作封面 media-cover-视频封面 common-通用',
    `url`         VARCHAR(512)          DEFAULT NULL   COMMENT '不带签名的访问地址（管理端展示用）',
    `size`        BIGINT                DEFAULT NULL   COMMENT '文件大小，单位字节',
    `status`      TINYINT      NOT NULL DEFAULT 2      COMMENT '状态：1-上传中 2-已上传',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `creater`     BIGINT                DEFAULT NULL   COMMENT '创建者',
    `updater`     BIGINT                DEFAULT NULL   COMMENT '更新者',
    `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key` (`key`),
    KEY `idx_biz_type` (`biz_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件表，图片等通用文件';

-- ==================== 媒资表：训练视频 ====================
CREATE TABLE IF NOT EXISTS `media` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，媒资id',
    `file_id`     VARCHAR(255) NOT NULL                COMMENT '对象存储key，如 media/2026/09/uuid.mp4',
    `filename`    VARCHAR(255)          DEFAULT NULL   COMMENT '视频原始文件名',
    `media_url`   VARCHAR(512)          DEFAULT NULL   COMMENT '不带签名的播放地址',
    `cover_url`   VARCHAR(512)          DEFAULT NULL   COMMENT '视频封面地址',
    `duration`    INT                   DEFAULT NULL   COMMENT '视频时长，单位秒',
    `size`        BIGINT                DEFAULT NULL   COMMENT '文件大小，单位字节',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1-上传中 2-已上传 3-已使用',
    `use_times`   INT          NOT NULL DEFAULT 0      COMMENT '被课程引用次数，>0 时禁止删除',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `creater`     BIGINT                DEFAULT NULL   COMMENT '创建者',
    `updater`     BIGINT                DEFAULT NULL   COMMENT '更新者',
    `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒资表，训练视频';
