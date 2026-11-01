-- ============================================================
-- fm_plan：训练计划模块数据库（M11-2 / P0）
-- ------------------------------------------------------------
-- ★ 读这张库之前先记住一句话：**改出来的进模板表，做出来的进日志表**。
--   模板：plan / plan_day / plan_day_item / exercise / exercise_favorite
--   日志：user_plan / workout_session / workout_set_log
--   改计划（plan_day_item）不会、也不允许改到任何一条历史训练记录。
--
-- ★ 三层参数不许混（改错这层是数据事故，不是 bug）：
--   exercise       这个动作本身是什么（难度 / 肌群 / 步骤）—— 内容，全站共享
--   plan_day_item  这个计划里怎么安排（几组几次多重休息多久）—— 计划，用户可改
--   workout_set_log这组我今天到底推了几个 —— 事实，永不回改
--
-- ★ 种子数据来源（一个字段都没手写）：
--   动作：Sanotsu/free-fitness 中文版 assets/datasets/free-exercise-db-zh.json（873 条）
--         取的 20 条 id 均实测存在；name / instructions / images / level 原样入库。
--         数据集**没有** heat、lumbar_pressure、gif_url、中文器械名 → 这些列留 NULL，
--         不猜、不估、不翻译。图片存完整 URL（ghfast 前缀，直连 raw 本机超时）。
--   计划：3 个官方示例，只引用上面 20 个动作，组数次数由本文件生成时算好。
--
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p1234 < sql/fm_plan.sql
-- ============================================================
CREATE DATABASE IF NOT EXISTS `fm_plan`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `fm_plan`;

SET NAMES utf8mb4;


