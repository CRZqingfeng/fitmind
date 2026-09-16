-- ============================================================
-- XXL-JOB 执行器与任务预置（FitMind）
-- ------------------------------------------------------------
-- 为什么要预置而不是"启动后去控制台点"：环境可重建 —— 与 MinIO bucket、
-- ES 索引、IK 词典同一原则。docker-compose up + 执行本脚本即可用。
--
-- ⚠️ xxl_job_info / xxl_job_group 没有业务唯一键，直接 INSERT 重复执行会插重复任务，
--    所以统一「先按 handler / app_name 删，再插」——本脚本可反复执行。
-- ⚠️ 生产环境慎用 DELETE：这里是开发环境预置脚本。
--
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p < sql/xxl_job/jobs.sql
-- ============================================================
USE `xxl_job`;

-- ==================== 执行器 ====================
DELETE FROM `xxl_job_group` WHERE `app_name` IN ('fm-learning', 'fm-media', 'fm-remark', 'fm-trade', 'fm-pay', 'fm-message', 'fm-data');

INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (1, 'fm-learning', '学习服务执行器', 0, NULL, NOW());

INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (2, 'fm-media', '媒资服务执行器', 0, NULL, NOW());

INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (3, 'fm-remark', '点赞服务执行器', 0, NULL, NOW());

INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (4, 'fm-trade', '交易服务执行器', 0, NULL, NOW());

-- M4-4：支付服务执行器（退款在途对账）
-- ⚠️ fm-common 对 xxl-job-core 用的是 provided（不传递依赖），
--    所以 fm-pay 的 pom 必须显式引入 —— 否则本行只是一个"空执行器"，
--    调度中心看不到任何地址，任务永远派不出去。
INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (5, 'fm-pay', '支付服务执行器', 0, NULL, NOW());

-- M4-4：消息服务执行器（定时/重复发布通知）
INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (6, 'fm-message', '消息服务执行器', 0, NULL, NOW());

-- M5：数据中心执行器（定时把 Redis 日计数落到 MySQL）
INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (7, 'fm-data', '数据中心执行器', 0, NULL, NOW());

-- ==================== 任务 ====================
DELETE FROM `xxl_job_info` WHERE `executor_handler` IN
    ('lessonExpireJob', 'mediaCleanupJob', 'ensureSeasonJob',
     'createTableJob', 'savePointsBoard2DB', 'clearPointsBoardFromRedis',
     'likedTimesSyncJob', 'orderTimeoutCancelJob',
     'refundOrderCheckJob', 'refundRequestJob', 'messageCleanupJob',
     'dailyDataFlushJob', 'dailyDataFlushManualJob');

