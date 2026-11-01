#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M3-2 签到与积分体系 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：签到（BitMap/连续奖励）、积分异步发放（幂等 + 每日上限补差额）、
 *       积分榜（Redis 实时榜 / 历史分表 / 动态表名）、赛季结算（分片广播）、XXL-JOB 预置
 *
 * 前置条件：7 个微服务 + xxl-job-admin(9090) + MySQL/Redis/RabbitMQ 已就绪
 * 用法：node scripts/accept-m3-2.mjs
 *
 * 本脚本最关键的四项：
 *   1) 积分幂等：同一条 MQ 消息发两次 → 明细不多行、榜单分数只加一次
 *      （常见实现这里会重复加分，是我们要超越的点）
 *   2) 每日上限「补差额」：笔记已得 18（上限 20）再来一笔 +3 → 实加 2
 *   3) 签到位图直查：GETBIT 验证位真的是 1，不是靠接口返回码
 *   4) 分片广播落库：分表里 id 恰好等于排名
 * ============================================================
 */

import { spawnSync } from 'child_process';
import fs from 'fs';

const GATEWAY = 'http://localhost:10010';
const XXL = 'http://localhost:9090/xxl-job-admin';
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const COACH_USER_ID = 2;

let passed = 0;
let failed = 0;
const failures = [];

function check(name, condition, detail = '') {
  if (condition) {
    passed++;
    console.log(`  ✅ ${name}`);
  } else {
    failed++;
    failures.push(name + (detail ? ` —— ${detail}` : ''));
    console.log(`  ❌ ${name}${detail ? ` —— ${detail}` : ''}`);
  }
}

