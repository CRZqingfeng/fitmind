-- ============================================================
-- fm_learning：M3-2 签到与积分体系
-- ------------------------------------------------------------
-- 三部分：积分明细 / 赛季 / 动态分表（由代码建）
-- ============================================================
USE `fm_learning`;

-- ==================== 积分明细 ====================
-- ★ uk_user_type_biz 是「积分幂等」的关键：
--   MQ 是 at-least-once，同一条消息可能被重复投递；
--   常见实现的积分没有防重（重复消费会重复加分），我们用唯一索引兜底。
--   biz_id 取值：学习 = {lessonId}:{sectionId}，签到 = yyyyMMdd，问答/笔记 = 记录id
CREATE TABLE IF NOT EXISTS `points_record` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT      NOT NULL COMMENT '用户id',
    `type`        TINYINT     NOT NULL COMMENT '积分类型：1-学习 2-签到 3-问答 4-笔记',
    `points`      INT         NOT NULL COMMENT '本次积分（可能被每日上限截断）',
    `biz_id`      VARCHAR(64)          DEFAULT NULL COMMENT '业务唯一标识（幂等用）',
    `create_time` DATETIME             DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_type_biz` (`user_id`, `type`, `biz_id`),
    KEY `idx_user_type_time` (`user_id`, `type`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分明细';

-- ==================== 赛季 ====================
-- id 同时作为分表后缀：points_board_{id}
CREATE TABLE IF NOT EXISTS `points_board_season` (
    `id`         INT         NOT NULL AUTO_INCREMENT COMMENT '赛季id，即分表后缀',
    `name`       VARCHAR(32) NOT NULL COMMENT '赛季名称',
    `begin_time` DATE        NOT NULL COMMENT '开始日期',
    `end_time`   DATE        NOT NULL COMMENT '结束日期',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分赛季';

-- 种子数据：上月 + 本月（让首月结算与验收都有数据可用）
-- 用显式 id + INSERT IGNORE 保证可重复执行
INSERT IGNORE INTO `points_board_season` (`id`, `name`, `begin_time`, `end_time`) VALUES
    (1, CONCAT(DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 MONTH), '%Y年%m月'), '赛季'),
        DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 MONTH), '%Y-%m-01'),
        LAST_DAY(DATE_SUB(CURDATE(), INTERVAL 1 MONTH))),
    (2, CONCAT(DATE_FORMAT(CURDATE(), '%Y年%m月'), '赛季'),
        DATE_FORMAT(CURDATE(), '%Y-%m-01'),
        LAST_DAY(CURDATE()));

-- ==================== 榜单分表（动态创建） ====================
-- ⚠️ 这里不建：物理表 points_board_{season} 由 XXL-JOB 任务 createTableJob 调用
--    PointsBoardMapper.createPointsBoardTable 创建（DDL 在 mapper XML 里，环境可重建）。
--    设计要点：id 即排名——结算是按 ZSet 从高到低分页插入，自增 id 天然等于排名。
