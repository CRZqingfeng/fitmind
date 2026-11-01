-- ============================================================
-- FitMind AI 智能健身平台 —— 数据库初始化
-- ------------------------------------------------------------
-- 原则：一服务一库（微服务数据隔离），对标主流微服务实践
-- 字符集：utf8mb4 / utf8mb4_general_ci
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p1234 < 00-create-databases.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. 认证授权
CREATE DATABASE IF NOT EXISTS `fm_auth`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 2. 用户与身体档案
CREATE DATABASE IF NOT EXISTS `fm_user`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 3. 训练课程体系
CREATE DATABASE IF NOT EXISTS `fm_course`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 4. 训练计划 / 打卡 / 积分 / 签到 / 排行榜
CREATE DATABASE IF NOT EXISTS `fm_learning`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 5. 体测评估 / 动作考核
CREATE DATABASE IF NOT EXISTS `fm_exam`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 6. 课程搜索
CREATE DATABASE IF NOT EXISTS `fm_search`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 7. 媒资（训练视频）
CREATE DATABASE IF NOT EXISTS `fm_media`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 8. 交易（购物车 / 订单）
CREATE DATABASE IF NOT EXISTS `fm_trade`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 9. 支付
CREATE DATABASE IF NOT EXISTS `fm_pay`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 10. 优惠券 / 会员卡
CREATE DATABASE IF NOT EXISTS `fm_promotion`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 11. 社区问答 / 点赞
CREATE DATABASE IF NOT EXISTS `fm_remark`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 12. 数据中心 / 统计
CREATE DATABASE IF NOT EXISTS `fm_data`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 13. AI 健身教练
CREATE DATABASE IF NOT EXISTS `fm_aigc`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 14. 消息通知（fm-message，预留）
CREATE DATABASE IF NOT EXISTS `fm_message`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 15. 训练计划 / 饮食计划（fm-plan，M11-2 起）
-- ⚠️ 与「4. fm_learning」不是一回事：那边是**课程学习计划**（某门课每周练几次），
--    这边是**训练编排**（周一练胸：深蹲 4 组 × 8 次 + 组休 90 秒）。
--    两个"计划"同名不同义，建表语句必须分库，否则三个月后没人分得清。
CREATE DATABASE IF NOT EXISTS `fm_plan`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- ============================================================
-- 校验
-- ============================================================
SELECT SCHEMA_NAME AS `数据库`, DEFAULT_CHARACTER_SET_NAME AS `字符集`
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME LIKE 'fm\_%'
ORDER BY SCHEMA_NAME;