-- ==================== 1. plan【模板】计划 ====================
-- 官方 / 用户自建 / AI 生成三种来源共用一张表，靠 source 区分，不建三张表。
-- ★ owner_id=0 表示官方：官方计划任何用户都能"执行本计划"，但只有 owner 能编辑。
-- ★ ref_count 只增不减（热度），不拿它做业务判断。
CREATE TABLE IF NOT EXISTS `plan` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`               VARCHAR(64)  NOT NULL DEFAULT '未命名计划' COMMENT '计划名（默认值由后端给，前端不拼）',
    `summary`             VARCHAR(512)          DEFAULT NULL COMMENT '一句话描述（详情页副标题）',
    `cover_url`           VARCHAR(512)          DEFAULT NULL COMMENT '封面图',
    `goal`                VARCHAR(16)  NOT NULL DEFAULT '增肌' COMMENT '训练目标：增肌 / 减脂',
    `difficulty`          TINYINT      NOT NULL DEFAULT 0 COMMENT '难度：0-新手 1-进阶 2-专业',
    `train_days_per_week` TINYINT      NOT NULL DEFAULT 0 COMMENT '一周几练（列表卡片展示，写时按 plan_day 数量回填）',
    `kcal_estimate`       INT          NOT NULL DEFAULT 0 COMMENT '预估千卡；0=暂无估算（缺 exercise.heat，P1 再接）',
    `equipment_json`      VARCHAR(255)          DEFAULT NULL COMMENT '器械清单 JSON，如 ["杠铃","哑铃"]',
    `source`              TINYINT      NOT NULL DEFAULT 1 COMMENT '来源：0-官方 1-自建 2-AI',
    `status`              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-草稿 1-已发布 2-已下架',
    `ref_count`           INT          NOT NULL DEFAULT 0 COMMENT '被执行次数（热度，只增）',
    `owner_id`            BIGINT       NOT NULL DEFAULT 0 COMMENT '归属用户，0=官方',
    `create_time`         DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_owner_source` (`owner_id`, `source`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【模板】训练计划（改这里不影响历史训练记录）';

-- ==================== 2. plan_day【模板】训练日 ====================
-- ★ day_index = "第几练"（从 1 开始，同一计划内唯一）；sort = 展示顺序。
--   两者 P0 同值，但语义不同：以后允许"第 5 练排在第 1 位显示"。
-- ★ weekday 可空：自建计划允许不绑星期。空 = 该训练日不会出现在「今日训练」里，
--   前端添加训练日时强制选周一~周日，避免用户建完发现"今天没安排"。
CREATE TABLE IF NOT EXISTS `plan_day` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `plan_id`     BIGINT      NOT NULL COMMENT '计划id',
    `day_index`   TINYINT     NOT NULL COMMENT '第几练，从 1 开始',
    `weekday`     TINYINT              DEFAULT NULL COMMENT '星期几：1~7（周一=1），null=不绑定',
    `name`        VARCHAR(32) NOT NULL DEFAULT '未命名训练日' COMMENT '训练日名，如"周一练胸"',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '展示顺序',
    `est_minutes` INT         NOT NULL DEFAULT 0 COMMENT '预计时长（分钟，后端算：Σ组数×单组秒+Σ组休）',
    `create_time` DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_day_index` (`plan_id`, `day_index`),
    KEY `idx_plan_sort` (`plan_id`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【模板】计划里的训练日（周一练胸这一层）';

-- ==================== 3. plan_day_item【模板】训练日里的动作项 ====================
-- ★★ 组数 / 次数 / 重量 / 组休全在这一层，**不放 exercise**。
--   放 exercise 的后果：用户把"卧推 4 组"改成 5 组，别人（和去年的自己）跟着变。
-- ★ uk_item_sort 保证同一训练日内序号不重；删除动作项后要把 sort 重排（见 ItemService）。
CREATE TABLE IF NOT EXISTS `plan_day_item` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `plan_day_id`  BIGINT       NOT NULL COMMENT '训练日id',
    `exercise_id`  BIGINT       NOT NULL COMMENT '动作id，关联同库 exercise.id',
    `sort`         INT          NOT NULL DEFAULT 0 COMMENT '训练日内的顺序，从 1 开始',
    `group_type`   TINYINT      NOT NULL DEFAULT 0 COMMENT '组类型：0-正式 1-热身 2-拉伸',
    `sets`         TINYINT      NOT NULL DEFAULT 4 COMMENT '组数',
    `reps`         TINYINT      NOT NULL DEFAULT 8 COMMENT '每组次数（计时型为 0）',
    `duration_sec` INT          NOT NULL DEFAULT 0 COMMENT '计时型单组秒数（平板/跳绳），次数型为 0',
    `weight_kg`    DECIMAL(6,2)          DEFAULT NULL COMMENT '建议重量，**单位 kg**（界面显示斤时 ×2，只在一处换算）',
    `rest_sec`     INT          NOT NULL DEFAULT 60 COMMENT '组间休息秒数',
    `create_time`  DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_sort` (`plan_day_id`, `sort`),
    KEY `idx_exercise` (`exercise_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【模板】训练日里的动作项（几组几次多重休息多久）';

-- ==================== 4. exercise【内容】动作库 ====================
-- ★ 参与筛选的字段必须列化并建索引：category（左侧分类）+ body_part（部位）+ equipment（器械 chips）。
--   肌群 / 步骤不参与筛选，用 JSON 存 —— 这样左侧栏和 chips 是 index range scan，不是全表扫。
-- ★ heat / lumbar_pressure / gif_url 数据集里没有 → P0 允许 NULL，前端显示"—"，
--   不许为了"看起来完整"填 0（0 会被当成"不伤腰"，这是安全问题）。
CREATE TABLE IF NOT EXISTS `exercise` (
    `id`                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`                   VARCHAR(64)  NOT NULL COMMENT '中文名',
    `en_name`                VARCHAR(128)          DEFAULT NULL COMMENT '英文名（由数据集 id 还原，非权威译名）',
    `category`               VARCHAR(16)  NOT NULL COMMENT '分类：热身 / 拉伸 / 有氧 / 力量',
    `body_part`              VARCHAR(16)  NOT NULL COMMENT '部位：胸/背/腿/肩/二头/三头/小腿/前臂/臀部/腹部/斜方肌/其他',
    `equipment`              VARCHAR(16)  NOT NULL COMMENT '器械：徒手/哑铃/杠铃/史密斯/悍马机/固定器械/弹力带/其他',
    `difficulty`             TINYINT      NOT NULL DEFAULT 1 COMMENT '难度 1~5（由数据集 level 映射：beginner1/intermediate3/expert5）',
    `heat`                   INT                   DEFAULT NULL COMMENT '热量参考（数据集无 → NULL，不估）',
    `lumbar_pressure`        TINYINT               DEFAULT NULL COMMENT '腰椎压力 1~5（数据集无 → NULL，不估）',
    `image_url`              VARCHAR(512)          DEFAULT NULL COMMENT '示意图 URL',
    `gif_url`                VARCHAR(512)          DEFAULT NULL COMMENT '动图 URL（数据集无 → NULL）',
    `primary_muscles_json`   VARCHAR(255)          DEFAULT NULL COMMENT '主要肌群 JSON 数组（原样存数据集值）',
    `secondary_muscles_json` VARCHAR(255)          DEFAULT NULL COMMENT '次要肌群 JSON 数组',
    `steps_json`             TEXT                  COMMENT '动作步骤 JSON 数组（数据集 instructions 原样）',
    `tips`                   VARCHAR(512)          DEFAULT NULL COMMENT '提示（数据集无此字段 → NULL）',
    `status`                 TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-下架 1-上架',
    `create_time`            DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`            DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_category_body` (`category`, `body_part`),
    KEY `idx_equipment` (`equipment`),
    KEY `idx_body_part` (`body_part`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【内容】动作库（876 条开源数据集，P0 只入 20 条种子）';

-- ==================== 5. user_plan【日志】我加入/正在执行的计划 ====================
-- ★★ 这是"实例"，和 plan（模板）分开：官方计划被 1 万个人执行，也只有 1 行 plan。
-- ★★ 唯一索引 uk_user_plan：重复点「执行本计划」不插新行，而是**复用这行并重置 start_date**
--     —— 用 INSERT … ON DUPLICATE KEY UPDATE 实现，不写 select-then-insert（有并发窗口）。
CREATE TABLE IF NOT EXISTS `user_plan` (
    `id`                BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`           BIGINT   NOT NULL COMMENT '用户id',
    `plan_id`           BIGINT   NOT NULL COMMENT '计划id',
    `start_date`        DATE     NOT NULL COMMENT '开始日期（重新执行时重置为今天）',
    `status`            TINYINT  NOT NULL DEFAULT 0 COMMENT '状态：0-进行中 1-已完成 2-已放弃',
    `current_day_index` TINYINT  NOT NULL DEFAULT 1 COMMENT '当前练到第几练',
    `create_time`       DATETIME          DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME          DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_plan` (`user_id`, `plan_id`),
    KEY `idx_user_status` (`user_id`, `status`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】我执行的计划（实例，一人一计划一行）';

-- ==================== 6. workout_session【日志】一次训练 ====================
-- ★★ uk_user_day_plan(user_id, session_date, plan_day_id)：同一天同一个训练日只能有一条。
--    防重复打卡**靠它**，不靠"先查有没有"——查完到插入之间有并发窗口。
-- ★ status=0 的 session 就是"练到一半退出"，允许继续练（GET /workouts/{id} 会把已完成的组带回来）。
-- ★ duration_sec 口径：max(实际耗时, Σ已完成组估算秒) —— 见 M11-2 方案 §4.2，秒级跑完也不会是 0。
CREATE TABLE IF NOT EXISTS `workout_session` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT        NOT NULL COMMENT '用户id',
    `user_plan_id` BIGINT        NOT NULL COMMENT 'user_plan.id',
    `plan_day_id`  BIGINT        NOT NULL COMMENT '训练日id',
    `session_date` DATE          NOT NULL COMMENT '训练日期',
    `start_time`   DATETIME      NOT NULL COMMENT '开始时间',
    `end_time`     DATETIME               DEFAULT NULL COMMENT '结束时间',
    `duration_sec` INT           NOT NULL DEFAULT 0 COMMENT '时长秒数（完成训练时写）',
    `kcal`         INT           NOT NULL DEFAULT 0 COMMENT '消耗千卡（0=未估，P1 接体重后再算，不编造）',
    `total_volume` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '总容量 = Σ(重量kg × 次数)',
    `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-进行中 1-已完成 2-已放弃',
    `create_time`  DATETIME               DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME               DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_day_plan` (`user_id`, `session_date`, `plan_day_id`),
    KEY `idx_user_date` (`user_id`, `session_date`),
    KEY `idx_user_start` (`user_id`, `start_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】一次训练（同一天同训练日唯一）';

-- ==================== 7. workout_set_log【日志】每一组的完成记录 ====================
-- ★ 每打一个勾就写一行（PUT /workouts/{id}/sets），中途刷新不丢进度。
-- ★ uk_session_item_set：同一 session 同一动作项同一组号只有一行 → 重复提交是"覆盖"，
--   天然幂等，不写 if。
CREATE TABLE IF NOT EXISTS `workout_set_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  BIGINT       NOT NULL COMMENT 'workout_session.id',
    `item_id`     BIGINT       NOT NULL COMMENT 'plan_day_item.id',
    `exercise_id` BIGINT       NOT NULL COMMENT '动作id（冗余一份，方便按动作统计，不 join item）',
    `set_no`      TINYINT      NOT NULL COMMENT '第几组，从 1 开始',
    `weight_kg`   DECIMAL(6,2)          DEFAULT NULL COMMENT '本组重量 kg（徒手为 NULL 或 0）',
    `reps`        SMALLINT     NOT NULL DEFAULT 0 COMMENT '本组完成次数（计时型为 0）',
    `duration_sec` INT         NOT NULL DEFAULT 0 COMMENT '本组时长（计时型）',
    `done`        TINYINT      NOT NULL DEFAULT 1 COMMENT '是否完成：0-否 1-是（P0 只写完成，保留字段给"放弃某组"）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_item_set` (`session_id`, `item_id`, `set_no`),
    KEY `idx_session` (`session_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】每一组的完成记录（事实，永不回改）';

-- ==================== 8. exercise_favorite【日志】我的动作/收藏 ====================
CREATE TABLE IF NOT EXISTS `exercise_favorite` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户id',
    `exercise_id` BIGINT   NOT NULL COMMENT '动作id',
    `create_time` DATETIME          DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_exercise` (`user_id`, `exercise_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】我的动作收藏（重复收藏被唯一索引挡住）';

-- ==================== 种子数据：20 个动作（数据集原样，id 固定 1~20） ====================
-- 为什么固定 id：下面 3 个官方计划直接引用 exercise_id；不固定就得靠 name 反查，
-- 一旦中文名改了就全错。固定 id 也让验收脚本可以直接断言。
INSERT INTO `exercise` (`id`, `name`, `en_name`, `category`, `body_part`, `equipment`, `difficulty`, `heat`, `lumbar_pressure`, `image_url`, `gif_url`, `primary_muscles_json`, `secondary_muscles_json`, `steps_json`, `tips`, `status`) VALUES
(1, '中握距卧推', 'Barbell Bench Press - Medium Grip', '力量', '胸', '杠铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Barbell_Bench_Press_-_Medium_Grip/0.jpg', NULL, '["chest"]', '["shoulders", "triceps"]', '["仰卧在平板凳上。采用中等握距（前臂与上臂在动作中段形成约90度夹角），将杠铃从架子上拿起并举过头顶，保持手臂伸直。这是起始姿势。", "从起始姿势开始，吸气并缓慢下放直到杠铃触碰到中部胸部。", "短暂停顿后，呼气将杠铃推回起始位置。专注于用胸部肌肉推动杠铃。在顶部收缩位置保持手臂伸直，挤压胸部肌肉并停顿一秒，然后缓慢下放重复动作。提示：理想情况下下放重量应耗时约为举起时的两倍。", "按规定的重复次数继续完成动作。", "完成后将杠铃放回架子上。"]', NULL, 1),
(2, '俯卧撑', 'Pushups', '力量', '胸', '徒手', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Pushups/0.jpg', NULL, '["chest"]', '["shoulders", "triceps"]', '["平躺于地面，面朝下，将双手放置于肩同宽处（约91厘米），同时保持身体平直于手臂伸直状态。", "接下来，吸气时慢慢降低身体，直到胸部几乎触地。", "呼气时，用胸部力量将上半身推回起始位置并挤压胸部。", "在顶部收缩姿势稍作停顿后，你可以开始再次降低身体，根据需要重复动作。"]', NULL, 1),
(3, '引体向上(Pullups)', 'Pullups', '力量', '背', '徒手', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Pullups/0.jpg', NULL, '["lats"]', '["biceps", "middle back"]', '["双手正握单杠，握距根据动作类型选择：宽握（手间距大于肩宽）、中握（手间距等于肩宽）、窄握（手间距小于肩宽）。注意：", "双臂伸直前平举握住单杠后，背部向后倾斜约30度，同时下背部形成自然弧度并挺胸，保持手臂伸直，即为起始姿势。", "通过下拉肩部和上臂带动身体向上，直到单杠触碰到上胸部（呼气）。技巧：完全收缩时专注挤压背部肌肉，上背部保持稳定，仅手臂运动，前臂仅负责握杠。", "在完全收缩姿势保持1秒后，开始吸气，缓慢将身体降至起始位置（手臂完全伸展，背阔肌充分拉伸）。", "按规定的重复次数重复此动作。"]', NULL, 1),
(4, '杠铃划船', 'Bent Over Barbell Row', '力量', '背', '杠铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Bent_Over_Barbell_Row/0.jpg', NULL, '["middle back"]', '["biceps", "lats", "shoulders"]', '["掌心向下抓握杠铃，略微屈膝并前倾躯干至腰部弯曲，保持背部挺直直到躯干几乎与地面平行。提示：确保头部抬起，杠铃应位于身体前方，手臂与地面垂直，躯干保持稳定。这是起始姿势。", "在躯干保持静止状态下呼气，通过前臂发力将杠铃向上拉起。全程保持肘部贴近身体，仅用前臂发力。在顶部收缩姿势时，收紧背部肌肉并短暂停顿。", "吸气，缓慢将杠铃下放至起始位置。", "重复推荐次数"]', NULL, 1),
(5, '杠铃深蹲', 'Barbell Squat', '力量', '腿', '杠铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Barbell_Squat/0.jpg', NULL, '["quadriceps"]', '["calves", "glutes", "hamstrings", "lower back"]', '["为安全起见，此动作建议在深蹲架上进行。首先将杠铃调整至略低于肩部的位置并加载重量，随后将双脚置于杠铃下方，让肩胛骨（稍低于颈部）横跨杠铃。", "双手握杠保持两侧平衡，先通过腿部发力推动杠铃离架，同时挺直躯干完成动作。", "离开深蹲架后，采用与肩同宽的中立站姿，脚尖略微外展。全程保持头部抬起、背部挺直（起始姿势）。注：本文讨论以标准站姿为主（侧重全身发展），但您也可参考脚部站姿章节选择其他三种站姿之一）。", "缓慢下蹲时屈膝屈髋，保持躯干直立和头部抬起。下蹲至大腿与小腿夹角略小于90度时屏气。技巧：动作规范时，膝盖前侧应与脚尖形成一条垂直于前方的假想直线。若膝盖超过此线（超过脚尖），说明动作存在膝关节压力过大的错误。", "呼气时以脚跟发力推动杠铃，同时伸膝挺髋回到起始姿势。", "重复推荐次数"]', NULL, 1),
(6, '腿推机', 'Leg Press', '力量', '腿', '固定器械', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Leg_Press/0.jpg', NULL, '["quadriceps"]', '["calves", "glutes", "hamstrings"]', '["使用腿推机，坐到机器上并将双腿放在正对您的前方平台，保持中等（肩宽）脚站距。（注：在此讨论中，我们将使用上述中等站距，该站距旨在全面开发；但您可以选择脚部定位部分描述的任何三种站距之一）", "降下安全杆以固定带重量的平台，将平台完全推起直到双腿在身前完全伸直。提示：确保不要锁死膝盖。躯干和双腿应形成完美的90度夹角，这是您的起始姿势。", "吸气时，缓慢下压平台直到上腿和下腿形成90度夹角。", "呼气时，主要用脚跟发力并借助股四头肌的力量将平台推回起始位置。", "重复推荐次数，完成后务必锁紧安全杆。您不希望这个满载的平台掉下来砸到您。"]', NULL, 1),
(7, '哑铃弓步', 'Dumbbell Lunges', '力量', '腿', '哑铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Dumbbell_Lunges/0.jpg', NULL, '["quadriceps"]', '["calves", "glutes", "hamstrings"]', '["双脚站直，躯干挺直，双手持哑铃置于体侧，此为起始姿势。", "右腿向前迈约两英尺，左腿保持静止，同时下压上半身，保持躯干直立并维持平衡。吸气下放。注意：如其他动作般，下放时膝盖不可超过脚尖，以免对膝关节造成不当压力。确保前小腿垂直地面。", "主要用脚跟发力，呼气时起身并回到起始姿势。", "重复动作至推荐次数，然后换左腿进行。"]', NULL, 1),
(8, '站姿军事推举', 'Standing Military Press', '力量', '肩', '杠铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Standing_Military_Press/0.jpg', NULL, '["shoulders"]', '["triceps"]', '["首先将杠铃放置于深蹲架上的高度约与胸部齐平。选择好重量后，使用掌心向前的抓握姿势握住杠铃，确保双手间距比肩宽略大。", "稍屈膝将杠铃置于锁骨上，保持杠铃贴紧胸部向上举起。退后一步，将双脚调整至肩同宽的间距。", "正确握距后，将杠铃举过头顶并锁定手臂。保持杠铃在肩部高度前倾约15度的起始姿势。", "缓慢下放杠铃至锁骨位置同时吸气。", "呼气时将杠铃重新举至起始位置。", "重复推荐次数的组数。"]', NULL, 1),
(9, '侧平举', 'Side Lateral Raise', '力量', '肩', '哑铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Side_Lateral_Raise/0.jpg', NULL, '["shoulders"]', '[]', '["选择一对哑铃，保持躯干挺直，双手自然下垂于体侧，掌心向前。这是起始姿势。", "保持躯干稳定不动（不要摆动身体），肘部轻微弯曲，手部略微前倾（仿佛在倒水）。缓慢将哑铃侧平举至手臂与地面平行，呼气完成此动作并短暂停顿。", "缓慢下放哑铃至起始位置，同时吸气。", "重复推荐次数的组数"]', NULL, 1),
(10, '哑铃弯举', 'Dumbbell Bicep Curl', '力量', '二头', '哑铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Dumbbell_Bicep_Curl/0.jpg', NULL, '["biceps"]', '["forearms"]', '["挺直站立，双手各持一哑铃于手臂自然伸直的位置。保持肘部紧贴身体，旋转手掌使其正面朝前，即为起始姿势。", "现在，保持上臂固定不动，呼气的同时弯举哑铃并收缩肱二头肌。继续将重量提升至肱二头肌完全收缩，哑铃达到肩部高度。短暂保持收缩状态，并用力挤压肱二头肌。", "然后，吸气并缓慢将哑铃下放回起始位置。", "重复推荐次数。"]', NULL, 1),
(11, '三头肌下压 - 绳索附件', 'Triceps Pushdown - Rope Attachment', '力量', '三头', '固定器械', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Triceps_Pushdown_-_Rope_Attachment/0.jpg', NULL, '["triceps"]', '[]', '["将绳索附件固定在高 pulley 上，采用中立抓握（双手掌心相对）。", "保持躯干挺直，轻微前倾，将上臂贴近身体并使其与地面垂直。前臂应向上指向 pulley 并保持中立抓握姿势，此时为起始位置。", "通过三头肌发力，将绳索下压至大腿两侧，同时保持上臂紧贴躯干不动，仅前臂运动。动作终点时手臂完全伸直并与地面垂直，全程呼气。", "在收缩位保持1秒后，缓慢将绳索上拉至起始位置并吸气。", "重复至推荐次数"]', NULL, 1),
(12, '站姿小腿举', 'Standing Calf Raises', '力量', '小腿', '固定器械', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Standing_Calf_Raises/0.jpg', NULL, '["calves"]', '[]', '["调整小腿举机有垫杠杆至适合你的身高。", "将肩胛骨置于支撑垫下方，脚尖正对前方（或采用章节开头所述的其他两种姿势）。确保脚掌完全踩在小腿支撑台上，脚跟悬空于台面之外。通过伸展髋部和膝盖将杠杆推起至身体直立，全程保持膝盖微屈（切勿锁死）。脚尖方向需与章节开头所述一致（正对/外展/内收）。此为起始姿势。", "呼气时，尽可能伸展脚踝并绷紧小腿肌肉，将脚跟向上抬。全程保持膝盖固定不动，动作过程中严禁任何角度弯曲。保持收缩状态1秒后开始还原。", "吸气时，缓慢降低脚跟并弯曲脚踝，直到小腿充分拉伸。返回起始姿势时需控制速度。", "重复至推荐次数。"]', NULL, 1),
(13, '坐姿正握杠铃腕屈伸', 'Seated Palm-Up Barbell Wrist Curl', '力量', '前臂', '杠铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Seated_Palm-Up_Barbell_Wrist_Curl/0.jpg', NULL, '["forearms"]', '[]', '["双手正握杠铃，掌心朝上，手间距与肩同宽；", "双脚平放在地面，间距略宽于肩部；", "身体前倾，将前臂放在大腿上方，掌心朝上。提示：确保手腕前侧贴于膝盖上方，这是起始姿势；", "吸气时尽可能将杠铃下放，保持握力紧绷；", "呼气时尽可能向上屈腕，同时保持前臂发力。顶部收缩保持1秒。提示：仅手腕应移动。"]', NULL, 1),
(14, '杠铃髋屈伸', 'Barbell Hip Thrust', '力量', '臀部', '杠铃', 3, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Barbell_Hip_Thrust/0.jpg', NULL, '["glutes"]', '["calves", "hamstrings"]', '["坐于地面，将长凳置于身后。双腿上方放置已加载的杠铃。使用粗握把或杠铃垫可大幅减轻本动作的不适感。", "将杠铃滚至髋关节正上方，向后靠于长凳使肩胛骨贴近凳顶。", "动作起始时通过脚部发力，垂直向上伸展髋关节穿过杠铃。体重应支撑于肩胛骨和脚部。尽可能充分伸展后，反向动作回到起始位置。"]', NULL, 1),
(15, '卷腹', 'Crunches', '力量', '腹部', '徒手', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Crunches/0.jpg', NULL, '["abdominals"]', '[]', '["平躺于地面，双脚平放地面或膝盖弯曲呈90度角支撑于长凳上。若双脚置于长凳，需保持三到四英寸间距，脚趾向内并使其相触。", "双手轻放于两侧头部，保持肘部贴紧身体（提示：不要将手指抵在头后）。", "通过将下背部用力压向地面来更好地孤立腹部肌肉，同时开始将双肩抬离地面。", "尽可能用力将下背部压向地面，同时强烈收缩腹部肌肉并呼气。双肩抬离地面的高度应不超过四英寸，下背部始终接触地面。动作顶端需强烈收缩腹部保持一秒（提示：专注于缓慢可控的动作，不要通过借助惯性偷懒）。", "在一秒收缩后，缓慢吸气同时将身体恢复起始姿势。", "重复推荐次数"]', NULL, 1),
(16, '平板支撑', 'Plank', '力量', '腹部', '徒手', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Plank/0.jpg', NULL, '["abdominals"]', '[]', '["俯卧于地面，用脚趾和前臂支撑身体重量。手臂弯曲，且前臂直接位于肩部下方。", "始终保持身体直线，尽可能长时间保持该姿势。若想增加难度，可抬起一只手臂或一条腿。"]', NULL, 1),
(17, '杠铃耸肩', 'Barbell Shrug', '力量', '斜方肌', '杠铃', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Barbell_Shrug/0.jpg', NULL, '["traps"]', '[]', '["保持背部挺直，双脚与肩同宽，双手正握杠铃（掌心朝向大腿）。提示：双手间距应略宽于肩宽。建议佩戴腕带增强握力，此时为起始姿势。", "呼气时尽可能耸起双肩，保持收缩1秒。提示：避免通过肱二头肌发力抬起杠铃。", "吸气时缓慢回到起始姿势。", "重复至推荐次数。"]', NULL, 1),
(18, '腘绳肌拉伸', 'Hamstring Stretch', '拉伸', '腿', '徒手', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Hamstring_Stretch/0.jpg', NULL, '["hamstrings"]', '[]', '["仰卧，将一条腿向上伸直并保持髋部呈90度，另一条腿平放在地面上。", "将绑带、弹性带或绳索绕过脚掌根部，调整至起始姿势。", "拉紧绑带对小腿和腘绳肌产生张力，保持拉伸10-30秒后，换另一条腿重复动作。"]', NULL, 1),
(19, '跳绳', 'Rope Jumping', '有氧', '腿', '其他', 3, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Rope_Jumping/0.jpg', NULL, '["quadriceps"]', '["calves", "hamstrings"]', '["双手各握住绳的一端，将绳子放在身后的地面。抬手将绳子从头顶翻转至身前下方，当绳子落地时跳过它。找到能持续稳定的摇动节奏。使用不同的速度和技巧可增加变化。", "跳绳是一项令人兴奋的运动，能挑战你的协调性并消耗大量能量。150磅的人跳绳30分钟大约消耗350大卡，而跑步则能消耗超过450大卡。"]', NULL, 1),
(20, '深蹲 - 带弹力带', 'Squats - With Bands', '力量', '腿', '弹力带', 1, NULL, NULL, 'https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/Squats_-_With_Bands/0.jpg', NULL, '["quadriceps"]', '["calves", "glutes", "hamstrings", "lower back"]', '["开始时，请确保弹力带均匀分布在身体两侧。用双手抓住弹力带两侧，将双脚置于弹力带中间。双脚应与肩同宽分开。", "抓握弹力带时，两侧应保持相同高度。采用掌心向前的抓握姿势（prone grip），让弹力带把手位于面部两侧，此时处于起始姿势。", "缓慢屈膝下蹲，双腿下沉至大腿与地面平行时呼气。", "用脚跟推起身体回到起始姿势时呼气。", "重复推荐次数的组数。"]', NULL, 1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ==================== 种子数据：3 个官方计划 ====================
-- 只引用上面 20 个动作；est_minutes / train_days_per_week 与后端同一公式算出来，
-- 前端不许重算一份（两个数在演示时对不上，是这个模块最常见的假 bug）。

INSERT INTO `plan` (`id`, `title`, `summary`, `cover_url`, `goal`, `difficulty`, `train_days_per_week`, `kcal_estimate`, `equipment_json`, `source`, `status`, `ref_count`, `owner_id`) VALUES
(1, '新手全身唤醒 · 一周3练', '零基础第一套：每周一三五各一次全身训练，复合动作打底，每个动作都有明确组数次数，练完不会废。', NULL, '增肌', 0, 3, 0, '["杠铃", "哑铃", "固定器械"]', 0, 1, 0, 0),
(2, '胸背腿分化 · 一周5练', '进阶分化：胸 / 背 / 腿 / 肩臂 / 核心有氧各一天，单部位容量拉满，适合已经练满三个月的人。', NULL, '增肌', 1, 5, 0, '["杠铃", "哑铃", "固定器械", "弹力带"]', 0, 1, 0, 0),
(3, '居家徒手燃脂 · 一周4练', '不用去健身房：徒手 + 弹力带 + 跳绳，每周四次，单次 20 分钟出头，减脂期照着练就行。', NULL, '减脂', 0, 4, 0, '["徒手", "弹力带", "其他"]', 0, 1, 0, 0)
ON DUPLICATE KEY UPDATE `id` = `id`;
INSERT INTO `plan_day` (`id`, `plan_id`, `day_index`, `weekday`, `name`, `sort`, `est_minutes`) VALUES
(1, 1, 1, 1, '周一 · 全身 A', 1, 25),
(2, 1, 2, 3, '周三 · 全身 B', 2, 18),
(3, 1, 3, 5, '周五 · 全身 C', 3, 13),
(4, 2, 1, 1, '周一 · 胸', 1, 19),
(5, 2, 2, 2, '周二 · 背', 2, 18),
(6, 2, 3, 3, '周三 · 腿', 3, 19),
(7, 2, 4, 4, '周四 · 肩臂', 4, 18),
(8, 2, 5, 5, '周五 · 核心有氧', 5, 18),
(9, 3, 1, 1, '周一 · 徒手 A', 1, 23),
(10, 3, 2, 2, '周二 · 徒手 B', 2, 20),
(11, 3, 3, 4, '周四 · 弹力带', 3, 13),
(12, 3, 4, 6, '周六 · 有氧拉伸', 4, 18)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 动作项：组数 / 次数 / 组休按动作类型给默认值（复合 4×8 休90、孤立 3×12 休60、
-- 计时 3×60s 休60、跳绳 3×120s、拉伸 2×30s 且 group_type=2）。
-- weight_kg 全为 NULL —— 建议重量必须由用户按自己水平填，种子不猜。

INSERT INTO `plan_day_item` (`id`, `plan_day_id`, `exercise_id`, `sort`, `group_type`, `sets`, `reps`, `duration_sec`, `weight_kg`, `rest_sec`) VALUES
(1, 1, 5, 1, 0, 4, 8, 0, NULL, 90),
(2, 1, 1, 2, 0, 4, 8, 0, NULL, 90),
(3, 1, 4, 3, 0, 4, 8, 0, NULL, 90),
(4, 1, 16, 4, 0, 3, 0, 60, NULL, 60),
(5, 1, 18, 5, 2, 2, 0, 30, NULL, 0),
(6, 2, 6, 1, 0, 3, 12, 0, NULL, 60),
(7, 2, 8, 2, 0, 4, 8, 0, NULL, 90),
(8, 2, 3, 3, 0, 3, 12, 0, NULL, 60),
(9, 2, 15, 4, 0, 3, 12, 0, NULL, 60),
(10, 3, 7, 1, 0, 3, 12, 0, NULL, 60),
(11, 3, 9, 2, 0, 3, 12, 0, NULL, 60),
(12, 3, 10, 3, 0, 3, 12, 0, NULL, 60),
(13, 3, 18, 4, 2, 2, 0, 30, NULL, 0),
(14, 4, 1, 1, 0, 4, 8, 0, NULL, 90),
(15, 4, 2, 2, 0, 3, 12, 0, NULL, 60),
(16, 4, 11, 3, 0, 3, 12, 0, NULL, 60),
(17, 4, 16, 4, 0, 3, 0, 60, NULL, 60),
(18, 5, 3, 1, 0, 3, 12, 0, NULL, 60),
(19, 5, 4, 2, 0, 4, 8, 0, NULL, 90),
(20, 5, 17, 3, 0, 3, 12, 0, NULL, 60),
(21, 5, 13, 4, 0, 3, 12, 0, NULL, 60),
(22, 6, 5, 1, 0, 4, 8, 0, NULL, 90),
(23, 6, 6, 2, 0, 3, 12, 0, NULL, 60),
(24, 6, 7, 3, 0, 3, 12, 0, NULL, 60),
(25, 6, 12, 4, 0, 3, 12, 0, NULL, 60),
(26, 6, 18, 5, 2, 2, 0, 30, NULL, 0),
(27, 7, 8, 1, 0, 4, 8, 0, NULL, 90),
(28, 7, 9, 2, 0, 3, 12, 0, NULL, 60),
(29, 7, 10, 3, 0, 3, 12, 0, NULL, 60),
(30, 7, 11, 4, 0, 3, 12, 0, NULL, 60),
(31, 8, 15, 1, 0, 3, 12, 0, NULL, 60),
(32, 8, 16, 2, 0, 3, 0, 60, NULL, 60),
(33, 8, 19, 3, 0, 3, 0, 120, NULL, 60),
(34, 8, 18, 4, 2, 2, 0, 30, NULL, 0),
(35, 9, 2, 1, 0, 3, 12, 0, NULL, 60),
(36, 9, 5, 2, 0, 4, 8, 0, NULL, 90),
(37, 9, 16, 3, 0, 3, 0, 60, NULL, 60),
(38, 9, 19, 4, 0, 3, 0, 120, NULL, 60),
(39, 10, 3, 1, 0, 3, 12, 0, NULL, 60),
(40, 10, 7, 2, 0, 3, 12, 0, NULL, 60),
(41, 10, 15, 3, 0, 3, 12, 0, NULL, 60),
(42, 10, 19, 4, 0, 3, 0, 120, NULL, 60),
(43, 11, 20, 1, 0, 3, 12, 0, NULL, 60),
(44, 11, 2, 2, 0, 3, 12, 0, NULL, 60),
(45, 11, 15, 3, 0, 3, 12, 0, NULL, 60),
(46, 11, 18, 4, 2, 2, 0, 30, NULL, 0),
(47, 12, 19, 1, 0, 3, 0, 120, NULL, 60),
(48, 12, 15, 2, 0, 3, 12, 0, NULL, 60),
(49, 12, 16, 3, 0, 3, 0, 60, NULL, 60),
(50, 12, 18, 4, 2, 2, 0, 30, NULL, 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ==================== P0 建完自检（跑在这里时 P1 还没建，所以表数量应 = 8） ====================
SELECT COUNT(*) AS `表数量` FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'fm_plan';
SELECT COUNT(*) AS `动作数` FROM `exercise`;
SELECT COUNT(*) AS `官方计划数` FROM `plan` WHERE `source` = 0;
SELECT COUNT(*) AS `训练日数` FROM `plan_day`;
SELECT COUNT(*) AS `动作项数` FROM `plan_day_item`;


-- ============================================================
-- ===== P1：饮食（M11-3）=====
-- ------------------------------------------------------------
-- 10 张表：模板 4（diet_plan / diet_plan_day / diet_plan_meal / diet_plan_meal_item）
--           内容 3（food / food_favorite / sport）
--           日志 2（diet_log / sport_log）
--           体征 1（user_body_metric）
--
-- ★★ 三条口径（写代码前就定了，改口径要改文案，别只改代码）：
--   1. 运动消耗**不抵扣**预算；预算固定，运动只作"净摄入"展示
--   2. 预算 = 4×蛋白 + 4×碳水 + 9×脂肪（三者存库，kcal 由这一处算出来再落库）
--   3. "还可以吃" = 预算 − 摄入，**读时算、不落库**（落库就有三处要同步）
--
-- ★★ meal_type 与 meal_index 是**双口径**：
--   记录侧（diet_log）只需要 meal_type 1早/2午/3晚/4加餐 —— 用户点的是 4 个入口；
--   计划侧（diet_plan_meal）需要 meal_index —— 参考 App 详情页是"每日六餐，三次加餐"，
--   只用 (day, meal_type) 的话三次加餐只能插进去一条，复现不了原设计。
--
-- ★ 种子食物 30 条来自《中国食物成分表》（Sanotsu/free-fitness，15 个文件 / 1677 条），
--   name / kcal / 三大营养素**逐字取自数据集**，没有一条是手写的。
--   数据集里没有的（糖 / 蜂蜜 / 盐 / 酱油 / 醋 / 料酒 / 面包 / 包子 / 饺子 / 汽水）一条都没补。
--   分类是**由文件名映射**过来的（数据本身没有分类字段），映射规则见 scripts/import-food-db.py。
--   中式菜肴 / 西式菜肴 两类在数据集里没有对应文件 → P1 是空的，不硬凑。
-- ============================================================


-- ==================== 9. diet_plan【模板】饮食计划 ====================
-- 与 plan（训练计划）分开而不是共用一张：字段重合度不到 30%（这边是 cycle_days / kcal_per_day，
-- 那边是 train_days_per_week / difficulty / equipment），硬合并会造出一堆 NULL。
CREATE TABLE IF NOT EXISTS `diet_plan` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`         VARCHAR(64)  NOT NULL DEFAULT '未命名饮食计划' COMMENT '计划名（默认值由后端给）',
    `summary`       VARCHAR(512)          DEFAULT NULL COMMENT '一句话描述（详情页副标题）',
    `cover_url`     VARCHAR(512)          DEFAULT NULL COMMENT '封面图',
    `goal`          VARCHAR(16)  NOT NULL DEFAULT '增肌' COMMENT '目标：增肌 / 减脂',
    `cycle_days`    TINYINT      NOT NULL DEFAULT 1 COMMENT '一周期几天',
    `kcal_per_day`  INT          NOT NULL DEFAULT 0 COMMENT '每日热量（= 各天 kcal_total 的平均，同事务算出，不手填）',
    `source`        TINYINT      NOT NULL DEFAULT 1 COMMENT '来源：0-官方 1-自建 2-AI',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-草稿 1-已发布 2-已下架',
    `ref_count`     INT          NOT NULL DEFAULT 0 COMMENT '被执行/查看次数（热度，只增）',
    `owner_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '归属用户，0=官方',
    `create_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dp_source_status` (`source`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【模板】饮食计划（改这里不影响历史饮食记录）';

-- ==================== 10. diet_plan_day【模板】第 N 天 ====================
CREATE TABLE IF NOT EXISTS `diet_plan_day` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `diet_plan_id` BIGINT      NOT NULL COMMENT '饮食计划id',
    `day_index`    TINYINT     NOT NULL COMMENT '第几天，从 1 开始',
    `kcal_total`   INT         NOT NULL DEFAULT 0 COMMENT '当天总热量（= 各餐 kcal 之和，冗余）',
    `protein_g`    DECIMAL(7,1) NOT NULL DEFAULT 0 COMMENT '当天蛋白（克，冗余）',
    `carb_g`       DECIMAL(7,1) NOT NULL DEFAULT 0 COMMENT '当天碳水（克，冗余）',
    `fat_g`        DECIMAL(7,1) NOT NULL DEFAULT 0 COMMENT '当天脂肪（克，冗余）',
    `create_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_diet_day` (`diet_plan_id`, `day_index`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【模板】饮食计划的第 N 天';

-- ==================== 11. diet_plan_meal【模板】一天里的一餐 ====================
-- ★★ 唯一索引必须是 (day, meal_type, meal_index) 三列：
--    只用 (day, meal_type) 的话，"早中晚 + 三次加餐"里那三次加餐只能插进去一条。
CREATE TABLE IF NOT EXISTS `diet_plan_meal` (
    `id`               BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `diet_plan_day_id` BIGINT   NOT NULL COMMENT '第几天id',
    `meal_type`        TINYINT  NOT NULL COMMENT '餐次：1-早餐 2-午餐 3-晚餐 4-加餐',
    `meal_index`       TINYINT  NOT NULL DEFAULT 1 COMMENT '同餐次里的第几顿（加餐可以是 1/2/3）',
    `meal_time`        TIME              DEFAULT NULL COMMENT '建议时间，如 07:30:00',
    `kcal`             INT      NOT NULL DEFAULT 0 COMMENT '这一餐热量（= 各食物项之和，冗余）',
    `create_time`      DATETIME          DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME          DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_diet_meal` (`diet_plan_day_id`, `meal_type`, `meal_index`),
    KEY `idx_meal_day` (`diet_plan_day_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【模板】饮食计划里的一餐（三餐 + 多次加餐）';

-- ==================== 12. diet_plan_meal_item【模板】一餐里的一份食物 ====================
-- ★ kcal 是"热量真相"（口径 6）：meal.kcal 与 day.kcal_total 都是它汇总出来的冗余。
--   ★★ 但模板"跟着食物库更新"意味着运营改 food.kcal_per_100g 后这一列会过期，
--      所以**详情页展示时按 food 现价 × amount 实时算**，本列只作导入时的对账快照。
--   为什么还留着它：出错时要能一眼看出"是食物库改了，还是导入算法错了"。
CREATE TABLE IF NOT EXISTS `diet_plan_meal_item` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meal_id`     BIGINT       NOT NULL COMMENT '这一餐id',
    `food_id`     BIGINT       NOT NULL COMMENT '食物id',
    `sort`        INT          NOT NULL DEFAULT 1 COMMENT '横滑卡片的顺序，从 1 开始',
    `amount`      DECIMAL(7,1) NOT NULL DEFAULT 100 COMMENT '份量数值（配合 unit）',
    `unit`        VARCHAR(8)   NOT NULL DEFAULT '克' COMMENT '单位：克 / 碗 / 盒 / 个…',
    `kcal`        INT          NOT NULL DEFAULT 0 COMMENT '按导入时 food 单价算出的热量（快照，不用于展示）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_meal_item_sort` (`meal_id`, `sort`),
    KEY `idx_mi_food` (`food_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【模板】一餐里的一份食物（热量真相在这一层）';

-- ==================== 13. food【内容】食物库 ====================
-- ★ 参与筛选的分类必须列化 + 建索引（category），不塞 JSON。
-- ★ category 只有 8 个真分类；"常见/收藏/自定义/搜索"是**入口**不是分类：
--   常见=按 sort 取前 N；收藏查 food_favorite；自定义查 owner_id>0；搜索走 keyword。
-- ★ units_json 只放"默认单位 + 最多两个常用单位"，**不做任意单位换算引擎**：
--   [{"unit":"克","gram":1},{"unit":"碗","gram":150}]
--   克数是按常见份量**估算**的（1 碗米饭 150g），不是权威值。
-- ★★ uk_food_name 是导入脚本"按 name 幂等 upsert"的前提：没有它，每跑一次导入就多一份全量数据。
CREATE TABLE IF NOT EXISTS `food` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`             VARCHAR(64)  NOT NULL COMMENT '食物名（数据集 foodName，逐字保留）',
    `alias`            VARCHAR(128)          DEFAULT NULL COMMENT '别名（从"黄瓜（鲜）［胡瓜］"的方括号里解析，没有则 null）',
    `category`         VARCHAR(16)  NOT NULL COMMENT '8 分类：主食/蔬果/肉蛋奶/豆类坚果/零食饮料/中式菜肴/西式菜肴/配料',
    `kcal_per_100g`    INT          NOT NULL DEFAULT 0 COMMENT '每 100g 热量（千卡）',
    `protein_per_100g` DECIMAL(6,1)          DEFAULT NULL COMMENT '每 100g 蛋白（克）；NULL=数据集缺',
    `carb_per_100g`    DECIMAL(6,1)          DEFAULT NULL COMMENT '每 100g 碳水（克）；NULL=数据集缺',
    `fat_per_100g`     DECIMAL(6,1)          DEFAULT NULL COMMENT '每 100g 脂肪（克）；NULL=数据集缺',
    `units_json`       VARCHAR(255)          DEFAULT NULL COMMENT '可选单位 JSON，如 [{"unit":"克","gram":1},{"unit":"碗","gram":150}]',
    `default_unit`     VARCHAR(8)   NOT NULL DEFAULT '克' COMMENT '默认单位',
    `emoji`            VARCHAR(8)            DEFAULT NULL COMMENT '展示用 emoji（不是营养数据）',
    `image_url`        VARCHAR(512)          DEFAULT NULL COMMENT '图片（P1 不用，留给以后）',
    `sort`             INT          NOT NULL DEFAULT 0 COMMENT '推荐位排序（"常见"取前 N 用它）',
    `owner_id`         BIGINT       NOT NULL DEFAULT 0 COMMENT '0=公共；>0=用户自定义',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '0-下架 1-上架',
    `create_time`      DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_food_name` (`name`),
    KEY `idx_food_category` (`category`),
    KEY `idx_food_owner` (`owner_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【内容】食物库（1677 条开源数据，P1 种子 30 条）';

-- ==================== 14. food_favorite【日志】我的收藏 ====================
CREATE TABLE IF NOT EXISTS `food_favorite` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户id',
    `food_id`     BIGINT   NOT NULL COMMENT '食物id',
    `create_time` DATETIME          DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_food_fav` (`user_id`, `food_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】食物收藏（重复收藏被唯一索引挡住）';

-- ==================== 15. diet_log【日志】每日饮食记录 ====================
-- ★★ 六列速查：amount / gram / kcal / protein_g / carb_g / fat_g
--   合并（同一天同一餐同一食物同一单位再记一次）时**六列必须一起按新增量累加**。
--   只加 amount 的后果：用户看到 50g 变 100g，热量还是 46 千卡 —— 而且页面上很难看出来。
-- ★★ 必须存快照（food_name / kcal / 三大营养素）：食物库的数值会被运营修正，
--   历史记录不能跟着变。只存 food_id 的话，去年 12 月的记录会变成今年的价。
-- ★ 唯一索引把 unit 也算进去：50g 与 1 碗是两条（单位不同折算不同，强行合并会把数算错）。
CREATE TABLE IF NOT EXISTS `diet_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '用户id',
    `log_date`    DATE         NOT NULL COMMENT '记录日期',
    `meal_type`   TINYINT      NOT NULL COMMENT '餐次：1-早餐 2-午餐 3-晚餐 4-加餐',
    `food_id`     BIGINT       NOT NULL COMMENT '食物id（快照外的溯源字段）',
    `food_name`   VARCHAR(64)  NOT NULL COMMENT '食物名快照',
    `amount`      DECIMAL(7,1) NOT NULL COMMENT '份量（配合 unit）',
    `unit`        VARCHAR(8)   NOT NULL COMMENT '单位',
    `gram`        DECIMAL(7,1) NOT NULL DEFAULT 0 COMMENT '折算成克（用于合计与展示）',
    `kcal`        INT          NOT NULL DEFAULT 0 COMMENT '这份食物的热量（快照）',
    `protein_g`   DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '蛋白（克，快照）',
    `carb_g`      DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '碳水（克，快照）',
    `fat_g`       DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '脂肪（克，快照）',
    `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_diet_log` (`user_id`, `log_date`, `meal_type`, `food_id`, `unit`),
    KEY `idx_diet_user_date` (`user_id`, `log_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】每日饮食记录（必须快照，合并靠唯一索引）';

-- ==================== 16. sport【内容】运动项目库 ====================
-- ★ 与 exercise 分表：字段重合度 < 30%（这个只要"每 30 分钟烧多少卡"，那个要肌群/步骤/示意图）。
-- ★ category 11 个真分类，不含"常见/搜索/自定义/动作库"（那是入口）。
-- ★★ kcal_per_30min 是**按 65kg 成年人中等强度估算**的，不是通用值：
--    体重不同消耗差别很大（90kg 的人跑步约为 65kg 的 1.4 倍）。P2 接体重后改成按 MET 算。
CREATE TABLE IF NOT EXISTS `sport` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`            VARCHAR(64) NOT NULL COMMENT '运动名',
    `category`        VARCHAR(16) NOT NULL COMMENT '分类：日常/走路/跑步/骑行/有氧操/舞蹈瑜伽/游泳/球类运动/跳绳/爬楼梯/其他',
    `kcal_per_30min`  INT         NOT NULL DEFAULT 0 COMMENT '每 30 分钟消耗（按 65kg 估算）',
    `met`             DECIMAL(4,1)         DEFAULT NULL COMMENT 'MET 值；NULL=未录入（不编造）',
    `sort`            INT         NOT NULL DEFAULT 0 COMMENT '常见项排序',
    `status`          TINYINT     NOT NULL DEFAULT 1 COMMENT '0-下架 1-上架',
    `create_time`     DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sport_name` (`name`),
    KEY `idx_sport_category` (`category`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【内容】运动项目库（50 条，热量按 65kg 估算）';

-- ==================== 17. sport_log【日志】运动消耗记录 ====================
-- ★ 一次运动一行，**不合并**：跑两次 30 分钟是两条记录，各自有时长。
--   合并会丢掉"什么时候跑的"，而消耗本来就不抵扣预算，合并没有任何收益。
CREATE TABLE IF NOT EXISTS `sport_log` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT      NOT NULL COMMENT '用户id',
    `log_date`     DATE        NOT NULL COMMENT '运动日期',
    `sport_id`     BIGINT      NOT NULL COMMENT '运动项目id',
    `sport_name`   VARCHAR(64) NOT NULL COMMENT '运动名快照',
    `duration_min` INT         NOT NULL COMMENT '时长（分钟）',
    `kcal`         INT         NOT NULL DEFAULT 0 COMMENT '本次消耗（快照 = kcal_per_30min × 分钟 / 30）',
    `create_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_sport_user_date` (`user_id`, `log_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】运动消耗记录（不合并，必须快照）';

-- ==================== 18. user_body_metric【日志】体征时序 ====================
-- ★ 改体重 = 插新行，**不改旧行**（体重是时序，历史要留）。
-- ★ 预算与三大营养素目标在这里落库，但**计算只在一处**（NutritionCalculator），
--   任何"重算"都必须走同一个方法，否则会出现"同一个体重算出两个预算"。
-- ★ kcal_budget 必须与 macro 自洽：protein×4 + carb×4 + fat×9 = kcal_budget。
CREATE TABLE IF NOT EXISTS `user_body_metric` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          BIGINT       NOT NULL COMMENT '用户id',
    `record_date`      DATE         NOT NULL COMMENT '记录日期（一天一行）',
    `height_cm`        DECIMAL(5,1)          DEFAULT NULL COMMENT '身高（cm）',
    `weight_kg`        DECIMAL(5,1) NOT NULL COMMENT '体重（kg，库里一律 kg；界面显示斤时由前端 ×2）',
    `target_weight_kg` DECIMAL(5,1)          DEFAULT NULL COMMENT '目标体重（kg）',
    `target_date`      DATE                  DEFAULT NULL COMMENT '目标日期',
    `bmi`              DECIMAL(4,1)          DEFAULT NULL COMMENT 'BMI = kg / m²',
    `kcal_budget`      INT          NOT NULL DEFAULT 0 COMMENT '每日热量预算（= 4P+4C+9F）',
    `protein_g`        DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '每日蛋白目标（克）',
    `carb_g`           DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '每日碳水目标（克）',
    `fat_g`            DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '每日脂肪目标（克）',
    `create_time`      DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_body_user_date` (`user_id`, `record_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '【日志】体征时序（当前体重 = 最新一行）';

-- ==================== 种子：30 个食物（数据集逐字取值，id 固定 1~30）====================
-- alias 从名字里的［］解析；kcal/三大营养素来自能源与成分列，缺的存 NULL（不是 0）。
INSERT INTO `food` (`id`, `name`, `alias`, `category`, `kcal_per_100g`, `protein_per_100g`, `carb_per_100g`, `fat_per_100g`, `units_json`, `default_unit`, `emoji`, `sort`, `owner_id`, `status`) VALUES
(1, '稻米（代表值）', NULL, '主食', 346, 7.9, 77.2, 0.9, '[{"unit": "克", "gram": 1}, {"unit": "碗", "gram": 150}]', '克', '🍚', 1, 0, 1),
(2, '小麦粉（代表值）', NULL, '主食', 359, 12.4, 74.1, 1.7, '[{"unit": "克", "gram": 1}, {"unit": "碗", "gram": 120}]', '克', '🌾', 2, 0, 1),
(3, '玉米（鲜）', NULL, '主食', 112, 4.0, 22.8, 1.2, '[{"unit": "克", "gram": 1}, {"unit": "根", "gram": 200}]', '克', '🌽', 3, 0, 1),
(4, '马铃薯［土豆、洋芋］', '土豆、洋芋', '主食', 81, 2.6, 17.8, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "个", "gram": 150}]', '克', '🥔', 4, 0, 1),
(5, '甘薯（红心）［山芋、红薯］', '山芋、红薯', '主食', 61, 0.7, 15.3, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "个", "gram": 200}]', '克', '🍠', 5, 0, 1),
(6, '龙须面（鸡蛋）', NULL, '主食', 352, 14.0, 71.5, 1.8, '[{"unit": "克", "gram": 1}, {"unit": "碗", "gram": 100}]', '克', '🍜', 6, 0, 1),
(7, '黄瓜（鲜）［胡瓜］', '胡瓜', '蔬果', 16, 0.8, 2.9, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "根", "gram": 200}]', '克', '🥒', 7, 0, 1),
(8, '番茄［西红柿］', '西红柿', '蔬果', 15, 0.9, 3.3, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "个", "gram": 150}]', '克', '🍅', 8, 0, 1),
(9, '大白菜（代表值）', NULL, '蔬果', 20, 1.6, 3.4, 0.2, '[{"unit": "克", "gram": 1}]', '克', '🥬', 9, 0, 1),
(10, '菠菜（鲜）［赤根菜］', '赤根菜', '蔬果', 28, 2.6, 4.5, 0.3, '[{"unit": "克", "gram": 1}]', '克', '🥬', 10, 0, 1),
(11, '香菇（鲜）［香蕈，冬菇］', '香蕈，冬菇', '蔬果', 26, 2.2, 5.2, 0.3, '[{"unit": "克", "gram": 1}, {"unit": "朵", "gram": 15}]', '克', '🍄', 11, 0, 1),
(12, '苹果（代表值）', NULL, '蔬果', 53, 0.4, 13.7, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "个", "gram": 200}]', '克', '🍎', 12, 0, 1),
(13, '香蕉（红皮）', NULL, '蔬果', 86, 1.1, 20.8, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "根", "gram": 120}]', '克', '🍌', 13, 0, 1),
(14, '橙', NULL, '蔬果', 48, 0.8, 11.1, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "个", "gram": 180}]', '克', '🍊', 14, 0, 1),
(15, '西瓜（代表值）', NULL, '蔬果', 31, 0.5, 6.8, 0.3, '[{"unit": "克", "gram": 1}, {"unit": "块", "gram": 200}]', '克', '🍉', 15, 0, 1),
(16, '猪肉（瘦）', NULL, '肉蛋奶', 143, 20.3, 1.5, 6.2, '[{"unit": "克", "gram": 1}]', '克', '🥩', 16, 0, 1),
(17, '鸡胸脯肉', NULL, '肉蛋奶', 118, 24.6, 0.6, 1.9, '[{"unit": "克", "gram": 1}, {"unit": "块", "gram": 120}]', '克', '🍗', 17, 0, 1),
(18, '牛肉（后腿）', NULL, '肉蛋奶', 106, 20.9, 1.1, 2.0, '[{"unit": "克", "gram": 1}]', '克', '🥩', 18, 0, 1),
(19, '鸡蛋（代表值）', NULL, '肉蛋奶', 139, 13.1, 2.4, 8.6, '[{"unit": "克", "gram": 1}, {"unit": "个", "gram": 50}]', '克', '🥚', 19, 0, 1),
(20, '纯牛奶（代表值，全脂）', NULL, '肉蛋奶', 65, 3.3, 4.9, 3.6, '[{"unit": "克", "gram": 1}, {"unit": "盒", "gram": 250}]', '克', '🥛', 20, 0, 1),
(21, '草鱼［白鲩、草包鱼］', '白鲩、草包鱼', '肉蛋奶', 96, 17.7, 0.5, 2.6, '[{"unit": "克", "gram": 1}]', '克', '🐟', 21, 0, 1),
(22, '黄豆［大豆］', '大豆', '豆类坚果', 390, 35.0, 34.2, 16.0, '[{"unit": "克", "gram": 1}]', '克', '🫘', 22, 0, 1),
(23, '豆腐（代表值）', NULL, '豆类坚果', 84, 6.6, 3.4, 5.3, '[{"unit": "克", "gram": 1}, {"unit": "块", "gram": 100}]', '克', '🍥', 23, 0, 1),
(24, '花生仁（生）', NULL, '豆类坚果', 574, 24.8, 21.7, 44.3, '[{"unit": "克", "gram": 1}, {"unit": "把", "gram": 20}]', '克', '🥜', 24, 0, 1),
(25, '核桃（干）［胡桃］', '胡桃', '豆类坚果', 646, 14.9, 19.1, 58.8, '[{"unit": "克", "gram": 1}, {"unit": "个", "gram": 10}]', '克', '🌰', 25, 0, 1),
(26, '酸奶（代表值，全脂）', NULL, '肉蛋奶', 86, 2.8, 12.9, 2.6, '[{"unit": "克", "gram": 1}, {"unit": "杯", "gram": 150}]', '克', '🥣', 26, 0, 1),
(27, '豆浆', NULL, '豆类坚果', 31, 3.0, 1.2, 1.6, '[{"unit": "克", "gram": 1}, {"unit": "杯", "gram": 250}]', '克', '🥛', 27, 0, 1),
(28, '花生油', NULL, '配料', 899, 0.0, 0.0, 99.9, '[{"unit": "克", "gram": 1}, {"unit": "勺", "gram": 10}]', '克', '🫗', 28, 0, 1),
(29, '燕麦', NULL, '主食', 338, 10.1, 77.4, 0.2, '[{"unit": "克", "gram": 1}, {"unit": "碗", "gram": 40}]', '克', '🥣', 29, 0, 1),
(30, '粳米（小站稻米）', NULL, '主食', 346, 6.9, 79.2, 0.7, '[{"unit": "克", "gram": 1}, {"unit": "碗", "gram": 150}]', '克', '🍚', 30, 0, 1)
ON DUPLICATE KEY UPDATE `id` = `id`;
-- ==================== 种子：50 个运动项目（kcal_per_30min 按 65kg 估算，met 留 NULL）====================
INSERT INTO `sport` (`id`, `name`, `category`, `kcal_per_30min`, `met`, `sort`, `status`) VALUES
(1, '坐着休息', '日常', 25, NULL, 1, 1),
(2, '站着闲聊', '日常', 40, NULL, 2, 1),
(3, '做家务（拖地）', '日常', 95, NULL, 3, 1),
(4, '上下楼梯（慢）', '日常', 130, NULL, 4, 1),
(5, '带孩子玩', '日常', 90, NULL, 5, 1),
(6, '慢走（4km/h）', '走路', 95, NULL, 6, 1),
(7, '快走（6km/h）', '走路', 150, NULL, 7, 1),
(8, '散步（3km/h）', '走路', 70, NULL, 8, 1),
(9, '爬山', '走路', 260, NULL, 9, 1),
(10, '徒步（负重）', '走路', 220, NULL, 10, 1),
(11, '慢跑（8km/h）', '跑步', 260, NULL, 11, 1),
(12, '跑步（10km/h）', '跑步', 320, NULL, 12, 1),
(13, '跑步（12km/h）', '跑步', 380, NULL, 13, 1),
(14, '快跑冲刺', '跑步', 420, NULL, 14, 1),
(15, '跑步机（坡度走）', '跑步', 200, NULL, 15, 1),
(16, '骑行（休闲 12km/h）', '骑行', 150, NULL, 16, 1),
(17, '骑行（中速 16km/h）', '骑行', 210, NULL, 17, 1),
(18, '动感单车', '骑行', 300, NULL, 18, 1),
(19, '骑行（快速 20km/h）', '骑行', 280, NULL, 19, 1),
(20, '室内健身车', '骑行', 190, NULL, 20, 1),
(21, '健身操（低强度）', '有氧操', 165, NULL, 21, 1),
(22, '健身操（高强度）', '有氧操', 240, NULL, 22, 1),
(23, '搏击操', '有氧操', 300, NULL, 23, 1),
(24, 'HIIT 训练', '有氧操', 330, NULL, 24, 1),
(25, '椭圆机', '有氧操', 215, NULL, 25, 1),
(26, '瑜伽', '舞蹈瑜伽', 110, NULL, 26, 1),
(27, '普拉提', '舞蹈瑜伽', 130, NULL, 27, 1),
(28, '拉丁舞', '舞蹈瑜伽', 190, NULL, 28, 1),
(29, '广场舞', '舞蹈瑜伽', 160, NULL, 29, 1),
(30, '芭蕾基础', '舞蹈瑜伽', 150, NULL, 30, 1),
(31, '自由泳（中速）', '游泳', 320, NULL, 31, 1),
(32, '蛙泳', '游泳', 280, NULL, 32, 1),
(33, '仰泳', '游泳', 260, NULL, 33, 1),
(34, '水中慢走', '游泳', 140, NULL, 34, 1),
(35, '蝶泳', '游泳', 400, NULL, 35, 1),
(36, '羽毛球', '球类运动', 220, NULL, 36, 1),
(37, '乒乓球', '球类运动', 150, NULL, 37, 1),
(38, '篮球', '球类运动', 260, NULL, 38, 1),
(39, '足球', '球类运动', 300, NULL, 39, 1),
(40, '网球', '球类运动', 240, NULL, 40, 1),
(41, '排球', '球类运动', 180, NULL, 41, 1),
(42, '跳绳（慢）', '跳绳', 240, NULL, 42, 1),
(43, '跳绳（快）', '跳绳', 360, NULL, 43, 1),
(44, '双摇跳绳', '跳绳', 420, NULL, 44, 1),
(45, '爬楼梯', '爬楼梯', 250, NULL, 45, 1),
(46, '爬楼梯（快速）', '爬楼梯', 320, NULL, 46, 1),
(47, '登山机', '爬楼梯', 280, NULL, 47, 1),
(48, '力量训练（中等）', '其他', 180, NULL, 48, 1),
(49, '力量训练（大重量）', '其他', 240, NULL, 49, 1),
(50, '拉伸', '其他', 60, NULL, 50, 1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ==================== 种子：3 个官方饮食计划 ====================
-- ★ 第一个计划的描述**逐字取自参考 App 截图**（"每日六餐…三次加餐"），
--   所以它的 meal 表里必须有 meal_index=1/2/3 三条加餐，否则描述与数据对不上。

INSERT INTO `diet_plan` (`id`, `title`, `summary`, `cover_url`, `goal`, `cycle_days`, `kcal_per_day`, `source`, `status`, `ref_count`, `owner_id`) VALUES
(1, '高效增肌 · 一周5天', '本计划专为偏瘦且具有增肌需求的人群设计，每日六餐，含早中晚三次加餐，通过少食多餐的方式刺激营养吸收，实现高效增肌！', NULL, '增肌', 5, 0, 0, 1, 0, 0),
(2, '轻断食减脂 · 7天', '每日四餐、控碳水不减蛋白：主食固定一份，蛋白按体重配足，适合想稳定掉秤又不想饿的人。', NULL, '减脂', 7, 0, 0, 1, 0, 0),
(3, '外卖党均衡 · 3天', '三餐为主、加餐一个水果，热量控制在 2000 千卡上下，点外卖也能照着凑。', NULL, '增肌', 3, 0, 0, 1, 0, 0)
ON DUPLICATE KEY UPDATE `id` = `id`;
-- plan 1 的每日热量 = 各天平均 = 2512 千卡（5 天菜单按天换主蛋白，所以各天不完全相同）
-- plan 2 的每日热量 = 各天平均 = 1598 千卡（5 天菜单按天换主蛋白，所以各天不完全相同）
-- plan 3 的每日热量 = 各天平均 = 1944 千卡（5 天菜单按天换主蛋白，所以各天不完全相同）
INSERT INTO `diet_plan_day` (`id`, `diet_plan_id`, `day_index`, `kcal_total`, `protein_g`, `carb_g`, `fat_g`) VALUES
(1, 1, 1, 2509, 146.8, 365.9, 54.5),
(2, 1, 2, 2494, 142.3, 366.5, 54.6),
(3, 1, 3, 2482, 138.5, 365.8, 55.3),
(4, 1, 4, 2539, 141.6, 367.0, 59.7),
(5, 1, 5, 2534, 133.0, 368.1, 62.5),
(6, 2, 1, 1617, 129.1, 181.4, 46.3),
(7, 2, 2, 1573, 115.3, 181.2, 47.7),
(8, 2, 3, 1593, 121.7, 182.4, 46.5),
(9, 2, 4, 1667, 120.5, 183.2, 54.9),
(10, 2, 5, 1549, 93.1, 187.0, 53.1),
(11, 2, 6, 1617, 129.1, 181.4, 46.3),
(12, 2, 7, 1573, 115.3, 181.2, 47.7),
(13, 3, 1, 1965, 99.5, 328.8, 31.9),
(14, 3, 2, 1928, 100.1, 328.4, 27.7),
(15, 3, 3, 1940, 103.8, 327.9, 27.6)
ON DUPLICATE KEY UPDATE `id` = `id`;
-- 餐次：meal_type 1早/2午/3晚/4加餐；加餐用 meal_index 区分第几次（1/2/3）
INSERT INTO `diet_plan_meal` (`id`, `diet_plan_day_id`, `meal_type`, `meal_index`, `meal_time`, `kcal`) VALUES
(1, 1, 1, 1, '07:30:00', 534),
(2, 1, 4, 1, '10:00:00', 266),
(3, 1, 2, 1, '12:00:00', 701),
(4, 1, 4, 2, '15:30:00', 258),
(5, 1, 3, 1, '18:30:00', 503),
(6, 1, 4, 3, '21:00:00', 247),
(7, 2, 1, 1, '07:30:00', 534),
(8, 2, 4, 1, '10:00:00', 266),
(9, 2, 2, 1, '12:00:00', 686),
(10, 2, 4, 2, '15:30:00', 258),
(11, 2, 3, 1, '18:30:00', 503),
(12, 2, 4, 3, '21:00:00', 247),
(13, 3, 1, 1, '07:30:00', 534),
(14, 3, 4, 1, '10:00:00', 266),
(15, 3, 2, 1, '12:00:00', 674),
(16, 3, 4, 2, '15:30:00', 258),
(17, 3, 3, 1, '18:30:00', 503),
(18, 3, 4, 3, '21:00:00', 247),
(19, 4, 1, 1, '07:30:00', 534),
(20, 4, 4, 1, '10:00:00', 266),
(21, 4, 2, 1, '12:00:00', 731),
(22, 4, 4, 2, '15:30:00', 258),
(23, 4, 3, 1, '18:30:00', 503),
(24, 4, 4, 3, '21:00:00', 247),
(25, 5, 1, 1, '07:30:00', 534),
(26, 5, 4, 1, '10:00:00', 266),
(27, 5, 2, 1, '12:00:00', 726),
(28, 5, 4, 2, '15:30:00', 258),
(29, 5, 3, 1, '18:30:00', 503),
(30, 5, 4, 3, '21:00:00', 247),
(31, 6, 1, 1, '08:00:00', 323),
(32, 6, 2, 1, '12:30:00', 769),
(33, 6, 4, 1, '16:00:00', 258),
(34, 6, 3, 1, '19:00:00', 267),
(35, 7, 1, 1, '08:00:00', 323),
(36, 7, 2, 1, '12:30:00', 725),
(37, 7, 4, 1, '16:00:00', 258),
(38, 7, 3, 1, '19:00:00', 267),
(39, 8, 1, 1, '08:00:00', 323),
(40, 8, 2, 1, '12:30:00', 745),
(41, 8, 4, 1, '16:00:00', 258),
(42, 8, 3, 1, '19:00:00', 267),
(43, 9, 1, 1, '08:00:00', 323),
(44, 9, 2, 1, '12:30:00', 819),
(45, 9, 4, 1, '16:00:00', 258),
(46, 9, 3, 1, '19:00:00', 267),
(47, 10, 1, 1, '08:00:00', 323),
(48, 10, 2, 1, '12:30:00', 701),
(49, 10, 4, 1, '16:00:00', 258),
(50, 10, 3, 1, '19:00:00', 267),
(51, 11, 1, 1, '08:00:00', 323),
(52, 11, 2, 1, '12:30:00', 769),
(53, 11, 4, 1, '16:00:00', 258),
(54, 11, 3, 1, '19:00:00', 267),
(55, 12, 1, 1, '08:00:00', 323),
(56, 12, 2, 1, '12:30:00', 725),
(57, 12, 4, 1, '16:00:00', 258),
(58, 12, 3, 1, '19:00:00', 267),
(59, 13, 1, 1, '07:30:00', 437),
(60, 13, 2, 1, '12:00:00', 877),
(61, 13, 4, 1, '15:00:00', 352),
(62, 13, 3, 1, '18:30:00', 299),
(63, 14, 1, 1, '07:30:00', 437),
(64, 14, 2, 1, '12:00:00', 840),
(65, 14, 4, 1, '15:00:00', 352),
(66, 14, 3, 1, '18:30:00', 299),
(67, 15, 1, 1, '07:30:00', 437),
(68, 15, 2, 1, '12:00:00', 852),
(69, 15, 4, 1, '15:00:00', 352),
(70, 15, 3, 1, '18:30:00', 299)
ON DUPLICATE KEY UPDATE `id` = `id`;
INSERT INTO `diet_plan_meal_item` (`id`, `meal_id`, `food_id`, `sort`, `amount`, `unit`, `kcal`) VALUES
(1, 1, 1, 1, 100, '克', 346),
(2, 1, 17, 2, 100, '克', 118),
(3, 1, 19, 3, 50, '克', 70),
(4, 2, 20, 1, 250, '克', 163),
(5, 2, 13, 2, 120, '克', 103),
(6, 3, 30, 1, 150, '克', 519),
(7, 3, 17, 2, 120, '克', 142),
(8, 3, 9, 3, 200, '克', 40),
(9, 4, 26, 1, 150, '克', 129),
(10, 4, 25, 2, 20, '克', 129),
(11, 5, 1, 1, 100, '克', 346),
(12, 5, 21, 2, 120, '克', 115),
(13, 5, 10, 3, 150, '克', 42),
(14, 6, 20, 1, 250, '克', 163),
(15, 6, 23, 2, 100, '克', 84),
(16, 7, 1, 1, 100, '克', 346),
(17, 7, 17, 2, 100, '克', 118),
(18, 7, 19, 3, 50, '克', 70),
(19, 8, 20, 1, 250, '克', 163),
(20, 8, 13, 2, 120, '克', 103),
(21, 9, 30, 1, 150, '克', 519),
(22, 9, 18, 2, 120, '克', 127),
(23, 9, 9, 3, 200, '克', 40),
(24, 10, 26, 1, 150, '克', 129),
(25, 10, 25, 2, 20, '克', 129),
(26, 11, 1, 1, 100, '克', 346),
(27, 11, 21, 2, 120, '克', 115),
(28, 11, 10, 3, 150, '克', 42),
(29, 12, 20, 1, 250, '克', 163),
(30, 12, 23, 2, 100, '克', 84),
(31, 13, 1, 1, 100, '克', 346),
(32, 13, 17, 2, 100, '克', 118),
(33, 13, 19, 3, 50, '克', 70),
(34, 14, 20, 1, 250, '克', 163),
(35, 14, 13, 2, 120, '克', 103),
(36, 15, 30, 1, 150, '克', 519),
(37, 15, 21, 2, 120, '克', 115),
(38, 15, 9, 3, 200, '克', 40),
(39, 16, 26, 1, 150, '克', 129),
(40, 16, 25, 2, 20, '克', 129),
(41, 17, 1, 1, 100, '克', 346),
(42, 17, 21, 2, 120, '克', 115),
(43, 17, 10, 3, 150, '克', 42),
(44, 18, 20, 1, 250, '克', 163),
(45, 18, 23, 2, 100, '克', 84),
(46, 19, 1, 1, 100, '克', 346),
(47, 19, 17, 2, 100, '克', 118),
(48, 19, 19, 3, 50, '克', 70),
(49, 20, 20, 1, 250, '克', 163),
(50, 20, 13, 2, 120, '克', 103),
(51, 21, 30, 1, 150, '克', 519),
(52, 21, 16, 2, 120, '克', 172),
(53, 21, 9, 3, 200, '克', 40),
(54, 22, 26, 1, 150, '克', 129),
(55, 22, 25, 2, 20, '克', 129),
(56, 23, 1, 1, 100, '克', 346),
(57, 23, 21, 2, 120, '克', 115),
(58, 23, 10, 3, 150, '克', 42),
(59, 24, 20, 1, 250, '克', 163),
(60, 24, 23, 2, 100, '克', 84),
(61, 25, 1, 1, 100, '克', 346),
(62, 25, 17, 2, 100, '克', 118),
(63, 25, 19, 3, 50, '克', 70),
(64, 26, 20, 1, 250, '克', 163),
(65, 26, 13, 2, 120, '克', 103),
(66, 27, 30, 1, 150, '克', 519),
(67, 27, 19, 2, 120, '克', 167),
(68, 27, 9, 3, 200, '克', 40),
(69, 28, 26, 1, 150, '克', 129),
(70, 28, 25, 2, 20, '克', 129),
(71, 29, 1, 1, 100, '克', 346),
(72, 29, 21, 2, 120, '克', 115),
(73, 29, 10, 3, 150, '克', 42),
(74, 30, 20, 1, 250, '克', 163),
(75, 30, 23, 2, 100, '克', 84),
(76, 31, 19, 1, 100, '克', 139),
(77, 31, 27, 2, 250, '克', 78),
(78, 31, 12, 3, 200, '克', 106),
(79, 32, 3, 1, 200, '克', 224),
(80, 32, 17, 2, 200, '克', 236),
(81, 32, 7, 3, 200, '克', 32),
(82, 32, 1, 4, 80, '克', 277),
(83, 33, 26, 1, 150, '克', 129),
(84, 33, 25, 2, 20, '克', 129),
(85, 34, 8, 1, 150, '克', 23),
(86, 34, 21, 2, 180, '克', 173),
(87, 34, 11, 3, 100, '克', 26),
(88, 34, 28, 4, 5, '克', 45),
(89, 35, 19, 1, 100, '克', 139),
(90, 35, 27, 2, 250, '克', 78),
(91, 35, 12, 3, 200, '克', 106),
(92, 36, 3, 1, 200, '克', 224),
(93, 36, 21, 2, 200, '克', 192),
(94, 36, 7, 3, 200, '克', 32),
(95, 36, 1, 4, 80, '克', 277),
(96, 37, 26, 1, 150, '克', 129),
(97, 37, 25, 2, 20, '克', 129),
(98, 38, 8, 1, 150, '克', 23),
(99, 38, 21, 2, 180, '克', 173),
(100, 38, 11, 3, 100, '克', 26),
(101, 38, 28, 4, 5, '克', 45),
(102, 39, 19, 1, 100, '克', 139),
(103, 39, 27, 2, 250, '克', 78),
(104, 39, 12, 3, 200, '克', 106),
(105, 40, 3, 1, 200, '克', 224),
(106, 40, 18, 2, 200, '克', 212),
(107, 40, 7, 3, 200, '克', 32),
(108, 40, 1, 4, 80, '克', 277),
(109, 41, 26, 1, 150, '克', 129),
(110, 41, 25, 2, 20, '克', 129),
(111, 42, 8, 1, 150, '克', 23),
(112, 42, 21, 2, 180, '克', 173),
(113, 42, 11, 3, 100, '克', 26),
(114, 42, 28, 4, 5, '克', 45),
(115, 43, 19, 1, 100, '克', 139),
(116, 43, 27, 2, 250, '克', 78),
(117, 43, 12, 3, 200, '克', 106),
(118, 44, 3, 1, 200, '克', 224),
(119, 44, 16, 2, 200, '克', 286),
(120, 44, 7, 3, 200, '克', 32),
(121, 44, 1, 4, 80, '克', 277),
(122, 45, 26, 1, 150, '克', 129),
(123, 45, 25, 2, 20, '克', 129),
(124, 46, 8, 1, 150, '克', 23),
(125, 46, 21, 2, 180, '克', 173),
(126, 46, 11, 3, 100, '克', 26),
(127, 46, 28, 4, 5, '克', 45),
(128, 47, 19, 1, 100, '克', 139),
(129, 47, 27, 2, 250, '克', 78),
(130, 47, 12, 3, 200, '克', 106),
(131, 48, 3, 1, 200, '克', 224),
(132, 48, 23, 2, 200, '克', 168),
(133, 48, 7, 3, 200, '克', 32),
(134, 48, 1, 4, 80, '克', 277),
(135, 49, 26, 1, 150, '克', 129),
(136, 49, 25, 2, 20, '克', 129),
(137, 50, 8, 1, 150, '克', 23),
(138, 50, 21, 2, 180, '克', 173),
(139, 50, 11, 3, 100, '克', 26),
(140, 50, 28, 4, 5, '克', 45),
(141, 51, 19, 1, 100, '克', 139),
(142, 51, 27, 2, 250, '克', 78),
(143, 51, 12, 3, 200, '克', 106),
(144, 52, 3, 1, 200, '克', 224),
(145, 52, 17, 2, 200, '克', 236),
(146, 52, 7, 3, 200, '克', 32),
(147, 52, 1, 4, 80, '克', 277),
(148, 53, 26, 1, 150, '克', 129),
(149, 53, 25, 2, 20, '克', 129),
(150, 54, 8, 1, 150, '克', 23),
(151, 54, 21, 2, 180, '克', 173),
(152, 54, 11, 3, 100, '克', 26),
(153, 54, 28, 4, 5, '克', 45),
(154, 55, 19, 1, 100, '克', 139),
(155, 55, 27, 2, 250, '克', 78),
(156, 55, 12, 3, 200, '克', 106),
(157, 56, 3, 1, 200, '克', 224),
(158, 56, 21, 2, 200, '克', 192),
(159, 56, 7, 3, 200, '克', 32),
(160, 56, 1, 4, 80, '克', 277),
(161, 57, 26, 1, 150, '克', 129),
(162, 57, 25, 2, 20, '克', 129),
(163, 58, 8, 1, 150, '克', 23),
(164, 58, 21, 2, 180, '克', 173),
(165, 58, 11, 3, 100, '克', 26),
(166, 58, 28, 4, 5, '克', 45),
(167, 59, 6, 1, 100, '克', 352),
(168, 59, 19, 2, 50, '克', 70),
(169, 59, 8, 3, 100, '克', 15),
(170, 60, 1, 1, 200, '克', 692),
(171, 60, 16, 2, 100, '克', 143),
(172, 60, 10, 3, 150, '克', 42),
(173, 61, 14, 1, 180, '克', 86),
(174, 61, 20, 2, 250, '克', 163),
(175, 61, 13, 3, 120, '克', 103),
(176, 62, 4, 1, 150, '克', 122),
(177, 62, 18, 2, 100, '克', 106),
(178, 62, 11, 3, 100, '克', 26),
(179, 62, 28, 4, 5, '克', 45),
(180, 63, 6, 1, 100, '克', 352),
(181, 63, 19, 2, 50, '克', 70),
(182, 63, 8, 3, 100, '克', 15),
(183, 64, 1, 1, 200, '克', 692),
(184, 64, 18, 2, 100, '克', 106),
(185, 64, 10, 3, 150, '克', 42),
(186, 65, 14, 1, 180, '克', 86),
(187, 65, 20, 2, 250, '克', 163),
(188, 65, 13, 3, 120, '克', 103),
(189, 66, 4, 1, 150, '克', 122),
(190, 66, 18, 2, 100, '克', 106),
(191, 66, 11, 3, 100, '克', 26),
(192, 66, 28, 4, 5, '克', 45),
(193, 67, 6, 1, 100, '克', 352),
(194, 67, 19, 2, 50, '克', 70),
(195, 67, 8, 3, 100, '克', 15),
(196, 68, 1, 1, 200, '克', 692),
(197, 68, 17, 2, 100, '克', 118),
(198, 68, 10, 3, 150, '克', 42),
(199, 69, 14, 1, 180, '克', 86),
(200, 69, 20, 2, 250, '克', 163),
(201, 69, 13, 3, 120, '克', 103),
(202, 70, 4, 1, 150, '克', 122),
(203, 70, 18, 2, 100, '克', 106),
(204, 70, 11, 3, 100, '克', 26),
(205, 70, 28, 4, 5, '克', 45)
ON DUPLICATE KEY UPDATE `id` = `id`;
UPDATE `diet_plan` SET `kcal_per_day` = 2512 WHERE `id` = 1;
UPDATE `diet_plan` SET `kcal_per_day` = 1598 WHERE `id` = 2;
UPDATE `diet_plan` SET `kcal_per_day` = 1944 WHERE `id` = 3;

-- ==================== P1 建完自检（应分别等于 18 / 30 / 50 / 3 / 30 / 155 左右）====================
SELECT COUNT(*) AS `表数量` FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'fm_plan';
SELECT COUNT(*) AS `食物数` FROM `food`;
SELECT COUNT(*) AS `运动项目数` FROM `sport`;
SELECT COUNT(*) AS `饮食计划数` FROM `diet_plan` WHERE `source` = 0;
SELECT COUNT(*) AS `计划天数` FROM `diet_plan_day`;
SELECT COUNT(*) AS `餐次数` FROM `diet_plan_meal`;
SELECT COUNT(*) AS `餐次食物项` FROM `diet_plan_meal_item`;
SELECT `title`, `cycle_days`, `kcal_per_day` FROM `diet_plan` ORDER BY `id`;
