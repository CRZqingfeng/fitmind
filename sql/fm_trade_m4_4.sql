-- ============================================================
-- FitMind 交易库 fm_trade —— M4-4 增量
-- ------------------------------------------------------------
-- 新增一张表：
--   refund_apply  退款申请（退款过程的**唯一真相来源**）
--
-- ★★ 与常见实现的差异（每一条都是有原因的）：
--
--   1. 常见实现在 trade_order_detail 上加了第二个状态字段 refund_status，
--      与 status 并存 —— 两个字段表达同一件事，迟早不同步。
--      我们只用 status（语义扩展为 4-退款中 / 5-已退款），
--      细粒度的"待审批/已同意/成功/失败"看本表的 status。
--      一句话：**退款过程看 refund_apply，订单列表看 trade_order_detail.status**。
--
--   2. 常见实现靠"查一下这个明细有没有进行中的申请"来做防重（check-then-act），
--      并发下两个请求会都通过。我们用**部分唯一索引的替代方案**：
--      生成列 active_flag（进行中=明细id，否则=NULL）+ 唯一索引 ——
--      MySQL 里唯一索引允许多个 NULL，所以"进行中只允许一条"这件事
--      由数据库保证，而且**退款成功后可以再次申请**（历史记录不受限）。
--
--   3. 常见实现学员最多申请 2 次是在 Java 里数数，我们同样放在服务层，
--      但把"进行中"的判定交给上面的唯一索引 —— 两者作用不同：
--      索引防并发，次数限制是业务规则。
-- ============================================================

USE `fm_trade`;

-- ==================== 退款申请 ====================
CREATE TABLE IF NOT EXISTS `refund_apply`
(
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键（★ 同时作为支付侧的 biz_refund_order_no）',
    `order_detail_id`  BIGINT      NOT NULL COMMENT '★ 退的是哪个订单明细（一门课一次退款）',
    `order_id`         BIGINT      NOT NULL COMMENT '订单id',
    `user_id`          BIGINT      NOT NULL COMMENT '订单所属用户id（★ 不是申请人 —— 管理员代申请时两者不同）',
    `refund_amount`    INT         NOT NULL COMMENT '退款金额（分）—— 服务端从明细实付取，前端传值无效',
    `status`           TINYINT     NOT NULL COMMENT '1-待审批 2-学员取消 3-同意退款 4-拒绝退款 5-退款成功 6-退款失败',
    `message`          VARCHAR(64)          DEFAULT NULL COMMENT '状态描述（给用户看的一句话）',
    `refund_reason`    VARCHAR(64)          DEFAULT NULL COMMENT '退款原因（分类）',
    `question_desc`    VARCHAR(500)         DEFAULT NULL COMMENT '问题说明（用户填的补充）',
    `approver`         BIGINT               DEFAULT NULL COMMENT '审批人id',
    `approve_opinion`  VARCHAR(255)         DEFAULT NULL COMMENT '审批意见',
    `approve_time`     DATETIME             DEFAULT NULL COMMENT '审批时间',
    `refund_order_no`  VARCHAR(64)          DEFAULT NULL COMMENT '支付侧退款单号（回调后回填）',
    `refund_channel`   VARCHAR(32)          DEFAULT NULL COMMENT '退款渠道标识',
    `failed_reason`    VARCHAR(255)         DEFAULT NULL COMMENT '退款失败原因',
    `finish_time`      DATETIME             DEFAULT NULL COMMENT '退款完成时间（成功或失败）',
    `creater`          BIGINT      NOT NULL COMMENT '申请人id',
    `create_time`      DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- ★★ "同一明细同时只能有一笔进行中的退款申请" —— 用生成列 + 唯一索引实现。
    --    进行中（UN_APPROVE / AGREE）时 active_flag = order_detail_id，
    --    已终结（取消/拒绝/成功/失败）时 active_flag = NULL。
    --    MySQL 的唯一索引对多个 NULL 不冲突，所以：
    --      · 并发的第二个进行中申请 → 撞索引 → 数据库层面挡住（不靠锁、不靠先查后插）
    --      · 退款失败后重新申请 → 旧的已是 NULL，不冲突，允许
    --    这比常见实现"查一下有没有进行中的"（check-then-act）可靠得多。
    `active_flag`      BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` IN (1, 3) THEN `order_detail_id` ELSE NULL END
        ) STORED COMMENT '进行中标记：进行中=明细id，否则NULL（配合唯一索引实现防重）',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_active_detail` (`active_flag`),
    KEY `idx_detail` (`order_detail_id`),
    KEY `idx_order` (`order_id`),
    KEY `idx_user_status` (`user_id`, `status`),
    -- 退款成功/失败后要按明细累计已退金额（资金安全校验用）
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '退款申请';

-- ============================================================
-- 校验
-- ============================================================
SELECT COLUMN_NAME    AS `字段`,
       COLUMN_TYPE    AS `类型`,
       COLUMN_COMMENT AS `说明`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'fm_trade'
  AND TABLE_NAME = 'refund_apply'
ORDER BY ORDINAL_POSITION;

-- 唯一索引建出来了吗
SELECT INDEX_NAME AS `索引`, COLUMN_NAME AS `字段`, NON_UNIQUE AS `非唯一`
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'fm_trade'
  AND TABLE_NAME = 'refund_apply';
