-- ============================================================
-- FitMind 促销库 fm_promotion
-- ------------------------------------------------------------
-- M4-3：优惠券 + 兑换码
--
-- 四张表：
--   coupon        券模板（运营配置：门槛、折扣、发放量、限领）
--   coupon_scope  券的限定范围（这张券能用在哪几门课 / 哪个分类）
--   user_coupon   用户券（谁领了哪张券、用没用、什么时候过期）
--   exchange_code 兑换码（★ 不为一个码建一条"待生成"记录 —— 见下方说明）
--
-- ★★ 关于「20 亿兑换码」的核心思路：
--   如果每张券发 100 万个码、有 2000 张券，就是 20 亿条记录 —— 存不下也不该存。
--   实际做法是：
--     1. 码本身由**算法**生成（自增序号 + 券id 混淆 + 校验码 → Base32 10 位串），
--        不需要预先存进数据库，用的时候现算
--     2. 数据库只存**已生成过**的码（用于后台查看/导出），按需插入
--     3. 是否已兑换用 **Redis bitmap** 标记（1 bit/码，20 亿码 = 256MB）
--     4. 序号属于哪张券用 **Redis ZSet** 存区间映射（member=券id，score=该券的最大序号）
--   这样 20 亿个码的存储空间是常数级的，而不是线性增长。
-- ============================================================

USE `fm_promotion`;

-- ==================== 券模板 ====================
CREATE TABLE IF NOT EXISTS `coupon` (
    `id`                BIGINT       NOT NULL COMMENT '券id（雪花）',
    `name`              VARCHAR(128) NOT NULL COMMENT '券名称（可与活动名一致）',
    `discount_type`     TINYINT      NOT NULL COMMENT '折扣类型：1-满减 2-每满减 3-折扣 4-无门槛',
    `specific`          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否限定作用范围：0-不限 1-限定',
    `discount_value`    INT          NOT NULL COMMENT '折扣值：满减存金额(分)，折扣存折扣率(8折存80)',
    `threshold_amount`  INT          NOT NULL DEFAULT 0 COMMENT '使用门槛（分），0 表示无门槛',
    `max_discount_amount` INT        NOT NULL DEFAULT 0 COMMENT '最高优惠（分），0 表示不限制',
    `obtain_way`        TINYINT      NOT NULL DEFAULT 1 COMMENT '获取方式：1-手动领取 2-兑换码',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-待发放 2-发放中 3-已结束',
    `total_num`         INT          NOT NULL DEFAULT 0 COMMENT '发放总量',
    `issue_begin_time`  DATETIME     NOT NULL COMMENT '领取开始时间',
    `issue_end_time`    DATETIME     NOT NULL COMMENT '领取结束时间',
    `user_limit`        INT          NOT NULL DEFAULT 1 COMMENT '每人限领数量',
    `term_days`         INT                   DEFAULT NULL COMMENT '有效期天数（领到后 N 天内有效）',
    `term_begin_time`   DATETIME              DEFAULT NULL COMMENT '固定有效期起',
    `term_end_time`     DATETIME              DEFAULT NULL COMMENT '固定有效期止',
    `creater`           BIGINT                DEFAULT NULL,
    `create_time`       DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '优惠券（券模板）';

-- ==================== 券的限定范围 ====================
-- 一张券可以限定"只能用于某几门课 / 某个分类"。
-- 用 (coupon_id, biz_id) 存，biz_type 区分是课程还是分类。
CREATE TABLE IF NOT EXISTS `coupon_scope` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `coupon_id`  BIGINT   NOT NULL COMMENT '券id',
    `biz_type`   TINYINT  NOT NULL COMMENT '范围类型：1-分类 2-课程',
    `biz_id`     BIGINT   NOT NULL COMMENT '分类id / 课程id',
    `create_time` DATETIME         DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_coupon` (`coupon_id`),
    KEY `idx_biz` (`biz_type`, `biz_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '优惠券限定范围';

-- ==================== 用户券 ====================
CREATE TABLE IF NOT EXISTS `user_coupon` (
    `id`            BIGINT   NOT NULL COMMENT '用户券id（雪花）',
    `coupon_id`     BIGINT   NOT NULL COMMENT '券模板id',
    `user_id`       BIGINT   NOT NULL COMMENT '持有人',
    `status`        TINYINT  NOT NULL DEFAULT 1 COMMENT '1-未使用 2-已使用 3-已失效(过期)',
    `term_begin_time` DATETIME        DEFAULT NULL COMMENT '有效期起（领券时按券规则算出）',
    `term_end_time`   DATETIME        DEFAULT NULL COMMENT '有效期止',
    `order_id`      BIGINT            DEFAULT NULL COMMENT '使用在哪个订单（核销时回填）',
    `used_time`     DATETIME          DEFAULT NULL COMMENT '核销时间',
    `create_time`   DATETIME          DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME          DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 我的券列表：按状态筛选
    KEY `idx_user_status` (`user_id`, `status`),
    KEY `idx_coupon` (`coupon_id`),
    KEY `idx_order` (`order_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户优惠券';

-- ==================== 兑换码 ====================
-- ⚠️ 注意 id 就是**序列号**（与券一一对应的全局自增序号），不是自增主键。
--    这样"码 → 序号 → 属于哪张券"只需一次主键查询。
CREATE TABLE IF NOT EXISTS `exchange_code` (
    `id`                BIGINT   NOT NULL COMMENT '★ 兑换码序列号（全局自增，由 Redis 分配）',
    `code`              VARCHAR(10) NOT NULL COMMENT '兑换码（Base32，10位）',
    `exchange_target_id` BIGINT  NOT NULL COMMENT '对应的券id',
    `expired_time`      DATETIME          DEFAULT NULL COMMENT '过期时间（沿用券的领取截止时间）',
    `status`            TINYINT  NOT NULL DEFAULT 1 COMMENT '1-未兑换 2-已兑换',
    `user_id`           BIGINT            DEFAULT NULL COMMENT '兑换人',
    `exchange_time`     DATETIME          DEFAULT NULL COMMENT '兑换时间',
    `create_time`       DATETIME          DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_target` (`exchange_target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '兑换码';
