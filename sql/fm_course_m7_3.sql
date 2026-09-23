-- ============================================================
-- fm_course —— M7-3（报名人数 sold）增量脚本
-- 可反复执行：先查 information_schema，已存在就跳过
--
-- 背景：ES 索引里一直有 sold 字段（integer，且 CourseSearchQuery 支持
--       rankBy=sold 排序），但 fm-search 建索引时写的是硬编码 `.sold(0)`，
--       而 course 表根本没有这一列 —— 前端拿到的报名人数恒为 0。
--
-- 为什么用「冗余计数列」而不是查询时现算：
--   真正的报名记录在 fm-learning，已支付订单在 fm-trade，两者都在别的库。
--   fm-search 建索引时若为此去调 fm-learning，就把「搜索」和「学习」耦合上了
--   （搜索服务本该只依赖课程数据）。
-- ============================================================

USE `fm_course`;

-- ==================== course 表加 sold ====================
-- ★ 放 AFTER `score`：两者都是「展示用统计值」，放在一起便于阅读
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `course`
        ADD COLUMN `sold` INT NOT NULL DEFAULT 0
        COMMENT ''报名人数（订单支付成功后 +1，退款成功后 -1）'' AFTER `score`',
    'SELECT ''course.sold 已存在，跳过'' AS note'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'fm_course'
      AND TABLE_NAME = 'course'
      AND COLUMN_NAME = 'sold');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ★ course_draft 表**不加** sold：
--   上架时是 `BeanUtil.copyProperties(draft, Course.class)`，
--   draft 没有该字段 → 拷出来的 Course.sold 为 null →
--   MyBatis-Plus 的 save/updateById 默认忽略 null 字段 →
--   insert 时用 DB 默认值 0，update 时保留原值。
--   两个方向都安全，不需要在草稿侧也放一个销售数。

-- ==================== 消费幂等表 ====================
-- 为什么需要：RabbitMQ 是「至少一次」投递，消费者重启 / 网络抖动 / ack 丢失
-- 都会让同一条 order.pay 消息被投递多次。如果直接 `sold = sold + 1`，
-- 重复投递就会把报名人数算多 —— 而且这种偏差**没有任何报错**，只能靠对账发现。
--
-- 做法：每处理一条 (orderId, courseId, delta) 就先往这张表插一条，
--       唯一键冲突说明「这条消息我处理过了」，直接跳过。
--       delta 参与唯一键，这样同一订单的同一门课「报名 +1」与「退款 -1」各能记一次。
CREATE TABLE IF NOT EXISTS `course_sold_log` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `order_id`    BIGINT      NOT NULL COMMENT '订单id',
    `course_id`   BIGINT      NOT NULL COMMENT '课程id',
    `delta`       TINYINT     NOT NULL COMMENT '变动值：+1 报名 / -1 退款',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_course_delta` (`order_id`, `course_id`, `delta`),
    KEY `idx_course_id` (`course_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '报名人数变动日志（MQ 消费幂等）';
