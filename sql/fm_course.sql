-- ============================================================
-- FitMind AI 智能健身平台 —— 课程库 fm_course
-- ------------------------------------------------------------
-- M2-2a：分类 + 课程基本信息（含草稿）+ 课程正文 + 上架下架闭环
--
-- 设计要点：
--   1. 草稿表与正式表**主键相同**（course.id == course_draft.id），
--      这是「草稿-发布」机制的地基：上架时 saveOrUpdateBatch 即可，
--      下架时用 insertFromCourse 反向复制回草稿
--   2. course / course_content 的 id 不自增，由草稿表带过来
--   3. M2-2b 会追加目录、教练相关表，本文件用 IF NOT EXISTS，可重复执行
-- ============================================================

CREATE DATABASE IF NOT EXISTS fm_course DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE fm_course;

-- ==================== 分类（三级树） ====================
-- 一级：训练目标（增肌/减脂/塑形/保持）
-- 二级：训练部位（胸/背/腿/肩/手臂/核心/臀/全身）
-- 三级：器械场景（徒手/哑铃/单杠/器械/弹力带/有氧器械）
CREATE TABLE IF NOT EXISTS `category` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(64) NOT NULL                COMMENT '分类名称',
    `parent_id`   BIGINT      NOT NULL DEFAULT 0      COMMENT '父分类id，一级为0',
    `level`       TINYINT     NOT NULL DEFAULT 1      COMMENT '层级：1-训练目标 2-训练部位 3-器械场景',
    `priority`    INT                  DEFAULT 0      COMMENT '排序优先级，越大越靠前',
    `status`      TINYINT     NOT NULL DEFAULT 1      COMMENT '状态：1-启用 2-禁用',
    `create_time` DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`     BIGINT               DEFAULT NULL,
    `updater`     BIGINT               DEFAULT NULL,
    `deleted`     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_level` (`level`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程分类，三级树';

-- ==================== 课程基本信息（正式表） ====================
CREATE TABLE IF NOT EXISTS `course` (
    `id`                BIGINT       NOT NULL COMMENT '课程id，与 course_draft.id 一致',
    `name`              VARCHAR(128) NOT NULL COMMENT '课程名称',
    `course_type`       TINYINT      NOT NULL DEFAULT 1 COMMENT '课程类型：1-训练计划课 2-单动作教学 3-理论饮食课',
    `cover_url`         VARCHAR(512)          DEFAULT NULL COMMENT '封面地址',
    `first_cate_id`     BIGINT                DEFAULT NULL COMMENT '一级分类（训练目标）',
    `second_cate_id`    BIGINT                DEFAULT NULL COMMENT '二级分类（训练部位）',
    `third_cate_id`     BIGINT                DEFAULT NULL COMMENT '三级分类（器械场景）',
    `free`              TINYINT      NOT NULL DEFAULT 1 COMMENT '是否免费：1-免费 2-付费',
    `price`             INT          NOT NULL DEFAULT 0 COMMENT '价格，单位分',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-待上架 2-已上架 3-下架 4-已完结',
    `step`              TINYINT      NOT NULL DEFAULT 1 COMMENT '填写进度：1-基本信息 2-目录 3-视频 4-习题 5-教练',
    `score`             INT                   DEFAULT NULL COMMENT '评分',
    `media_duration`    INT          NOT NULL DEFAULT 0 COMMENT '视频总时长，单位秒',
    `valid_duration`    INT                   DEFAULT NULL COMMENT '课程有效期，单位天',
    `section_num`       INT          NOT NULL DEFAULT 0 COMMENT '训练动作数量',
    `goal_type`         TINYINT               DEFAULT NULL COMMENT '训练目标（冗余，便于检索）：1-增肌 2-减脂 3-塑形 4-保持',
    `training_level`    TINYINT               DEFAULT NULL COMMENT '适用水平：1-零基础 2-初级 3-中级 4-高级',
    `total_days`        INT                   DEFAULT NULL COMMENT '训练周期总天数',
    `weekly_frequency`  INT                   DEFAULT NULL COMMENT '建议每周训练次数',
    `equipment`         VARCHAR(128)          DEFAULT NULL COMMENT '所需器械，逗号分隔',
    `estimated_calories` INT                  DEFAULT NULL COMMENT '单次训练预计消耗，单位千卡',
    `enroll_start_time` DATETIME              DEFAULT NULL COMMENT '报名开始时间',
    `enroll_end_time`   DATETIME              DEFAULT NULL COMMENT '报名结束时间',
    `publish_times`     INT          NOT NULL DEFAULT 0 COMMENT '发布次数',
    `publish_time`      DATETIME              DEFAULT NULL COMMENT '最近一次上架时间',
    `create_time`       DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`           BIGINT                DEFAULT NULL,
    `updater`           BIGINT                DEFAULT NULL,
    `deleted`           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_first_cate` (`first_cate_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程基本信息（正式表）';

-- ==================== 课程基本信息（草稿表） ====================
-- 字段与 course 基本一致，多了 can_update / c_version 两个并发控制字段
CREATE TABLE IF NOT EXISTS `course_draft` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '草稿id，上架后成为 course.id',
    `name`              VARCHAR(128) NOT NULL COMMENT '课程名称',
    `course_type`       TINYINT      NOT NULL DEFAULT 1 COMMENT '课程类型：1-训练计划课 2-单动作教学 3-理论饮食课',
    `cover_url`         VARCHAR(512)          DEFAULT NULL COMMENT '封面地址',
    `first_cate_id`     BIGINT                DEFAULT NULL,
    `second_cate_id`    BIGINT                DEFAULT NULL,
    `third_cate_id`     BIGINT                DEFAULT NULL,
    `free`              TINYINT      NOT NULL DEFAULT 1 COMMENT '是否免费：1-免费 2-付费',
    `price`             INT          NOT NULL DEFAULT 0 COMMENT '价格，单位分',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-待上架 2-已上架 3-下架 4-已完结',
    `step`              TINYINT      NOT NULL DEFAULT 1 COMMENT '填写进度，支持断点续填',
    `score`             INT                   DEFAULT NULL COMMENT '评分',
    `media_duration`    INT          NOT NULL DEFAULT 0 COMMENT '视频总时长，单位秒',
    `valid_duration`    INT                   DEFAULT NULL COMMENT '课程有效期，单位天',
    `section_num`       INT          NOT NULL DEFAULT 0 COMMENT '训练动作数量',
    `goal_type`         TINYINT               DEFAULT NULL COMMENT '训练目标',
    `training_level`    TINYINT               DEFAULT NULL COMMENT '适用水平',
    `total_days`        INT                   DEFAULT NULL COMMENT '训练周期总天数',
    `weekly_frequency`  INT                   DEFAULT NULL COMMENT '建议每周训练次数',
    `equipment`         VARCHAR(128)          DEFAULT NULL COMMENT '所需器械',
    `estimated_calories` INT                  DEFAULT NULL COMMENT '单次训练预计消耗',
    `enroll_start_time` DATETIME              DEFAULT NULL COMMENT '报名开始时间',
    `enroll_end_time`   DATETIME              DEFAULT NULL COMMENT '报名结束时间',
    `can_update`        TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可修改：1-可修改 0-锁定',
    `c_version`         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `publish_time`      DATETIME              DEFAULT NULL COMMENT '最近一次上架时间',
    `create_time`       DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`           BIGINT                DEFAULT NULL,
    `updater`           BIGINT                DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程基本信息（草稿表）';

-- ==================== 课程正文（正式表） ====================
CREATE TABLE IF NOT EXISTS `course_content` (
    `id`                 BIGINT      NOT NULL COMMENT '课程id，与 course.id 一致',
    `course_introduce`   TEXT                 COMMENT '课程介绍',
    `use_people`         VARCHAR(255)         DEFAULT NULL COMMENT '适合人群',
    `course_detail`      TEXT                 COMMENT '课程详情',
    `precautions`        TEXT                 COMMENT '★ 训练注意事项（伤病提醒）',
    `required_equipment` VARCHAR(255)         DEFAULT NULL COMMENT '★ 所需器械清单',
    `create_time`        DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`            BIGINT               DEFAULT NULL,
    `updater`            BIGINT               DEFAULT NULL,
    `deleted`            TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程正文（正式表）';

-- ==================== 课程正文（草稿表） ====================
CREATE TABLE IF NOT EXISTS `course_content_draft` (
    `id`                 BIGINT      NOT NULL COMMENT '草稿id，与 course_draft.id 一致',
    `course_introduce`   TEXT                 COMMENT '课程介绍',
    `use_people`         VARCHAR(255)         DEFAULT NULL COMMENT '适合人群',
    `course_detail`      TEXT                 COMMENT '课程详情',
    `precautions`        TEXT                 COMMENT '训练注意事项',
    `required_equipment` VARCHAR(255)         DEFAULT NULL COMMENT '所需器械清单',
    `create_time`        DATETIME             DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`            BIGINT               DEFAULT NULL,
    `updater`            BIGINT               DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程正文（草稿表）';

-- ============================================================
-- M2-2b 追加：训练目录 + 课程教练
-- ------------------------------------------------------------
-- 目录的语义（健身化）：
--   章 = 训练日（如 Day 1 胸部+三头）
--   节 = 训练动作（如 哑铃卧推），动作上挂组数/次数/休息/负重等参数
-- 所以「目录本身就是一份训练计划」
-- ============================================================

-- ==================== 训练目录（正式表） ====================
CREATE TABLE IF NOT EXISTS `course_catalogue` (
    `id`                  BIGINT       NOT NULL COMMENT '主键，由代码生成（雪花）',
    `name`                VARCHAR(128) NOT NULL COMMENT '名称：训练日名 / 动作名',
    `course_id`           BIGINT       NOT NULL COMMENT '课程id',
    `type`                TINYINT      NOT NULL COMMENT '类型：1-训练日 2-训练动作 3-测试',
    `parent_catalogue_id` BIGINT       NOT NULL DEFAULT 0 COMMENT '父目录id，训练日为0',
    `c_index`             INT                   DEFAULT NULL COMMENT '排序序号',
    `media_id`            BIGINT                DEFAULT NULL COMMENT '★ 关联 fm-media.media.id',
    `video_name`          VARCHAR(255)          DEFAULT NULL COMMENT '视频名（上架完整性校验用）',
    `media_duration`      INT                   DEFAULT NULL COMMENT '视频时长，单位秒',
    `trailer`             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否可试看：1-是 0-否',
    `sets`                INT                   DEFAULT NULL COMMENT '★ 组数',
    `reps`                INT                   DEFAULT NULL COMMENT '★ 每组次数',
    `rest_seconds`        INT                   DEFAULT NULL COMMENT '★ 组间休息，单位秒',
    `weight`              DECIMAL(5, 2)         DEFAULT NULL COMMENT '★ 建议负重，单位kg',
    `duration_seconds`    INT                   DEFAULT NULL COMMENT '★ 计时类动作时长，单位秒',
    `action_type`         TINYINT               DEFAULT NULL COMMENT '★ 动作类型：1-力量 2-有氧 3-拉伸',
    `equipment`           VARCHAR(64)           DEFAULT NULL COMMENT '★ 该动作所需器械',
    `target_muscles`      VARCHAR(128)          DEFAULT NULL COMMENT '★ 目标肌群',
    `create_time`         DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`             BIGINT                DEFAULT NULL,
    `updater`             BIGINT                DEFAULT NULL,
    `deleted`             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_course_id` (`course_id`),
    KEY `idx_parent` (`parent_catalogue_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '训练目录（正式表）';

-- ==================== 训练目录（草稿表） ====================
CREATE TABLE IF NOT EXISTS `course_catalogue_draft` (
    `id`                  BIGINT       NOT NULL COMMENT '主键',
    `name`                VARCHAR(128) NOT NULL COMMENT '名称',
    `course_id`           BIGINT       NOT NULL COMMENT '课程id',
    `type`                TINYINT      NOT NULL COMMENT '类型：1-训练日 2-训练动作 3-测试',
    `parent_catalogue_id` BIGINT       NOT NULL DEFAULT 0 COMMENT '父目录id',
    `c_index`             INT                   DEFAULT NULL COMMENT '排序序号',
    `media_id`            BIGINT                DEFAULT NULL COMMENT '★ 关联媒资id',
    `video_name`          VARCHAR(255)          DEFAULT NULL COMMENT '视频名',
    `media_duration`      INT                   DEFAULT NULL COMMENT '视频时长，单位秒',
    `trailer`             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否可试看',
    `sets`                INT                   DEFAULT NULL COMMENT '★ 组数',
    `reps`                INT                   DEFAULT NULL COMMENT '★ 每组次数',
    `rest_seconds`        INT                   DEFAULT NULL COMMENT '★ 组间休息，单位秒',
    `weight`              DECIMAL(5, 2)         DEFAULT NULL COMMENT '★ 建议负重，单位kg',
    `duration_seconds`    INT                   DEFAULT NULL COMMENT '★ 计时类动作时长，单位秒',
    `action_type`         TINYINT               DEFAULT NULL COMMENT '★ 动作类型：1-力量 2-有氧 3-拉伸',
    `equipment`           VARCHAR(64)           DEFAULT NULL COMMENT '★ 该动作所需器械',
    `target_muscles`      VARCHAR(128)          DEFAULT NULL COMMENT '★ 目标肌群',
    `can_update`          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可修改',
    `create_time`         DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`             BIGINT                DEFAULT NULL,
    `updater`             BIGINT                DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_course_id` (`course_id`),
    KEY `idx_parent` (`parent_catalogue_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '训练目录（草稿表）';

-- ==================== 课程教练（正式表） ====================
CREATE TABLE IF NOT EXISTS `course_teacher` (
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `course_id`   BIGINT   NOT NULL COMMENT '课程id',
    `teacher_id`  BIGINT   NOT NULL COMMENT '教练用户id，关联 fm-user.user.id',
    `is_show`     TINYINT  NOT NULL DEFAULT 1 COMMENT '是否展示：1-展示 0-隐藏',
    `c_index`     INT               DEFAULT NULL COMMENT '排序序号',
    `create_time` DATETIME          DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME          DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`     BIGINT            DEFAULT NULL,
    `updater`     BIGINT            DEFAULT NULL,
    `deleted`     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_course_id` (`course_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程教练（正式表）';

-- ==================== 课程教练（草稿表） ====================
CREATE TABLE IF NOT EXISTS `course_teacher_draft` (
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `course_id`   BIGINT   NOT NULL COMMENT '课程id',
    `teacher_id`  BIGINT   NOT NULL COMMENT '教练用户id',
    `is_show`     TINYINT  NOT NULL DEFAULT 1 COMMENT '是否展示',
    `c_index`     INT               DEFAULT NULL COMMENT '排序序号',
    `create_time` DATETIME          DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME          DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `creater`     BIGINT            DEFAULT NULL,
    `updater`     BIGINT            DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_course_id` (`course_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程教练（草稿表）';


-- ============================================================
-- M2-2c：动作小测（题目）
-- ------------------------------------------------------------
-- 两张表：题目主数据 + 「训练节点 ↔ 题目」关联（正式 + 草稿）
--
-- 与常见实现的差异（详见 docs/M2-2c-动作小测开发方案.md）：
--   1. 常见实现的题目主数据在独立的 测评服务 服务（question / question_detail /
--      question_biz），而课程的 course_cata_subject **正式表从来没被写过**——
--      上架时关系被跨服务写进 exam 的 question_biz。
--      我们不建独立考试服务：健身题目跟着动作走，没有跨课程复用的题库需求；
--      保留 course_cata_subject 正式表，与目录/教练的「草稿-发布」机制完全同构。
--   2. 砍掉 subject_category（按三级分类给题目打标签）与 course_subject（课程级题目）：
--      常见实现里这两张表都是**死表**（只有 Mapper/XML，无任何 Service 调用）。
--   3. options 用 JSON 单列存选项数组。常见实现是 option1..option10 十个列 +
--      `ReflectUtils.setFieldValue(subject, "option" + (count + 1), ...)` 按列名拼字符串
--      反射读写——改字段名编译器不知道，运行时才炸；且十个列大部分是 NULL。
-- ============================================================

USE `fm_course`;

-- ==================== 题目主数据 ====================
-- 题目挂在课程上（course_id），不做跨课程题库：健身小测问的是
-- 「这个动作的要点」，脱离动作没有意义。
CREATE TABLE IF NOT EXISTS `subject` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '题目id',
    `course_id`     BIGINT       NOT NULL COMMENT '所属课程id',
    `name`          VARCHAR(200) NOT NULL COMMENT '题干',
    `subject_type`  TINYINT      NOT NULL COMMENT '题型：1-单选 2-多选 3-不定向 4-判断',
    `difficulty`    TINYINT      NOT NULL DEFAULT 1 COMMENT '难易度：1-简单 2-中等 3-困难',
    `options`       JSON                  DEFAULT NULL COMMENT '选项数组（JSON），判断题为空',
    `answer`        VARCHAR(64)  NOT NULL COMMENT '正确答案：选项序号（从1开始），多个逗号分隔；判断题 1-正确 2-错误',
    `analysis`      VARCHAR(500)          DEFAULT NULL COMMENT '答案解析（提交后才下发）',
    `score`         INT          NOT NULL DEFAULT 5 COMMENT '题目分值（小测计分用，与积分解耦）',
    `answer_times`  INT          NOT NULL DEFAULT 0 COMMENT '被作答次数',
    `correct_times` INT          NOT NULL DEFAULT 0 COMMENT '答对次数',
    `creater`       BIGINT                DEFAULT NULL COMMENT '创建人',
    `updater`       BIGINT                DEFAULT NULL COMMENT '更新人',
    `create_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_course` (`course_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '动作小测题目';

-- ==================== 训练节点-题目关联（正式表） ====================
-- cata_id 指向 course_catalogue.id，既可以是「训练动作」（type=2），
-- 也可以是「测评」节点（type=3）——所以动作下可以挂小测，训练日末尾也能来一次综合测评。
CREATE TABLE IF NOT EXISTS `course_cata_subject` (
    `id`          BIGINT   NOT NULL COMMENT '主键（雪花id，与目录表一致）',
    `course_id`   BIGINT   NOT NULL COMMENT '课程id',
    `cata_id`     BIGINT   NOT NULL COMMENT '目录节点id（训练动作 / 测评）',
    `subject_id`  BIGINT   NOT NULL COMMENT '题目id',
    `c_index`     INT      NOT NULL DEFAULT 0 COMMENT '题目在该节点下的顺序（第几题）',
    `create_time` DATETIME          DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME          DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_course` (`course_id`),
    KEY `idx_cata` (`cata_id`),
    KEY `idx_subject` (`subject_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '训练节点-题目关联';

-- ==================== 训练节点-题目关联（草稿表） ====================
-- 与正式表字段完全对称（不像目录表那样多一个 can_update）。
-- 同一时刻数据只在一张表里：上架时草稿 → 正式，下架时正式 → 草稿。
CREATE TABLE IF NOT EXISTS `course_cata_subject_draft` (
    `id`          BIGINT   NOT NULL COMMENT '主键（雪花id）',
    `course_id`   BIGINT   NOT NULL COMMENT '课程id',
    `cata_id`     BIGINT   NOT NULL COMMENT '目录节点id（训练动作 / 测评）',
    `subject_id`  BIGINT   NOT NULL COMMENT '题目id',
    `c_index`     INT      NOT NULL DEFAULT 0 COMMENT '题目在该节点下的顺序（第几题）',
    `create_time` DATETIME          DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME          DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_course` (`course_id`),
    KEY `idx_cata` (`cata_id`),
    KEY `idx_subject` (`subject_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '训练节点-题目关联（草稿）';
