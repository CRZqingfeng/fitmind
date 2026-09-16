-- ============================================================
-- FitMind AI 智能健身平台 —— M1 种子数据
-- ------------------------------------------------------------
-- 测试账号（密码统一为 123456，BCrypt 加密）
--   学员  13800010001 / 123456
--   教练  13800010002 / 123456
--   管理员 13800010003 / 123456
--
-- 同时为「测试学员」预置一份身体档案，便于直接验证读取接口。
--
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p1234 < data_m1_seed.sql
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 账号 —— 密码均为 123456
-- ------------------------------------------------------------
INSERT INTO `fm_user`.`user`
    (`id`, `username`, `cell_phone`, `password`, `status`, `type`, `creater`, `updater`)
VALUES
    (1, '13800010001', '13800010001', '$2a$10$RWMmvM7wRtl6jVY4n9doQ.Mw2WoOnMjvCNv7XdBBZSh7T/5MwqJlS', 1, 2, 0, 0),
    (2, '13800010002', '13800010002', '$2a$10$RWMmvM7wRtl6jVY4n9doQ.Mw2WoOnMjvCNv7XdBBZSh7T/5MwqJlS', 1, 3, 0, 0),
    (3, '13800010003', '13800010003', '$2a$10$RWMmvM7wRtl6jVY4n9doQ.Mw2WoOnMjvCNv7XdBBZSh7T/5MwqJlS', 1, 1, 0, 0)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`);

-- ------------------------------------------------------------
-- 2. 用户详情
-- ------------------------------------------------------------
INSERT INTO `fm_user`.`user_detail`
    (`id`, `type`, `name`, `gender`, `email`, `birthday`, `province`, `city`, `district`, `intro`, `role_id`, `creater`, `updater`)
VALUES
    (1, 2, '测试学员', 0, 'student@fitmind.dev', '2003-05-20', '江苏省', '南京市', '雨花台区', '想练出线条，先从零基础开始', 0, 0, 0),
    (2, 3, '测试教练', 0, 'coach@fitmind.dev',  '1995-03-11', '江苏省', '南京市', '鼓楼区',   '国家职业资格健身教练，擅长增肌与体态矫正', 0, 0, 0),
    (3, 1, '测试管理员', 0, 'admin@fitmind.dev',  '1990-01-01', '江苏省', '南京市', '玄武区',   '平台管理员', 0, 0, 0)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- ------------------------------------------------------------
-- 3. 身体档案（预置给测试学员）
--    BMI = 64 / (1.82^2) ≈ 19.3
-- ------------------------------------------------------------
INSERT INTO `fm_user`.`user_body_profile`
    (`user_id`, `height`, `weight`, `target_weight`, `bmi`, `body_fat_rate`,
     `waist`, `chest`, `arm`, `hip`, `goal_type`, `training_level`, `weekly_frequency`, `injury_history`)
VALUES
    (1, 182.0, 64.0, 72.0, 19.3, 14.0, 74.0, 92.0, 30.0, 92.0, 1, 1, 3, '无')
ON DUPLICATE KEY UPDATE `weight` = VALUES(`weight`);

-- ------------------------------------------------------------
-- 校验
-- ------------------------------------------------------------
SELECT u.`id`, u.`cell_phone` AS `手机号`,
       CASE u.`type` WHEN 1 THEN '管理员' WHEN 2 THEN '学员' WHEN 3 THEN '教练' ELSE '未知' END AS `类型`,
       d.`name` AS `姓名`, IF(p.`id` IS NULL, '无', CONCAT(p.`height`, 'cm/', p.`weight`, 'kg')) AS `档案`
FROM `fm_user`.`user` u
         LEFT JOIN `fm_user`.`user_detail` d ON d.`id` = u.`id`
         LEFT JOIN `fm_user`.`user_body_profile` p ON p.`user_id` = u.`id`
ORDER BY u.`id`;
