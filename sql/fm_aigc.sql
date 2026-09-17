-- ============================================================
-- fm_aigc —— AI 健身教练（M6）
-- 可反复执行（IF NOT EXISTS + ON DUPLICATE KEY）
-- ============================================================

CREATE DATABASE IF NOT EXISTS `fm_aigc`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `fm_aigc`;

-- ==================== 会话 ====================
-- 会话元数据。消息正文不在这里（在 chat_message / Redis 热消息）。
--
-- ★ 唯一索引为什么是 (user_id, session_id) 而不是 session_id 单列：
--   ① sessionId 由前端生成，全局唯一只是"约定"而不是"保证"；
--   ② 查询永远是"我的会话"，走 (user_id, session_id) 天然带上了越权防护 ——
--      别人拿到你的 sessionId 也查不到（WHERE 里必须带自己的 user_id）。
--   这两个理由里第 ② 个更重要：**把鉴权做进索引，就不可能忘**。
CREATE TABLE IF NOT EXISTS `chat_session`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  VARCHAR(64)  NOT NULL COMMENT '会话id（前端生成或服务端回填）',
    `user_id`     BIGINT       NOT NULL COMMENT '归属用户',
    `title`       VARCHAR(128)          DEFAULT NULL COMMENT '会话标题（取首轮提问的前 N 字）',
    `msg_count`   INT          NOT NULL DEFAULT 0 COMMENT '消息条数（含提问与回答）',
    -- M6-5 加：会话列表展示"标题 + 最后一句"。标题取自**首轮**提问，
    -- 聊了几十轮后对用户毫无辨识度，真正能帮他认出会话的是最后一句在聊什么。
    -- 冗余在这里而不是实时去 Redis 取：列表是分页查询，20 个会话就是 20 次往返，
    -- 而且 Redis 那份有 TTL、会被压缩清掉 —— 列表展示不该依赖会过期的缓存。
    `last_message` VARCHAR(255)         DEFAULT NULL COMMENT '最后一条消息预览（截断后的单行文本，M6-5）',
    `last_active` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间（会话列表按它倒序）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_session` (`user_id`, `session_id`),
    KEY `idx_user_active` (`user_id`, `last_active`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 会话';

-- ==================== 消息 ====================
-- ★ 为什么消息要落库（常见实现只存 Redis 不落库）：
--   ① 翻历史记录时**工具卡片要能重渲染**（卡片参数存在 params 列）
--   ② **token 消耗与延迟是可观测性的原料**（prompt_tokens/first_token_ms/total_ms）
--       —— 这些数字可以直接喂给 M5 的数据中心做"AI 使用分析"
--   ③ 出了幻觉要能复盘（把当时真正进 prompt 的东西和回答放在一起看）
--
-- 问与答都用 t_message 的同一张表，靠 role 区分（user / assistant）。
-- 不用"一条问答一行"的宽表：多轮对话里轮次边界会因为"打断重答"而不清晰。
CREATE TABLE IF NOT EXISTS `chat_message`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`        VARCHAR(64) NOT NULL COMMENT '会话id',
    `user_id`           BIGINT      NOT NULL COMMENT '归属用户（冗余，避免查消息还要 join 会话）',
    `request_id`        VARCHAR(64)          DEFAULT NULL COMMENT '本轮请求id（助手消息用它关联同一轮的提问与 SSE 分片）',
    `role`              VARCHAR(16) NOT NULL COMMENT '角色：user / assistant',
    `content`           MEDIUMTEXT           DEFAULT NULL COMMENT '正文',
    `agent_type`        VARCHAR(32)          DEFAULT NULL COMMENT '本条回答由哪个 Agent 产出（M6-2 起有值）',
    `provider`          VARCHAR(16)          DEFAULT NULL COMMENT '模型通道：deepseek / mock',
    -- 可观测列：都是"本轮"的指标，写在助手消息上
    `prompt_tokens`     INT                  DEFAULT NULL COMMENT '输入 token',
    `completion_tokens` INT                  DEFAULT NULL COMMENT '输出 token',
    `first_token_ms`    INT                  DEFAULT NULL COMMENT '首字延迟（毫秒）—— 流式体验的核心指标',
    `total_ms`          INT                  DEFAULT NULL COMMENT '总耗时（毫秒）',
    `tool_calls`        JSON                 DEFAULT NULL COMMENT '本轮调用过的工具（名称/参数/结果摘要）',
    `params`            JSON                 DEFAULT NULL COMMENT '卡片数据（前端翻历史时用它重渲染，M6-4）',
    `create_time`       DATETIME             DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 翻某会话的历史：WHERE session_id=? ORDER BY id，走这个索引
    KEY `idx_session_id` (`session_id`, `id`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    -- ★ 幂等兜底：同一轮请求的助手消息只该有一条。
    --   SSE 重连、用户刷新页面重发、客户端重试都可能重复落库；
    --   靠这个唯一索引挡住，而不是靠"代码里记得判一下"。
    --   （与积分的 uk_user_type_biz、站内信的 uk_user_source 同一套思路）
    UNIQUE KEY `uk_request_role` (`request_id`, `role`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 会话消息';

-- ============================================================
-- 校验
-- ============================================================
SELECT TABLE_NAME AS `表`, TABLE_COMMENT AS `说明`, TABLE_ROWS AS `行数`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'fm_aigc'
ORDER BY TABLE_NAME;
