-- ============================================================
-- fm_learning 库 · M10-1（社区页「答疑广场」）增量脚本
-- ------------------------------------------------------------
-- **只有索引，没有表结构变更** —— 广场要的能力全部复用已有表和字段：
--   · 问题表：`scope=ALL` 全站分页 + 「待回答」（`answer_times = 0`）+ 按回答数排序
--   · 笔记表：`scope=ALL` 全站分页
--
-- ★ 为什么需要索引：广场的两条查询都长这样
--       WHERE hidden = 0 ORDER BY create_time DESC
--   而现有索引只有 `course_id` / `section_id` / `user_id` ——
--   广场**恰恰不带这两个条件**，于是退化成全表扫描 + filesort。
--   复合索引 `(hidden, create_time)` 能让"过滤 hidden + 按时间排序"一次走完
--   （等值条件 `hidden` 放左边，范围/排序条件 `create_time` 放右边）。
--
-- ⚠️ 演示数据量（几十条）其实不建也跑得动 —— 但"跑得动"不是"写对了"：
--    这两张表是**会随用户增长的表**，而广场是默认入口（每次打开社区页都会查）。
--
-- 可反复执行：先查 information_schema，已存在就跳过
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p < sql/fm_learning_m10_1.sql
-- ============================================================

USE `fm_learning`;

-- ==================== ① 互动提问：广场分页 ====================
SET @ddl = IF(EXISTS(SELECT 1
                     FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = 'fm_learning'
                       AND TABLE_NAME = 'interaction_question'
                       AND INDEX_NAME = 'idx_hidden_create'),
    'SELECT ''interaction_question.idx_hidden_create 已存在，跳过'' AS note',
    'ALTER TABLE `interaction_question` ADD INDEX `idx_hidden_create` (`hidden`, `create_time`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ==================== ② 笔记：广场分页 ====================
SET @ddl = IF(EXISTS(SELECT 1
                     FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = 'fm_learning'
                       AND TABLE_NAME = 'note'
                       AND INDEX_NAME = 'idx_hidden_create'),
    'SELECT ''note.idx_hidden_create 已存在，跳过'' AS note',
    'ALTER TABLE `note` ADD INDEX `idx_hidden_create` (`hidden`, `create_time`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ==================== 校验 ====================
SELECT CONCAT(table_name, ' → ', index_name, ' (',
              GROUP_CONCAT(column_name ORDER BY seq_in_index), ')') AS `新增索引`
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'fm_learning'
  AND INDEX_NAME = 'idx_hidden_create'
GROUP BY table_name, INDEX_NAME;
