-- ============================================================
-- fm_aigc —— M6-5（会话记忆与打断）增量脚本
-- 可反复执行：先查 information_schema，已存在就跳过
-- ============================================================

USE `fm_aigc`;

-- ==================== chat_session 加"最后一条消息预览" ====================
-- 会话列表页展示"标题 + 最后一句"。标题取自**首轮**提问，
-- 聊了几十轮之后它对用户毫无辨识度（"今天该练什么"这种标题到处都一样），
-- 真正能帮用户认出"是哪一个会话"的是最后一句在聊什么。
--
-- ★ 为什么不在列表页实时去 Redis 取（那里明明有最后一条）：
--   ① 列表是分页查询，20 个会话就是 20 次 Redis 往返；
--   ② Redis 那份有 TTL、还会被压缩清掉 —— 列表展示不该依赖一个**会过期的缓存**。
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `chat_session`
        ADD COLUMN `last_message` VARCHAR(255) NULL
        COMMENT ''最后一条消息预览（会话列表用，截断后的单行文本）'' AFTER `msg_count`',
    'SELECT ''last_message 已存在，跳过'' AS note'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'fm_aigc'
      AND TABLE_NAME = 'chat_session'
      AND COLUMN_NAME = 'last_message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 校验
-- ============================================================
SELECT COLUMN_NAME AS `列`, COLUMN_TYPE AS `类型`, COLUMN_COMMENT AS `说明`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'fm_aigc'
  AND TABLE_NAME = 'chat_session'
ORDER BY ORDINAL_POSITION;
