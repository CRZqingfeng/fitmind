-- ============================================================
-- FitMind 支付库 fm_pay —— M4-4 增量
-- ------------------------------------------------------------
-- 新增一张表：
--   refund_order  退款单（渠道侧的退款事实）
--
-- ★★ 与常见实现的差异（这是本阶段的重点）：
--   1. 常见实现靠 @Lock 分布式锁 + "查有没有已存在的退款单"来做幂等 ——
--      锁是**尽力而为**的（leaseTime 到期即失效），且 check-then-act 在锁失效时
--      依然会重复。我们直接上**唯一索引** uk_biz_refund_order_no：
--      并发重复申请时数据库层面就插不进第二条，这是硬保证。
--   2. 状态加一个"退款中"（常见实现也有，但没有补偿路径）——
--      渠道异步退款必须配一个轮询任务，否则回调丢了就是永久悬空。
--   3. is_split 记录是否全额退款：真实渠道在部分退款时需要传原单总额。
--
-- 金额统一用**分**（int），与 pay_order.amount / trade_order.real_amount 一致。
-- ============================================================

USE `fm_pay`;

-- ==================== 退款单 ====================
-- 一笔支付单可以对应多张退款单吗？
--   本项目的业务约束是"一个订单明细最多退一次成功"，
--   但**失败后允许重新申请**，所以会有多张退款单指向同一个 pay_order，
--   而 biz_refund_order_no（业务退款申请 id）始终唯一。
--   于是：uk_biz_refund_order_no 挡"同一申请重复退款"，
--        idx_pay_order_no 用于按支付单查历史退款记录。
CREATE TABLE IF NOT EXISTS `refund_order`
(
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `refund_order_no`     VARCHAR(64) NOT NULL COMMENT '★ 退款单号（本系统生成，全局唯一，给渠道用）',
    `biz_refund_order_no` VARCHAR(64) NOT NULL COMMENT '★ 业务退款单号（= fm_trade.refund_apply.id）',
    `biz_order_no`        VARCHAR(64) NOT NULL COMMENT '业务订单号（trade_order.id）',
    `biz_user_id`         BIGINT      NOT NULL COMMENT '退款用户id',
    `pay_order_no`        VARCHAR(64) NOT NULL COMMENT '★ 原支付单号（渠道退款必须带上）',
    `pay_channel_code`    VARCHAR(32) NOT NULL COMMENT '支付渠道码（决定用哪个渠道实现退款）',
    `refund_amount`       INT         NOT NULL COMMENT '退款金额（分）',
    `total_amount`        INT         NOT NULL COMMENT '原支付总额（分）—— 部分退款时渠道需要',
    `is_split`            TINYINT     NOT NULL DEFAULT 0 COMMENT '是否全额退款：0-否 1-是',
    `status`              TINYINT     NOT NULL DEFAULT 1 COMMENT '1-退款中 2-退款成功 3-退款失败',
    `refund_channel`      VARCHAR(32)          DEFAULT NULL COMMENT '渠道回执里的退款渠道标识',
    `result_code`         VARCHAR(64)          DEFAULT NULL COMMENT '渠道返回码',
    `result_msg`          VARCHAR(255)         DEFAULT NULL COMMENT '渠道返回描述 / 失败原因',
    `finish_time`         DATETIME             DEFAULT NULL COMMENT '退款完成时间（成功或失败）',
    `create_time`         DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- ★★ 幂等的第一道闸门：同一笔业务退款申请，永远只会有这一张退款单。
    --    并发重复申请时，第二个请求在 INSERT 就撞唯一索引 ——
    --    比"先查再插"（check-then-act）或分布式锁都可靠：数据库自己保证原子性。
    UNIQUE KEY `uk_biz_refund_order_no` (`biz_refund_order_no`),
    UNIQUE KEY `uk_refund_order_no` (`refund_order_no`),
    KEY `idx_biz_order` (`biz_order_no`),
    -- 补偿任务要扫"退款中"的单子，走这个索引
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '退款单';

-- ============================================================
-- 校验
-- ============================================================
SELECT COLUMN_NAME       AS `字段`,
       COLUMN_TYPE       AS `类型`,
       COLUMN_COMMENT    AS `说明`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'fm_pay'
  AND TABLE_NAME = 'refund_order'
ORDER BY ORDINAL_POSITION;
