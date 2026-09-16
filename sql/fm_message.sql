-- ============================================================
-- FitMind 消息库 fm_message（M4-4 从零补全）
-- ------------------------------------------------------------
-- 四张表：
--   notice_template  通知模板（code 唯一，content 支持 {占位符}）
--   public_notice    ★ 公告箱（pull 模式：全员公告只写一行）
--   user_inbox       ★ 用户信箱（push + 公告展开的落地）
--   sms_record       短信下发记录（Mock 留痕，验收脚本直查这张表）
--
-- ★★ 与常见实现的核心差异（三条，都在下面各表的注释里展开）：
--   1. user_inbox 加 (user_id, source_type, source_id) 唯一索引 ——
--      常见实现这张表**一个唯一索引都没有**：MQ 重投会重复通知，
--      公告拉取靠 push_time 去重（同一毫秒的两条公告会重复插入）。
--   2. 公告拉取不再依赖时间戳猜测，而是精确到"哪一条公告"（source_id）。
--   3. 短信增加 sms_record 留痕表 —— 常见实现只打日志，
--      没有"短信到底发出去没有"的凭证，验收只能看日志。
--      我们让验收脚本直查表，与全程"查真实数据"的验收原则一致。
-- ============================================================

USE `fm_message`;

-- ==================== 通知模板 ====================
-- 文案改了不用改代码：运营在管理端配置，服务端只负责把 {占位符} 换掉。
CREATE TABLE IF NOT EXISTS `notice_template`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`            VARCHAR(64)  NOT NULL COMMENT '模板名称（给人看的）',
    `code`            VARCHAR(64)  NOT NULL COMMENT '★ 模板代号（给程序用的，如 purchase-success）',
    `type`            TINYINT      NOT NULL DEFAULT 0 COMMENT '通知类型：0-系统通知 1-笔记通知 2-问答通知 3-其它通知 4-私信',
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '模板状态：0-草稿 1-使用中 2-停用',
    `title`           VARCHAR(128) NOT NULL COMMENT '通知标题（支持 {占位符}）',
    `content`         VARCHAR(1024) NOT NULL COMMENT '通知内容（支持 {占位符}）',
    `is_sms_template` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否同时发短信：0-否 1-是',
    `creater`         BIGINT                DEFAULT NULL,
    `updater`         BIGINT                DEFAULT NULL,
    `create_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 代号唯一：业务代码按 code 找模板，不按 id（id 是自增的，各环境不一致）
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '通知模板';

-- 预置模板（可反复执行）
-- ⚠️ 购课通知的金额是 M5 才补上的：M4-2 时 order.pay 事件只带订单号与课程id，
--    文案里就拿不到金额（当时只能写"已成功购买XX"）。M5 给数据中心统计 GMV 时
--    顺手给消息体加了 amount，于是这条通知也能带上金额了 ——
--    **一个字段加了，两个消费方受益**（数据中心能算成交额、通知能显示金额）。
INSERT INTO `notice_template`(`name`, `code`, `type`, `status`, `title`, `content`, `is_sms_template`)
VALUES ('购课成功通知', 'purchase-success', 0, 1, '课程购买成功',
        '你已成功购买《{courseName}》，实付 {amount} 元。课程已加入我的训练，快去开始第一课吧！', 0),
       ('退款到账通知', 'refund-success', 0, 1, '退款已到账',
        '《{courseName}》的退款 {amount} 元已原路退回，预计 1-3 个工作日到账。', 1),
       -- ⚠️ 这条模板**故意不带动作名**：动作名在 fm-course（课程大纲的节点名），
       --    而为了一条通知去查课程服务、把课程服务变成通知链路的依赖，不划算。
       --    文案里用"你提交的训练动作"已经够用 —— 学员自己知道刚提交了哪个。
       ('动作考核结果通知', 'action-reviewed', 0, 1, '你的动作考核结果已出',
        '你提交的训练动作已由教练评分：{score} 分。教练点评：{comment}', 0)
    ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `title` = VALUES(`title`),
                            `content` = VALUES(`content`), `is_sms_template` = VALUES(`is_sms_template`);

-- ==================== 公告箱（★ pull 模式） ====================
-- 全员公告**只在这里写一行**，不逐用户写 user_inbox。
-- 用户来查消息时才把"他上次看到之后新增的公告"展开进自己的信箱。
--
-- 为什么这么设计：给 10 万用户发一条"双十一半价"，全量推送要写 10 万行，
-- 而其中 99% 的用户根本不会看这条公告。pull 模式把写的代价
-- 转移给"真正会看的少数人"，是最划算的一笔交易。
CREATE TABLE IF NOT EXISTS `public_notice`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `type`        TINYINT      NOT NULL DEFAULT 0 COMMENT '通知类型（同 notice_template.type）',
    `title`       VARCHAR(128) NOT NULL COMMENT '公告标题',
    `content`     VARCHAR(1024) NOT NULL COMMENT '公告内容',
    `push_time`   DATETIME     NOT NULL COMMENT '发布时间（用户按它判断"哪些是我还没看过的"）',
    `expire_time` DATETIME              DEFAULT NULL COMMENT '失效时间（过期公告不再展开给用户）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_push_time` (`push_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '全员公告（pull 模式）';

