-- ============================================================
-- fm_data —— M6-6：AI 对话指标接入数据中心
-- 可反复执行（先查 information_schema，已存在就跳过）
-- ============================================================

USE `fm_data`;

-- ==================== data_daily 加 AI 指标列 ====================
-- ★ 为什么 AI 指标要进"日汇总表"而不是只看 chat_message：
--   chat_message 是**明细**（一行一轮对话），而数据中心要回答的是
--   "这个平台每天在 AI 上花了多少、快不快、稳不稳"——
--   这类问题在明细表上要全表扫，而在日汇总表上是一行。
--
-- ★ 为什么"首字延迟"存的是**合计**而不是平均：
--   汇总表是每天**增量更新**的（Redis 计数攒着，定时任务累加），
--   存平均值就没法增量累加（平均值不能相加）。存合计 + 轮数，
--   平均值在任何时候都能算出来；反过来则算不回去。
--   ——这是"汇总表里只放可加量"的一条通用原则。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `data_daily`
        ADD COLUMN `chat_count` INT NOT NULL DEFAULT 0 COMMENT ''AI 对话轮数（M6-6）'' AFTER `assess_count`,
        ADD COLUMN `chat_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT ''AI 消耗 token 合计'' AFTER `chat_count`,
        ADD COLUMN `chat_first_ms_sum` BIGINT NOT NULL DEFAULT 0 COMMENT ''首字延迟合计（ms，配合 chat_count 算平均）'' AFTER `chat_tokens`,
        ADD COLUMN `chat_degraded_count` INT NOT NULL DEFAULT 0 COMMENT ''AI 降级次数（模型不可用）'' AFTER `chat_first_ms_sum`',
    'SELECT ''AI 指标列已存在，跳过'' AS note'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'fm_data'
      AND TABLE_NAME = 'data_daily'
      AND COLUMN_NAME = 'chat_count');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 校验
-- ============================================================
SELECT COLUMN_NAME AS `列`, COLUMN_TYPE AS `类型`, COLUMN_COMMENT AS `说明`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'fm_data'
  AND TABLE_NAME = 'data_daily'
ORDER BY ORDINAL_POSITION;