-- M3-1：课表过期扫描（每日 0 点）
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (1, '课表过期扫描', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0 0 * * ?', 'DO_NOTHING', 'FAILOVER', 'lessonExpireJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M3-1：僵尸媒资清理（每日 1 点）
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (2, '僵尸媒资清理', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0 1 * * ?', 'DO_NOTHING', 'FAILOVER', 'mediaCleanupJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M3-2：赛季自动滚动（每月 25 日）
-- 常见实现的赛季表靠人工插数据，没插数据则整个榜单结算静默不工作；
-- 这里让赛季自己滚动，系统自转。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (1, '赛季自动滚动（创建下月赛季）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0 0 25 * ?', 'DO_NOTHING', 'FAILOVER', 'ensureSeasonJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M3-2：建上月分表（每月 1 日 01:00）
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (1, '建上月积分榜分表', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0 1 1 * ?', 'DO_NOTHING', 'FAILOVER', 'createTableJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M3-2：★ 上月榜单落库（分片广播）
-- SHARDING_BROADCAST 是多实例并行的关键：每个实例拿到 shardIndex/shardTotal，
-- 处理 pageNo = index+1、index+1+total、... 各分片页互不重叠。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (1, '上月积分榜落库（分片广播）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 10 1 1 * ?', 'DO_NOTHING', 'SHARDING_BROADCAST', 'savePointsBoard2DB',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M3-2：清理上月 ZSet（每月 1 日 01:30）
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (1, '清理上月积分榜 Redis', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 30 1 1 * ?', 'DO_NOTHING', 'FAILOVER', 'clearPointsBoardFromRedis',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M3-3：★ 点赞数同步（每 20 秒）
-- 常见实现用 @Scheduled(fixedDelay = 20000)（JVM 内、无执行记录、多实例各自跑）；
-- 这里换成 XXL-JOB：执行记录可见、失败可重试、多实例路由可控。
-- 正确性其实不依赖「只执行一次」——SPOP 本身原子、且业务侧是覆盖更新，
-- 所以路由策略用 FAILOVER 即可（不像榜单结算那样必须分片广播才不重复）。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (3, '点赞数同步到业务方（每20秒）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0/20 * * * * ?', 'DO_NOTHING', 'FAILOVER', 'likedTimesSyncJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M4-1：订单超时取消（每分钟）
-- ★ 为什么不用 RabbitMQ 延迟队列：常见实现用它来"反复轮询第三方支付结果"，
--   而我们 M4-2 的支付是 Mock 的、回调由自己主动触发，不需要反复去问；
--   真正需要的只是"到期把订单关掉"。这类需求 M3-1 已经用 Redis ZSet + ZREM
--   原子抢占做过一遍（课表过期），这里复用同一套模式。
-- 任务内部走两条路：① ZSet 延迟队列里到期的；② 兜底扫 DB（防 ZSet 数据丢失）。
-- 正确性不依赖"只执行一次"（条件更新 + ZREM 抢占天然幂等），所以用 FAILOVER。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (4, '订单超时取消（每分钟）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 * * * * ?', 'DO_NOTHING', 'FAILOVER', 'orderTimeoutCancelJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M4-4：退款请求补发（每 2 分钟，★ 分片广播）
-- 兜的是"审批同意后调支付服务发起退款失败"（服务重启/网络抖动/Feign 超时）——
-- 失败时申请会停在"同意退款"（审批过了但钱没动），本任务把它补发出去。
-- 安全性由支付侧的 uk_biz_refund_order_no 唯一索引保证：重复发只会产生一张退款单。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (4, '退款请求补发（每2分钟·分片广播）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0/2 * * * ?', 'DO_NOTHING', 'SHARDING_BROADCAST', 'refundRequestJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M4-4：退款在途对账（每 2 分钟，★ 分片广播）
-- 与回调互为备份：回调会丢（网络抖动 / 服务重启 / 渠道重试用尽），
-- 导致"渠道退了钱、我们一直显示退款中"且永不自愈。本任务主动去问渠道，把状态纠回来。
-- 用 SHARDING_BROADCAST 是因为代码里用 shardIndex+1 作页码取数据（每片看自己那一页）；
-- 若用 FAILOVER，多实例会重复扫描同一批，分片就白分了。
-- 正确性不依赖"只执行一次"：结清走条件更新（WHERE status = 退款中），重复执行零副作用。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (5, '退款在途对账（每2分钟·分片广播）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0/2 * * * ?', 'DO_NOTHING', 'SHARDING_BROADCAST', 'refundOrderCheckJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M4-4：过期站内信清理（每日 3 点）
-- ⚠️ 注意这里**没有**"定时发布通知"的任务 —— 公告是 pull 模式，
--    定时发布只需把 public_notice.push_time 写成未来时间，用户到点自然可见
--    （expandToInbox 里有 push_time <= now 的条件）。常见实现为此专门建了
--    notice_task 表 + 定时任务，那是 push 模式的必需品，我们不需要。
-- 用 FAILOVER：清理是同一条 SQL 条件删，正确性靠条件本身，不靠"只跑一次"。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (6, '过期站内信清理（每日3点）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FAILOVER', 'messageCleanupJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M5：日数据落库（每日 1 点，落昨天的数据）
-- 为什么落"昨天"而不是"今天"：今天的计数还在涨，落下去马上就过时；
-- 而且 23:59 的成交算哪天，落昨天的话边界是清楚的。
-- 用 FAILOVER：落库本身幂等（覆盖式写入 data_daily），重复跑结果一样。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (7, '日数据落库（每日1点）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0 1 * * ?', 'DO_NOTHING', 'FAILOVER', 'dailyDataFlushJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 1);

-- M5：日数据落库（手动补跑，参数 yyyyMMdd，为空=昨天）
-- ⚠️ 这个任务的 trigger_status = 0（不自动触发）：
--    它是"人工补数"的入口，靠 xxl-job-admin 手动执行。
--    挂一个 cron 会让它每天多跑一次没意义的全量落库。
INSERT INTO `xxl_job_info`(`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`, `executor_handler`,
  `executor_param`, `executor_block_strategy`, `executor_timeout`, `executor_fail_retry_count`,
  `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`, `child_jobid`, `trigger_status`)
VALUES (7, '日数据落库-手动补跑（参数 yyyyMMdd）', NOW(), NOW(), 'fitmind', '',
  'CRON', '0 0 4 * * ?', 'DO_NOTHING', 'FAILOVER', 'dailyDataFlushManualJob',
  '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', NOW(), '', 0);

commit;