-- ==================== 用户信箱 ====================
-- 两个来源：
--   ① 定向通知（购课成功、退款到账）—— 直接 push 进来
--   ② 公告展开 —— 用户拉取时从 public_notice 复制进来
--
-- ★★ 唯一索引 uk_user_source 是本表最重要的一行：
--    它让"同一业务事件只通知一次"这件事由**数据库**保证，
--    而不是靠"消息只会投递一次"这个错误假设。
--    - 定向通知：source_type=2, source_id=业务id（如订单id、退款申请id）
--    - 公告展开：source_type=1, source_id=public_notice.id
--    MQ 重投 → 插入冲突 → 忽略；公告重复拉取 → 同一条公告不会再展开一次。
CREATE TABLE IF NOT EXISTS `user_inbox`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '收件人',
    `type`        TINYINT      NOT NULL DEFAULT 0 COMMENT '通知类型（同 notice_template.type）',
    `title`       VARCHAR(128)          DEFAULT NULL COMMENT '通知标题',
    `content`     VARCHAR(1024) NOT NULL COMMENT '通知内容（占位符已渲染）',
    `is_read`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0-未读 1-已读',
    `publisher`   BIGINT                DEFAULT NULL COMMENT '发送者id；空/0 表示系统',
    `push_time`   DATETIME     NOT NULL COMMENT '推送时间',
    `expire_time` DATETIME              DEFAULT NULL COMMENT '过期时间（过期不再展示）',
    -- ★ 幂等来源标识
    `source_type` TINYINT      NOT NULL DEFAULT 2 COMMENT '来源类型：1-公告 2-业务通知 3-私信',
    `source_id`   BIGINT       NOT NULL COMMENT '来源id：公告id / 业务id（订单id、退款申请id…）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- ★★ 幂等的地基：同一用户、同一来源、只允许一条
    UNIQUE KEY `uk_user_source` (`user_id`, `source_type`, `source_id`),
    -- 查"我的消息 + 未读数"走这个索引
    KEY `idx_user_read_time` (`user_id`, `is_read`, `push_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '用户信箱';

-- ==================== 短信下发记录（Mock 留痕） ====================
-- 为什么要有这张表：短信是"发出去就收不回来"的动作，没有留痕就无从对账。
-- 常见实现只打日志，于是"这条短信到底发没发"只能翻日志文件。
-- 有了这张表，验收脚本可以直查（我们全程坚持"查中间件的真实数据"），
-- 将来排查"用户说没收到短信"也有依据。
CREATE TABLE IF NOT EXISTS `sms_record`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT                DEFAULT NULL COMMENT '接收用户id（可空：注册验证码时还没有用户）',
    `phone`         VARCHAR(20)  NOT NULL COMMENT '手机号',
    `template_code` VARCHAR(64)           DEFAULT NULL COMMENT '短信模板代号',
    `content`       VARCHAR(512) NOT NULL COMMENT '实际下发的短信内容（渲染后）',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '下发结果：1-成功 2-失败',
    `result_msg`    VARCHAR(255)          DEFAULT NULL COMMENT '渠道返回描述 / 失败原因',
    `create_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_phone` (`phone`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '短信下发记录';

-- ==================== 关于"定时发布公告" ====================
-- 常见实现有一张 notice_task 表（任务表 + max_times/interval 重复执行 + partial 部分用户），
-- 由 publishNoticeJob 定时扫出来执行。**我们不需要这张表**，理由值得记一下：
--
--   notice_task 存在的根本原因是**push 模式必须定时**：
--   如果你要在"明早 10 点"给所有用户各写一行收件箱记录，
--   就必须有一个定时器在那时候跑。
--
--   但我们的公告是 **pull 模式** —— 用户来查消息时才展开。
--   于是"定时发布"退化成"把 push_time 写成未来的时间点"：
--   expandToInbox 里本来就有 `push_time <= now` 这个条件
--   （用来过滤未到时间的公告），到点它自然就可见了。
--
--   ★ 一行表结构省掉一整张表 + 一个定时任务 + 一套"重复执行"的状态机。
--     这**不是偷懒**，是 pull 模式顺带买到的东西。
--
--   （保留 publishNoticeJob 的执行器分组另有用途：见 messageCleanupJob）

-- ============================================================
-- 校验
-- ============================================================
SELECT TABLE_NAME    AS `表`,
       TABLE_COMMENT AS `说明`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'fm_message'
ORDER BY TABLE_NAME;

-- 确认幂等索引在
SELECT INDEX_NAME AS `索引`, COLUMN_NAME AS `字段`, NON_UNIQUE AS `非唯一`
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'fm_message'
  AND TABLE_NAME = 'user_inbox'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
