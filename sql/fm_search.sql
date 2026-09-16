-- ============================================================
-- fm_search：搜索服务数据库
-- ------------------------------------------------------------
-- 搜索服务只有「用户兴趣」需要落 MySQL，课程索引数据全在 Elasticsearch。
-- ============================================================
CREATE DATABASE IF NOT EXISTS `fm_search`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `fm_search`;

-- ==================== 用户兴趣 ====================
-- id 就是 user_id（一人一条），沿用常见实现设计。
-- interests 用逗号分隔的分类 id（二级分类 = 训练部位），
-- 因为一个用户可能同时关注多个部位，而这里不需要做反查（不需要"查哪些人对胸部感兴趣"），
-- 单列存储比拆关联表简单得多。
CREATE TABLE IF NOT EXISTS `interests` (
    `id`          BIGINT       NOT NULL COMMENT '用户id，与 fm_user.user.id 一致',
    `interests`   VARCHAR(255) NOT NULL COMMENT '关注的分类id，逗号分隔（多为二级分类=训练部位）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户兴趣';