function section(title) {
  console.log(`\n【${title}】`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** ==========================================================================
 * ★★ 管理端调用自动改用「管理员」token —— #A9 收尾（2026-09-21）
 *
 * 背景：**管理端接口**（建课/改课/上下架/删课/题目/建券/发券/媒资列表与删除/评分…）
 *       现在都要求**管理员**（UserType=1）。以前它们只要求「已登录」，所以本脚本一直用
 *       **学员** token 调它们 —— 那个越权漏洞就是 docs/待修复问题清单.md 里的 #A9。
 *
 * ★★ 为什么是**管理员**、而不是教练：`UserType` 是 ADMIN(1) / STUDENT(2) / COACH(3)，
 *      而 SDK 的 AdminAuthInterceptor 只认 ADMIN —— **实测教练 token 一样会被 403**。
 *      这与 `/ts/admin`、`/es/admin` 等其余管理端接口一致（它们同样只认管理员）。
 *      （"教练建题"在本项目里从来不是一条**权限**，只是 #A9 无校验时的副作用。）
 *
 * 做法：原实现改名 `callRaw`，`call` 变成一层薄包装 ——
 *       **路径命中管理端清单时**，把调用方的 token 换成管理员 token。
 *
 * ★ 两个刻意的边界：
 *   ① 调用方**没带 token**（如「未登录应 401」的用例）**不替换** ——
 *      否则会把 401 断言测成 200，变成假绿。
 *   ② 管理员 token **惰性获取**：各脚本里 staff 登录的位置不一致（有的要到文件后半段
 *      才登录），惰性获取让这层包装对**所有**脚本完全一致，也不必挪动原有代码。
 *
 * ★★ 清单必须覆盖**所有服务**，不只 fm-course：#A9 是分两次收口的 ——
 *    M7-9 给 fm-media / fm-promotion / fm-exam 开了鉴权，但**没有同步改脚本**，
 *    于是 accept-m2-1（媒资列表/删除）与 accept-m4-3（建券/发券）从那天起一直在 403，
 *    直到 M7-11 跑全量回归才暴露。所以这里**一次性列全**。
 * ========================================================================== */
let _adminToken = null
/**
 * 管理端路径清单 —— 与各服务 `application.yml` 的 `fm.auth.resource.admin.pathPatterns` 对齐：
 *   · fm-trade / fm-data / fm-aigc / fm-message / fm-learning / fm-exam → 默认 `/admin/**`
 *   · fm-media     → `/medias`（含 GET 列表与 DELETE）、`/medias/{id}/preview-url`、`DELETE /medias/{id}`
 *                    ★ `/files/**` **刻意不圈**：它是通用文件服务（bizType 含 avatar 等学员用途）
 *   · fm-promotion → `/coupons/**`、`/exchange-codes/**`
 *   · fm-course    → 见下面逐条（用 `METHOD:/path`：同前缀里混着学员端读接口）
 */
function isAdminPath(method, path) {
  // 去掉网关前缀（/cs、/ms、/prs、/es…），还原成服务内部看到的路径
  const p = String(path || '').split('?')[0]
    .replace(/^\/(as|us|ms|cs|ss|ls|rs|ts|ps|prs|sms|es|ds|ais)(?=\/)/, '');
  const m = String(method || 'GET').toUpperCase();
  // ① 通用：所有服务的 /admin/** 都是管理端
  if (p === '/admin' || p.startsWith('/admin/')) return true;
  // ② fm-media（★ 刻意不圈：upload-signature / */confirm / **/files/** —— 学员也在用）
  if (p === '/medias') return true;
  if (p.startsWith('/medias/') && p.endsWith('/preview-url')) return true;
  if (m === 'DELETE' && p.startsWith('/medias/')) return true;
  // ③ fm-promotion（★ /user-coupons/** 与 /internal/** 是学员端在用，不在其中）
  if (p === '/coupons' || p.startsWith('/coupons/')) return true;
  if (p.startsWith('/exchange-codes/')) return true;
  // ④ fm-course
  if (p === '/courses/baseInfo/save') return m === 'POST';
  if (p.startsWith('/courses/catas/save/')) return m === 'POST';
  if (p.startsWith('/courses/media/save/')) return m === 'POST';
  if (p === '/courses/teachers/save') return m === 'POST';
  if (p === '/courses/upShelf' || p === '/courses/downShelf') return m === 'POST';
  if (p.startsWith('/courses/delete/')) return m === 'DELETE';
  if (p === '/courses/page' || p === '/courses/checkName') return m === 'GET';
  if (p.startsWith('/courses/checkBeforeUpShelf/')) return m === 'GET';
  if (p.startsWith('/courses/catas/')) return m === 'GET';
  if (p.startsWith('/subjects')) return true;
  if (p.startsWith('/courses/subjects/')) return true;
  if (p === '/categorys/add') return m === 'POST';
  if (p === '/categorys/update' || p === '/categorys/disableOrEnable') return m === 'PUT';
  return p.startsWith('/categorys/') && m === 'DELETE';
}
async function call(method, path, opts = {}) {
  // ★ `noAdminSwap: true` 用于**故意用非管理员 token 探测 403** 的负向用例 ——
  //   不加这个开关就会把"应 403"换成管理员 token 测成 200，
  //   把安全断言变成永远失败（实测回归里踩到过）。
  if (opts && opts.token && !opts.noAdminSwap && isAdminPath(method, path)) {
    if (!_adminToken) {
      const r = await callRaw('POST', '/as/accounts/admin/login', {
        body: { type: 1, cellPhone: '13800010003', password: '123456', rememberMe: false },
      });
      _adminToken = r.json?.data;
      if (!_adminToken) throw new Error('管理员登录失败（管理端调用需要 UserType=1）：' + JSON.stringify(r.json));
    }
    opts = { ...opts, token: _adminToken };
  }
  return callRaw(method, path, opts);
}
async function callRaw(method, path, { body, token, local } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = token;
  const base = local ? `http://localhost:${local}` : GATEWAY;
  const res = await fetch(base + path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON */ }
  return { status: res.status, json, text };
}

function sql(query) {
  const r = spawnSync(MYSQL, ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  checkCmdResult('SQL', query, r);
  return (r.stdout || '').trim();
}
/**
 * ★★ 查询本身失败时必须炸出来，不能静默返回空字符串。
 *
 * <p>踩过的坑：mysql / redis CLI 偶尔会启动失败（Windows 上进程创建失败、
 * 瞬时资源不足、被杀软拦一下），此时 stdout 为空而 status 非 0。
 * 原来的实现只返回 `(r.stdout || '').trim()`，于是：</p>
 * <ul>
 *   <li>期望某个值的断言 → 表现为「业务没生效」，把排查方向带偏（实际是查询没跑）</li>
 *   <li><b>期望「空 / 不含 / 为 0」的断言 → 直接假通过</b>（和 401 假通过同一类，只是更隐蔽）</li>
 * </ul>
 *
 * <p>注意区分两种"空"：查询<b>成功但无匹配行</b>时 status=0 且 stdout 为空，
 * 那是合法结果，不能报错；只有 status 非 0 才是"查询本身没跑起来"。</p>
 */
function checkCmdResult(kind, cmd, r) {
  if (r.error) {
    throw new Error(`${kind} 执行失败（进程无法启动）：${cmd}\n${r.error.message}`);
  }
  if (r.status !== 0) {
    throw new Error(`${kind} 执行失败（退出码 ${r.status}）：${cmd}\n${(r.stderr || '').trim()}`);
  }
}


function envGet(key) {
  const env = fs.readFileSync('docker/.env', 'utf8');
  return (env.match(new RegExp(`^${key}=(.*)$`, 'm')) || [])[1]?.trim();
}

function redis(...args) {
  const r = spawnSync('docker', ['exec', 'fm-redis', 'redis-cli', '-a', envGet('REDIS_PASSWORD'), '--no-auth-warning', ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  return (r.stdout || '').trim();
}

function rabbitAuth() {
  return 'Basic ' + Buffer.from(`${envGet('RABBITMQ_USER')}:${envGet('RABBITMQ_PASSWORD')}`).toString('base64');
}

/** 交换机发布计数（证明事件真的发出去） */
function exchangePublishCount() {
  const r = spawnSync('curl', ['-s', '-u', `${envGet('RABBITMQ_USER')}:${envGet('RABBITMQ_PASSWORD')}`, '--max-time', '8',
    'http://localhost:15672/api/exchanges/%2F/learning.topic'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  try { return JSON.parse(r.stdout)?.message_stats?.publish_in ?? 0; } catch { return null; }
}

/**
 * 用管理 API 直接往交换机发消息——用来验证「幂等」：
 * 同一 bizId 发两次，第二次必须被唯一索引挡住。
 */
async function mqPublish(routingKey, payload) {
  const res = await fetch('http://localhost:15672/api/exchanges/%2F/learning.topic/publish', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: rabbitAuth() },
    body: JSON.stringify({
      properties: {},
      routing_key: routingKey,
      payload: JSON.stringify(payload),
      payload_encoding: 'string',
    }),
  });
  return res.json();
}

async function xxlLogin() {
  const res = await fetch(`${XXL}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ userName: 'admin', password: '123456' }).toString(),
  });
  const json = await res.json();
  return json.code === 200 ? (res.headers.get('set-cookie') || '').split(';')[0] : null;
}

async function xxlPost(cookie, path, form) {
  const res = await fetch(`${XXL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookie },
    body: new URLSearchParams(form).toString(),
  });
  return res.json();
}

/** 时间工具 */
const pad = (n) => String(n).padStart(2, '0');
const monthKey = (d) => `${d.getFullYear()}${pad(d.getMonth() + 1)}`;
const monthDayKey = (d) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;

/** 建一门课并上架（复用 M3-1 的流程） */
async function buildCourse({ token, name, validDuration = 90 }) {
  const cat = await call('POST', '/cs/categorys/add', { token, body: { name: `M32分类${Date.now() % 100000}`, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cat.json?.data?.name)
    || (cates.json?.data || [])[0];
  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, free: 1, price: 0, validDuration, firstCateId: cate?.id,
      goalType: 1, trainingLevel: 2, totalDays: 21, courseIntroduce: 'M3-2 验收用',
    },
  });
  const courseId = base.json?.data?.id;
  if (!courseId) return null;
  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{ type: 1, name: 'Day 1', index: 1, sections: [{ type: 2, name: '哑铃卧推' }] }],
  });
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const sectionId = tree.json?.data?.[0]?.sections?.[0]?.id;
  const buf = Buffer.from('m32-x');
  const sig = await call('POST', `/ms/medias/upload-signature?filename=${encodeURIComponent(name)}.mp4&size=${buf.length}`, { token });
  const mediaId = sig.json?.data?.mediaId;
  await fetch(sig.json.data.uploadUrl, { method: 'PUT', body: buf }).catch(() => null);
  await call('POST', `/ms/medias/${mediaId}/confirm`, { token, body: { duration: 180 } });
  await call('POST', `/cs/courses/media/save/${courseId}`, {
    token, body: [{ cataId: sectionId, mediaId, videoName: 'a.mp4', mediaDuration: 180 }],
  });
  await call('POST', '/cs/courses/teachers/save', { token, body: { id: courseId, teachers: [{ id: COACH_USER_ID, isShow: true }] } });
  const up = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  return up.json?.code === 200 ? { courseId, sectionId, categoryId: cate?.id } : null;
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M3-2 签到与积分体系 —— 端到端验收');
  console.log('========================================');

  // ==================== 1. XXL-JOB 环境 ====================
  section('1. XXL-JOB 任务与赛季数据');
  const cookie = await xxlLogin();
  check('admin 登录成功', !!cookie);
  const jobPage = await xxlPost(cookie, '/jobinfo/pageList', { jobGroup: 0, triggerStatus: -1, start: 0, length: 50 });
  const jobs = jobPage?.data || [];
  check('★ 6 个任务全部预置', jobs.length >= 6, `实际 ${jobs.length} 个：${jobs.map((j) => j.executorHandler).join(',')}`);
  const saveJob = jobs.find((j) => j.executorHandler === 'savePointsBoard2DB');
  check('★ 落库任务的路由策略为 SHARDING_BROADCAST（分片广播）',
    saveJob?.executorRouteStrategy === 'SHARDING_BROADCAST', `route=${saveJob?.executorRouteStrategy}`);

  // ==================== 2. 登录 ====================
  section('2. 登录与内部接口');
  const PHONE = '13800010001';
  const codeRes = await call('POST', `/us/codes?phone=${PHONE}`);
  const verifyCode = codeRes.json?.data;
  let token = null;
  if (verifyCode) {
    const loginRes = await call('POST', '/as/accounts/login', {
      body: { type: 2, cellPhone: PHONE, password: verifyCode, rememberMe: false },
    });
    token = loginRes.json?.data;
  }
  check('登录成功并拿到 token', typeof token === 'string' && token.length > 20);
  if (!token) return summary();
  const me = await call('GET', '/us/users/me', { token });
  const userId = me.json?.data?.id;
  check('拿到当前用户 id', !!userId, `userId=${userId}`);

  const seasons = await call('GET', '/ls/boards/seasons/list', { token });
  const seasonList = seasons.json?.data || [];
  // ★★ 赛季列表的语义在 #C41 里被改过一次：**只返回「已结束」的赛季**（原来是「已开始」）。
  //    ⚠️ 断言别写成「本月+上月」—— 种子里是「上月+本月」（sql/fm_learning_m3_2.sql:37），
  //    过滤掉进行中的那个月之后**只剩 1 个**，写 >=2 会假失败。
  check('★ 赛季列表非空，且只含「已结束」的赛季', seasonList.length >= 1, `seasonCount=${seasonList.length}`);
  // ★★ 不能出现「正在进行」的赛季：它的分表要到下月 1 号结算时才建（PointsBoardJob#createTableJob），
  //    列出来就是一个**点开必然为空**的选项，而且与「当前赛季（实时）」是同一个月的两个入口。
  const todayStr = new Date().toISOString().slice(0, 10);
  check('★★ 列表不含「正在进行」的赛季（否则就是个必然为空的选项）',
    seasonList.every((s) => String(s.endTime) < todayStr),
    `today=${todayStr}, ends=[${seasonList.map((s) => s.endTime).join(', ')}]`);
  // ★ 最近的已结束赛季 = 上个月（列表按 beginTime 倒序）
  const lastSeason = seasonList[0];

  const ensureNext = await call('GET', '/boards/internal/ensure-season', { local: 8086 });
  check('★ 内部接口可免登录访问 + 赛季自动滚动（创建下月赛季）',
    typeof ensureNext.json === 'number' || ensureNext.json?.code === 200,
    `body=${ensureNext.text.slice(0, 60)}`);

  // ==================== 3. 签到 ====================
  section('3. ★ 签到（Redis BitMap）');
  const today = new Date();
  const signKey = `sign:uid:${userId}:${monthKey(today)}`;
  // ★ 隔离步骤：本脚本的断言依赖精确的积分数值（比如"1 条 10 分"），
  //   但积分是全局累积的——M3-1 回归也会给同一用户造学习积分（每完成一个动作 +10），
  //   还会撞上每日上限（学习类 50 分/天），导致复跑时断言失败。
  //   所以先清掉本用户的积分数据、签到位图、实时榜，让断言可复现。
  const isoBoardKey = `boards:${monthKey(today)}`;
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=${userId} AND type IN (1, 2, 4)`);
  redis('DEL', `sign:uid:${userId}:${monthKey(today)}`);
  redis('DEL', isoBoardKey);
  console.log('  （已隔离：清空本用户积分明细 / 签到位图 / 实时榜）');
  const offset = today.getDate() - 1;
  const mqBefore = exchangePublishCount();

  const sign1 = await call('POST', '/ls/sign-records', { token });
  check('首次签到成功', sign1.json?.code === 200 && sign1.json?.data?.signDays >= 1,
    `data=${JSON.stringify(sign1.json?.data)}`);
  check('★ 直查 Redis 位图：今天那一位真的是 1', redis('GETBIT', signKey, String(offset)) === '1');
  const dup = await call('POST', '/ls/sign-records', { token });
  check('★ 重复签到被拒（SETBIT 返回旧值判重）',
    dup.json?.code !== 200 && String(dup.json?.msg).includes('签到'), `msg=${dup.json?.msg}`);

  const records = await call('GET', '/ls/sign-records', { token });
  const signDays = records.json?.data?.days || [];
  check('★ 本月签到记录含今天（位图解码方向正确）',
    signDays.includes(today.getDate()), `days=${JSON.stringify(signDays)}`);

  const signRowsFirst = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId} AND type=2`);
  check('首次签到积分落库（1 条 1 分）', signRowsFirst.replace(/\s+/g, ' ') === '1 1', `count/sum=${signRowsFirst}`);

  // 连续 7 天奖励：清今天位 + 造前 6 天 → 再签
  // ⚠️ 同一日期签两次时，第二次会被「biz_id=日期」的幂等规则挡住（这是正确行为：
  //    一天只能领一次签到分）。为了让奖励真的走到明细，先清掉当日那一笔和榜单分，
  //    模拟"新的一天"。
  redis('SETBIT', signKey, String(offset), '0');
  for (let i = 1; i <= 6; i++) {
    redis('SETBIT', signKey, String(offset - i), '1');
  }
  const todayBiz = monthDayKey(today);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=${userId} AND type=2 AND biz_id='${todayBiz}'`);
  redis('DEL', `boards:${monthKey(today)}`);

  const sign7 = await call('POST', '/ls/sign-records', { token });
  check('★ 连续第 7 天签到得奖励 +10',
    sign7.json?.data?.signDays === 7 && sign7.json?.data?.rewardPoints === 10,
    `signDays=${sign7.json?.data?.signDays}, reward=${sign7.json?.data?.rewardPoints}`);

  await sleep(1500);
  let mqAfter = exchangePublishCount();
  for (let i = 0; i < 8 && mqAfter <= mqBefore; i++) {
    await sleep(1500);
    mqAfter = exchangePublishCount();
  }
  check('★ 签到发出 sign.in 事件（交换机计数增加）', mqAfter > mqBefore, `${mqBefore} → ${mqAfter}`);

  let signRows = '';
  for (let i = 0; i < 8; i++) {
    await sleep(1200);
    signRows = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId} AND type=2`);
    if (signRows.replace(/\s+/g, ' ') === '1 11') break;
  }
  check('★ 奖励分真的进了明细：1 条 11 分（基础 1 + 奖励 10）',
    signRows.replace(/\s+/g, ' ') === '1 11', `count/sum=${signRows}`);

  const today1 = await call('GET', '/ls/boards/points/today', { token });
  const signStat = (today1.json?.data || []).find((x) => x.type === 2);
  check('今日积分明细：签到 11 分、无上限（maxPoints=0）',
    signStat?.points === 11 && signStat?.maxPoints === 0,
    `points=${signStat?.points}, max=${signStat?.maxPoints}`);

  // ==================== 4. 学习积分与幂等 ====================
  section('4. ★ 学习积分：MQ 发放 + 幂等');
  const suffix = String(Date.now()).slice(-6);
  const course = await buildCourse({ token, name: `M32学习课${suffix}` });
  check('测试课程已上架', !!course);
  if (!course) return summary();

  await call('POST', `/ls/lessons/${course.courseId}`, { token });
  const lesson = await call('GET', `/ls/lessons/${course.courseId}`, { token });
  const lessonId = lesson.json?.data?.id;
  await call('POST', '/ls/learning-records', {
    token, body: { lessonId, sectionId: course.sectionId, moment: 180, duration: 180 },
  });
  // 等待 MQ 消费
  let learnRow = '';
  for (let i = 0; i < 10; i++) {
    await sleep(1200);
    learnRow = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId} AND type=1`);
    if (learnRow.replace(/\s+/g, ' ') === '1 10') break;
  }
  check('★ 完成训练动作 → MQ → 积分 +10 落库', learnRow.replace(/\s+/g, ' ') === '1 10', `count/sum=${learnRow}`);

  const boardKey = `boards:${monthKey(today)}`;
  const scoreAfterLearn = redis('ZSCORE', boardKey, String(userId));
  check('★ 实时榜 ZSet 同步（直查 ZSCORE = 21 = 签到11 + 学习10）',
    Number(scoreAfterLearn) === 21, `score=${scoreAfterLearn}`);

  // 幂等：重发同一条 section.learned
  await mqPublish('section.learned', { userId, lessonId, sectionId: course.sectionId });
  await sleep(3000);
  const afterRepeat = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId} AND type=1`);
  const scoreAfterRepeat = redis('ZSCORE', boardKey, String(userId));
  check('★ 积分幂等：重发同一消息 → 明细不多行（仍 1 条 10 分）',
    afterRepeat.replace(/\s+/g, ' ') === '1 10', `count/sum=${afterRepeat}`);
  check('★ 积分幂等：榜单分数不变（仍 21）', Number(scoreAfterRepeat) === 21, `score=${scoreAfterRepeat}`);

  // ==================== 5. 每日上限（补差额） ====================
  section('5. ★ 每日上限：截断与丢弃');
  const noteBase = `m32note${suffix}`;
  for (let i = 1; i <= 6; i++) {
    await mqPublish('note.new', { userId, bizId: `${noteBase}-${i}` });
    await sleep(400);
  }
  let noteSum = '';
  for (let i = 0; i < 8; i++) {
    await sleep(1200);
    noteSum = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId} AND type=4`);
    if (noteSum.replace(/\s+/g, ' ') === '6 18') break;
  }
  check('6 条笔记各 +3 → 累计 18 分', noteSum.replace(/\s+/g, ' ') === '6 18', `count/sum=${noteSum}`);

  await mqPublish('note.new', { userId, bizId: `${noteBase}-7` });
  await sleep(3000);
  const cutRow = sql(`SELECT points FROM fm_learning.points_record WHERE user_id=${userId} AND biz_id='${noteBase}-7'`);
  const noteSum2 = sql(`SELECT COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId} AND type=4`);
  check('★ 上限补差额：18 分时再来 +3 → 实际只加 2 分', cutRow === '2', `points=${cutRow}`);
  check('★ 上限生效：笔记类累计恰好 20 分（上限值）', noteSum2 === '20', `sum=${noteSum2}`);

  await mqPublish('note.new', { userId, bizId: `${noteBase}-8` });
  await sleep(3000);
  const noteSum3 = sql(`SELECT COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId} AND type=4`);
  check('★ 达上限后整笔丢弃（仍 20 分）', noteSum3 === '20', `sum=${noteSum3}`);

  const today2 = await call('GET', '/ls/boards/points/today', { token });
  const noteStat = (today2.json?.data || []).find((x) => x.type === 4);
  check('今日明细：笔记 20/20 已达上限',
    noteStat?.points === 20 && noteStat?.maxPoints === 20,
    `points=${noteStat?.points}/${noteStat?.maxPoints}`);

  // ==================== 6. 积分榜 ====================
  section('6. 积分榜');
  const board = await call('GET', '/ls/boards?pageNo=1&pageSize=10', { token });
  const list = board.json?.data?.boardList || [];
  check('★ 榜单返回且排名从 1 开始', list.length > 0 && list[0].rank === 1,
    `firstRank=${list[0]?.rank}`);
  check('★ 用户名已批量补齐', !!list[0]?.name, `name=${list[0]?.name}`);
  check('★ 我的积分与排名与 Redis 直查一致',
    board.json?.data?.points === Number(redis('ZSCORE', boardKey, String(userId))),
    `api=${board.json?.data?.points}, redis=${redis('ZSCORE', boardKey, String(userId))}`);
  const rankFromRedis = Number(redis('ZREVRANK', boardKey, String(userId))) + 1;
  check('★ 我的排名正确（ZREVRANK 0-based +1）', board.json?.data?.rank === rankFromRedis,
    `api=${board.json?.data?.rank}, redis=${rankFromRedis}`);

  // ==================== 7. 未结算赛季的流水兜底（#C42） ====================
  section('7. ★ 未结算赛季：分表不存在时用积分流水兜底');
  // 造一个「已结束但从未结算」的赛季：3 个月前。
  // ★ 为什么不复用 lastSeason：§8 会去给 lastSeason 建表（顺序耦合），而且它可能残留过表。
  //   这里从 ensure-season 现造一个，只依赖「种子里没有 3 个月前那个月」，与库/页面的当前状态解耦。
  const probeMonth = new Date(today.getFullYear(), today.getMonth() - 3, 1);
  const probeMonthStr = `${probeMonth.getFullYear()}-${pad(probeMonth.getMonth() + 1)}`;
  const probeSeasonRes = await call('GET', `/boards/internal/ensure-season?month=${probeMonthStr}`, { local: 8086 });
  const probeSeasonId = Number(probeSeasonRes.text);
  check('已备好一个「已结束但未结算」的赛季', Number.isInteger(probeSeasonId) && probeSeasonId > 0,
    `month=${probeMonthStr}, id=${probeSeasonRes.text}`);
  const probeTableCount = sql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='fm_learning' AND table_name='points_board_${probeSeasonId}'`);
  check('前提成立：该赛季的分表**确实不存在**（等价于「结算任务从没跑过」）',
    probeTableCount === '0', `count=${probeTableCount}`);

  // 往流水里塞一条这个月的积分（biz_id 带标记，异常中断时便于人工兜底清理）
  const FALLBACK_BIZ = '__m3_2_fallback_probe__';
  sql(`DELETE FROM fm_learning.points_record WHERE biz_id='${FALLBACK_BIZ}'`);
  sql(`INSERT INTO fm_learning.points_record (user_id, type, points, biz_id, create_time) VALUES (${userId}, 5, 77, '${FALLBACK_BIZ}', '${probeMonthStr}-15 10:00:00')`);
  check('探针积分已写入流水', sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE biz_id='${FALLBACK_BIZ}'`) === '1');

  const fb = await call('GET', `/ls/boards?season=${probeSeasonId}&pageNo=1&pageSize=10`, { token });
  const fbList = fb.json?.data?.boardList || [];
  // ★★ 修前这里返回的是**空榜**（异常被 catch 吞掉后 return List.of()），
  //    用户看到「榜单还没有数据」，与「这个赛季真的没人得分」完全一样 —— 这就是 #C42。
  check('★★ 分表不存在时不再返回空榜，而是用积分流水实时重算',
    fbList.length === 1 && fbList[0]?.points === 77, `len=${fbList.length}, points=${fbList[0]?.points}`);
  check('★★「我的积分」也来自兜底（修前这里是 0/未上榜，用户会以为这赛季自己没得分）',
    fb.json?.data?.points === 77, `points=${fb.json?.data?.points}`);
  check('★ 兜底路径的名次从 1 开始', fbList[0]?.rank === 1, `rank=${fbList[0]?.rank}`);

  // ★★ 负向断言：兜底**只认明细**，不合并实时榜。
  //    曾经的实现会把"榜里独有、明细里没有的人"补进来（为了迁就验收脚本删明细不回滚榜造成的脏数据），
  //    后来去掉 —— 脏数据应该直接把分表补对，而不是让每次历史查询都去兼容它（见 #C42 / #C43）。
  const probeBoardKey = `boards:${probeMonth.getFullYear()}${pad(probeMonth.getMonth() + 1)}`;
  redis('ZADD', probeBoardKey, '999', '10086');
  const fb2 = await call('GET', `/ls/boards?season=${probeSeasonId}&pageNo=1&pageSize=10`, { token });
  const fb2List = fb2.json?.data?.boardList || [];
  check('★★ 榜里独有的人**不会**被合并进来（口径只有明细；这条钉住"不做跨源合并"的决定）',
    fb2List.length === 1 && fb2List[0]?.points === 77,
    `len=${fb2List.length}, points=${fb2List.map((r) => r.points).join('/')}`);
  redis('DEL', probeBoardKey);

  // 边界：赛季 id 不存在 → 返回 200 + 空榜，**不能炸 500**（老行为不能退化）
  const bogusSeason = await call('GET', '/ls/boards?season=99999&pageNo=1&pageSize=10', { token });
  check('不存在的赛季 id 返回 200 + 空榜（不炸 500）',
    bogusSeason.status === 200 && (bogusSeason.json?.data?.boardList || []).length === 0,
    `status=${bogusSeason.status}, code=${bogusSeason.json?.code}`);

  // 清理：探针积分 + 现造的赛季行（不清的话它会一直挂在下拉里）
  sql(`DELETE FROM fm_learning.points_record WHERE biz_id='${FALLBACK_BIZ}'`);
  sql(`DELETE FROM fm_learning.points_board_season WHERE id=${probeSeasonId}`);
  const fbLeft = sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE biz_id='${FALLBACK_BIZ}'`);
  const seasonLeft = sql(`SELECT COUNT(*) FROM fm_learning.points_board_season WHERE id=${probeSeasonId}`);
  check('兜底验证的探针已清理（积分明细 + 赛季行）', fbLeft === '0' && seasonLeft === '0',
    `points_record=${fbLeft}, season=${seasonLeft}`);

  // ==================== 8. 赛季结算（分片广播） ====================
  section('8. ★ 赛季结算：建表 → 落库 → 清缓存');
  const createTable = await call('GET', `/boards/internal/create-table?season=${lastSeason?.id}`, { local: 8086 });
  check('内部接口建分表成功', createTable.status === 200, `status=${createTable.status}`);
  const tableExists = sql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='fm_learning' AND table_name='points_board_${lastSeason?.id}'`);
  check('★ 分表真的存在（直查 information_schema）', tableExists === '1', `count=${tableExists}`);

  // 造上月榜单数据（3 条）
  const lastMonth = new Date(today.getFullYear(), today.getMonth() - 1, 1);
  const lastKey = `boards:${monthKey(lastMonth)}`;
  redis('DEL', lastKey);
  redis('ZADD', lastKey, '300', '101');
  redis('ZADD', lastKey, '200', '102');
  redis('ZADD', lastKey, '100', '103');
  const saveRes = await call('GET', `/boards/internal/save-to-db?season=${lastSeason?.id}`, { local: 8086 });
  check('★ 分片广播落库接口返回落库条数 3', Number(saveRes.text) === 3, `body=${saveRes.text.slice(0, 40)}`);

  const rows = sql(`SELECT id, user_id, points FROM fm_learning.points_board_${lastSeason?.id} ORDER BY id`);
  // 统一换行符与制表符再比（mysql.exe 在 Windows 下返回 \r\n）
  const normalized = rows.replace(/\r?\n/g, '|').replace(/\t/g, ' ');
  check('★ 落库数据正确且 id 恰好等于排名（1/2/3 与 300/200/100 一一对应）',
    normalized === '1 101 300|2 102 200|3 103 100', `rows=${JSON.stringify(rows)}`);

  const history = await call('GET', `/ls/boards?season=${lastSeason?.id}&pageNo=1&pageSize=10`, { token });
  const hList = history.json?.data?.boardList || [];
  check('★ 历史赛季查询走分表返回数据（动态表名生效）',
    hList.length === 3 && hList[0]?.points === 300,
    `len=${hList.length}, first=${hList[0]?.points}`);

  const clearRes = await call('GET', `/boards/internal/clear-redis?season=${lastSeason?.id}`, { local: 8086 });
  check('内部接口清理 Redis 成功', clearRes.status === 200);
  check('★ 上月榜单 key 已删除', redis('EXISTS', lastKey) === '0', `exists=${redis('EXISTS', lastKey)}`);

  // ==================== 9. 鉴权 ====================
  section('9. 鉴权');
  const noTokenSign = await call('POST', '/ls/sign-records');
  check('未登录签到被拦截 401', noTokenSign.status === 401, `status=${noTokenSign.status}`);
  const noTokenBoard = await call('GET', '/ls/boards');
  check('未登录查榜单被拦截 401', noTokenBoard.status === 401, `status=${noTokenBoard.status}`);

  // ==================== 10. 清理 ====================
  section('10. 清理测试数据');
  await call('POST', '/cs/courses/downShelf', { token, body: { id: course.courseId } });
  await call('DELETE', `/cs/courses/delete/${course.courseId}`, { token });
  if (course.categoryId) await call('DELETE', `/cs/categorys/${course.categoryId}`, { token });
  sql(`DROP TABLE IF EXISTS fm_learning.points_board_${lastSeason?.id}`);
  sql(`DELETE FROM fm_learning.learning_record WHERE lesson_id=${lessonId}`);
  sql(`DELETE FROM fm_learning.learning_lesson WHERE id=${lessonId}`);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=${userId} AND biz_id LIKE '${noteBase}%'`);
  console.log('     测试数据已清理（签到位图与积分明细保留，便于人工复核）');

  return summary();
}

function summary() {
  console.log('\n========================================');
  console.log(` 验收结果：通过 ${passed} 项，失败 ${failed} 项`);
  if (failures.length > 0) {
    console.log(' 失败明细：');
    failures.forEach((f) => console.log(`   - ${f}`));
  }
  console.log('========================================');
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error('\n验收脚本异常：', e.message);
  process.exit(2);
});
