-- ============================================================
-- FitMind 支付库 fm_pay
-- ------------------------------------------------------------
-- M4-2：支付单 + 支付渠道
--
-- 与常见实现的差异：
--   1. 常见实现的 支付服务 是多模块结构（api / domain / service），
--      目的是让 交易服务 只依赖 api 而不依赖实现。我们用 fm-api 统一放
--      跨服务契约（CourseClient / RemarkClient / PayClient 都在这），
--      已经起到了同样的隔离作用，所以 fm-pay 是单模块。
--   2. ★ 增加了 Mock 渠道。常见实现只有真实的微信/支付宝（靠改沙箱域名
--      "假装"），本地跑不通完整链路；我们提供一个 mockPay 渠道，
--      它走的是和真实渠道完全相同的代码路径（含验签、幂等、MQ），
--      只是"第三方"换成了我们自己 —— 零外部依赖、演示不会挂、验收可全自动。
--   3. 金额统一用**分**（int），与 fm_course.course.price、fm_trade 一致。
-- ============================================================

USE `fm_pay`;

-- ==================== 支付渠道 ====================
-- 一行一个渠道。channel_code 同时是渠道实现类的 Spring Bean 名，
-- 新增渠道 = 写一个 @Service("{channelCode}") + 往这里插一行。
CREATE TABLE IF NOT EXISTS `pay_channel` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `channel_code` VARCHAR(32) NOT NULL COMMENT '渠道码（同时是渠道实现类的 Bean 名）',
    `name`         VARCHAR(64) NOT NULL COMMENT '渠道名称',
    `enabled`      TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用：0-否 1-是',
    `create_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`channel_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付渠道';

-- 预置 Mock 渠道（M4-2 唯一可用的渠道）
INSERT INTO `pay_channel`(`channel_code`, `name`, `enabled`)
VALUES ('mockPay', '模拟支付（本地闭环）', 1)
    ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- ==================== 支付单 ====================
-- 一笔业务订单（trade_order）可以对应多笔支付单（首次支付失败后重试），
-- 但同一时刻只有一笔是"待支付"的 —— 由业务侧保证。
CREATE TABLE IF NOT EXISTS `pay_order` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `pay_order_no`     VARCHAR(64) NOT NULL COMMENT '★ 支付单号（本系统生成，全局唯一）',
    `biz_order_no`     VARCHAR(64) NOT NULL COMMENT '★ 业务订单号（trade_order.id）',
    `biz_user_id`      BIGINT      NOT NULL COMMENT '下单用户（冗余，回调时不用回查交易）',
    `pay_channel_code` VARCHAR(32) NOT NULL COMMENT '支付渠道码',
    `amount`           INT         NOT NULL COMMENT '支付金额（分）',
    `status`           TINYINT     NOT NULL DEFAULT 1 COMMENT '1-待支付 2-已支付 3-已关闭（失败/取消）',
    `qr_code_url`      VARCHAR(512)         DEFAULT NULL COMMENT '支付二维码链接（Mock 渠道指向本地模拟收银台）',
    `pay_success_time` DATETIME             DEFAULT NULL COMMENT '支付成功时间',
    `expire_time`      DATETIME    NOT NULL COMMENT '支付单过期时间',
    `create_time`      DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- ★ 支付单号唯一：这是回调幂等的第一道闸（重复回调插入会冲突）
    UNIQUE KEY `uk_pay_order_no` (`pay_order_no`),
    KEY `idx_biz` (`biz_order_no`),
    KEY `idx_user_status` (`biz_user_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付单';
