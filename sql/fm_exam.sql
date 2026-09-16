-- ============================================================
-- FitMind 体测库 fm_exam（M5）
-- ------------------------------------------------------------
-- 两张表：
--   body_assessment    体测记录（★ 不可变快照，每次体测一条）
--   action_assessment  动作考核（学员传视频 → 教练评分）
--
-- ★★ 与 fm_user.user_body_profile 的分工（这是本模块最关键的设计）：
--     user_body_profile = **当前状态**（覆盖式更新，回答"我现在什么样"）
--     body_assessment   = **历史轨迹**（每次一条、不可变，回答"我练得怎么样"）
--
--   两者都存身高体重，看起来冗余，但语义完全不同：
--   档案被"改"了就没有历史，而体测的价值恰恰在**能对比**。
--   （M4 的订单明细存课程快照也是同一个道理 —— 订单是交易凭证，
--     体测是训练凭证，都必须是"当时"的样子，不能跟着现在的课程/身体数据变。）
--
-- ★★ 另一条硬规则：所有"算出来的"字段（bmi / whr / bmr / tdee / 各项分级）
--     都由服务端计算后写入，**不接受前端传值** ——
--     改个请求体就能把自己的 BMI 说成正常，那评估就没有意义了。
--     （与退款金额、支付金额由服务端取值同一个原则。）
-- ============================================================

USE `fm_exam`;

-- ==================== 体测记录 ====================
CREATE TABLE IF NOT EXISTS `body_assessment`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`           BIGINT       NOT NULL COMMENT '用户id',

    -- ---------- 录入值（体测当时的快照） ----------
    `gender`            TINYINT      NOT NULL DEFAULT 2 COMMENT '性别快照：0-男 1-女 2-未知（★ 体脂/WHR/BMR 的标准都依赖它）',
    `height`            DECIMAL(5, 1) NOT NULL COMMENT '身高（cm）',
    `weight`            DECIMAL(5, 1) NOT NULL COMMENT '体重（kg）',
    `target_weight`     DECIMAL(5, 1)          DEFAULT NULL COMMENT '目标体重（kg）',
    `body_fat_rate`     DECIMAL(4, 1)          DEFAULT NULL COMMENT '体脂率（%）；可为空 → 该项不参与评估',
    `waist`             DECIMAL(4, 1)          DEFAULT NULL COMMENT '腰围（cm）',
    `chest`             DECIMAL(4, 1)          DEFAULT NULL COMMENT '胸围（cm）',
    `arm`               DECIMAL(4, 1)          DEFAULT NULL COMMENT '臂围（cm）',
    `hip`               DECIMAL(4, 1)          DEFAULT NULL COMMENT '臀围（cm）',

    -- ---------- 服务端计算（★ 不接前端传值） ----------
    `bmi`               DECIMAL(4, 1)          DEFAULT NULL COMMENT 'BMI = 体重 / 身高(m)²',
    `bmi_level`         TINYINT                DEFAULT NULL COMMENT 'BMI 分级：1-偏瘦 2-正常 3-超重 4-肥胖（★ 中国标准）',
    `fat_level`         TINYINT                DEFAULT NULL COMMENT '体脂分级：1-偏低 2-运动员 3-正常 4-偏高 5-肥胖（★ 男女标准不同）',
    `whr`               DECIMAL(4, 2)          DEFAULT NULL COMMENT '腰臀比 = 腰围 / 臀围',
    `whr_level`         TINYINT                DEFAULT NULL COMMENT '腰臀比分级：1-正常 2-中心性肥胖（★ 男女阈值不同）',
    `bmr`               INT                    DEFAULT NULL COMMENT '基础代谢 kcal/天（★ Mifflin-St Jeor 公式）',
    `tdee`              INT                    DEFAULT NULL COMMENT '每日总消耗 kcal/天 = BMR × 活动系数',
    `lean_mass`         DECIMAL(5, 1)          DEFAULT NULL COMMENT '去脂体重（kg）= 体重 × (1 - 体脂率)',
    `fat_mass`          DECIMAL(5, 1)          DEFAULT NULL COMMENT '脂肪重量（kg）= 体重 × 体脂率',
    `score`             TINYINT                DEFAULT NULL COMMENT '综合评分 0-100（统一算法，这样不同时间的体测才能连成曲线）',

    -- ---------- 结论 ----------
    `goal_type`         TINYINT                DEFAULT NULL COMMENT '本次的目标：1-增肌 2-减脂 3-塑形 4-保持',
    `summary`           VARCHAR(500)           DEFAULT NULL COMMENT '一句话结论（给人看的）',
    `suggestion`        VARCHAR(1000)          DEFAULT NULL COMMENT '★ 训练建议（结构化文本：热量目标 + 训练侧重 + 风险提示）',

    `assess_time`       DATETIME     NOT NULL COMMENT '体测时间（用户可补录，故与 create_time 分开）',
    `create_time`       DATETIME              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 查"我的体测历史 / 趋势"走这个索引
    KEY `idx_user_time` (`user_id`, `assess_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '体测记录（不可变快照）';

-- ==================== 动作考核 ====================
-- 学员提交某个训练动作的视频 → 教练观看后打分。
-- ★ 为什么是人工评分而不是规则自动判分：
--   动作做得标不标准，本质是视觉判断（关节角度、节奏、代偿），
--   规则做不到；而"知识类"的自动判分 M2-2c 已经做过了（服务端判分 + 答对发积分）。
CREATE TABLE IF NOT EXISTS `action_assessment`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT      NOT NULL COMMENT '学员id',
    `course_id`    BIGINT               DEFAULT NULL COMMENT '所属课程id',
    `cata_id`      BIGINT               DEFAULT NULL COMMENT '所属训练动作（目录）id',
    `media_id`     BIGINT               DEFAULT NULL COMMENT '媒资id（复用 fm-media）',
    `video_url`    VARCHAR(512)         DEFAULT NULL COMMENT '视频地址',
    `remark`       VARCHAR(500)         DEFAULT NULL COMMENT '学员自述',
    `status`       TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1-待评分 2-已评分',
    `score`        TINYINT              DEFAULT NULL COMMENT '教练评分 0-100',
    `comment`      VARCHAR(500)         DEFAULT NULL COMMENT '教练评语',
    `coach_id`     BIGINT               DEFAULT NULL COMMENT '评分教练id',
    `submit_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `review_time`  DATETIME             DEFAULT NULL COMMENT '评分时间',
    `create_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`, `submit_time`),
    -- 管理端"待我评分"列表走这个索引
    KEY `idx_status` (`status`, `submit_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '动作考核';

-- ============================================================
-- 校验
-- ============================================================
SELECT TABLE_NAME AS `表`, TABLE_COMMENT AS `说明`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'fm_exam'
ORDER BY TABLE_NAME;
