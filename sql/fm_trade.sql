-- ============================================================
-- FitMind AI 智能健身平台 —— 交易库 fm_trade
-- ------------------------------------------------------------
-- M4-1：购物车 + 订单 + 订单明细
--
-- 设计要点：
--   1. 金额统一用**分**（int），与 fm_course.course.price 一致
--   2. 订单 id 由**前端预生成**（雪花），下单接口因此天然幂等
--   3. order_detail 存课程名/封面/价格/有效期的**快照** ——
--      课程改名改价后，历史订单显示的仍是"当时买的样子"
--   4. ⚠️ 表名叫 trade_order 而不是 order：**ORDER 是 MySQL 保留字**，
--      MyBatis-Plus 不会自动加反引号，用 order 做表名会生成
--      `SELECT ... FROM order` 直接语法报错。加统一前缀既避开保留字，
--      也和库名 fm_trade、项目其他表（course_catalogue 等）的风格一致。
-- ============================================================

USE `fm_trade`;

-- ==================== 购物车 ====================
CREATE TABLE IF NOT EXISTS `trade_cart` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '用户id',
    `course_id`   BIGINT       NOT NULL COMMENT '课程id',
    `course_name` VARCHAR(128) NOT NULL COMMENT '课程名称（展示用快照）',
    `cover_url`   VARCHAR(512)          DEFAULT NULL COMMENT '封面',
    `price`       INT          NOT NULL COMMENT '加入时的价格（分），仅展示；下单时回源取最新价',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- ★ 同一门课只能加一次：重复加入由这个唯一索引兜底（不是靠应用层查一次再插）
    UNIQUE KEY `uk_user_course` (`user_id`, `course_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '购物车';

-- ==================== 订单 ====================
CREATE TABLE IF NOT EXISTS `trade_order` (
    `id`              BIGINT       NOT NULL COMMENT '订单id（前端预生成的雪花id）',
    `user_id`         BIGINT       NOT NULL COMMENT '下单用户',
    `status`          TINYINT      NOT NULL COMMENT '1-待支付 2-已支付 3-已关闭 5-已报名(免费) 6-退款中',
    `total_amount`    INT          NOT NULL COMMENT '商品总额（分）',
    `discount_amount` INT          NOT NULL DEFAULT 0 COMMENT '优惠金额（分），M4-3 起生效',
    `real_amount`     INT          NOT NULL COMMENT '实付金额（分）= 总额 - 优惠',
    `coupon_ids`      JSON                  DEFAULT NULL COMMENT '使用的优惠券id（M4-3）',
    `pay_channel`     VARCHAR(32)           DEFAULT NULL COMMENT '支付渠道码（M4-2）',
    `pay_order_no`    VARCHAR(64)           DEFAULT NULL COMMENT '支付单号（M4-2）',
    `message`         VARCHAR(255)          DEFAULT NULL COMMENT '关闭原因（超时/用户取消）',
    `create_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `pay_time`        DATETIME              DEFAULT NULL,
    `close_time`      DATETIME              DEFAULT NULL,
    `update_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 我的订单列表（按状态筛选）
    KEY `idx_user_status` (`user_id`, `status`),
    -- ★ 超时兜底扫描用：WHERE status = 1 AND create_time < ?
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单';

-- ==================== 订单明细 ====================
-- 一单多明细（一个订单可以买多门课），**不做拆单** ——
-- 常见实现的 placeOrder 实际也是这个结构，"订单拆单"是需求文档里的过度设计。
CREATE TABLE IF NOT EXISTS `trade_order_detail` (
    `id`                 BIGINT       NOT NULL COMMENT '明细id（雪花）',
    `order_id`           BIGINT       NOT NULL COMMENT '订单id',
    `user_id`            BIGINT       NOT NULL COMMENT '冗余用户id：查"我买过这门课吗"不用回表 join',
    `course_id`          BIGINT       NOT NULL COMMENT '课程id',
    `course_name`        VARCHAR(128) NOT NULL COMMENT '★ 快照：下单时的课程名',
    `cover_url`          VARCHAR(512)          DEFAULT NULL COMMENT '★ 快照：下单时的封面',
    `price`              INT          NOT NULL COMMENT '★ 快照：下单时课程单价（分）',
    `discount_amount`    INT          NOT NULL DEFAULT 0 COMMENT '该明细分摊到的优惠（分）',
    `real_pay_amount`    INT          NOT NULL COMMENT '该明细实付（分）',
    `valid_duration`     INT          NOT NULL COMMENT '★ 快照：下单时课程有效期（天）',
    `course_expire_time` DATETIME              DEFAULT NULL COMMENT '支付成功后算出的到期时间',
    -- ★ status 与订单同步冗余：这样"我买过这门课吗"只查这一张表
    --   （否则要 join trade_order 看父单状态）
    `status`             TINYINT      NOT NULL DEFAULT 1 COMMENT '1-待支付 2-已支付',
    `create_time`        DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_order` (`order_id`),
    -- 判重购 / 判断已购的核心索引
    KEY `idx_user_course_status` (`user_id`, `course_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细';
