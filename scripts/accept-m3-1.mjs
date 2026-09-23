#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M3-1 课表与学习记录 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：报名（幂等/付费拒绝/未上架拒绝）、课表分页与续播、
 *       分布式进度合并（Redis 缓存+ZSet+DB 三方对比）、
 *       看完判定与课表推进、MQ 积分事件、学习计划、过期任务（XXL-JOB 触发）、鉴权
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、
 *   fm-search(8085)、fm-learning(8086)、fm-gateway(10010)、xxl-job-admin(9090)
 *
 * 用法：node scripts/accept-m3-1.mjs
 *
 * 本脚本最关键的三项：
 *   1) 停播 5 秒后三方对比：Redis 缓存已清 + ZSet 任务已空 + DB moment=最新值
 *      ——分布式合并链路任何一环坏了都能抓到
 *   2) 过半判定 → learned_sections 原子累加 → 课程状态流转全链路
 *   3) XXL-JOB 通过 admin API 真实触发过期任务（不是只看执行器在线）
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

/** 直查 MySQL（进程直调 mysql.exe，不经 shell，避免路径空格转义问题） */
function sql(query) {
  const r = spawnSync(MYSQL, ['-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query], {
    encoding: 'utf8',
  });
  checkCmdResult('SQL', query, r);
  return (r.stdout || '').trim();
}

/**
 * ★★ 查询本身失败时必须炸出来，不能静默返回空字符串。
 *
 * <p>曾经踩过：mysql / redis CLI 偶尔会启动失败（Windows 上进程创建失败、
 * 瞬时资源不足、被杀软拦一下），此时 stdout 为空而 status 非 0。
 * 原来的实现只返回 `(r.stdout || '').trim()`，于是：</p>
 * <ul>
 *   <li>期望某个值的断言 → 表现为「业务没生效」，把排查方向带偏（实际是查询没跑）</li>
 *   <li><b>期望「空 / 不含 / 为 0」的断言 → 直接假通过</b>（和 401 假通过同一类，只是更隐蔽）</li>
 * </ul>
 *
 * <p>注意区分两种"空"：查询<b>成功但没有匹配行</b>时 status=0 且 stdout 为空，
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

/** 直查 Redis（走 fm-redis 容器） */
function redis(...args) {
  const env = fs.readFileSync('docker/.env', 'utf8');
  const pass = (env.match(/^REDIS_PASSWORD=(.*)$/m) || [])[1]?.trim();
  const r = spawnSync('docker',
    ['exec', 'fm-redis', 'redis-cli', '-a', pass, '--no-auth-warning', ...args],
    { encoding: 'utf8' });
  checkCmdResult('Redis', args.join(' '), r);
  return (r.stdout || '').trim();
}

/** RabbitMQ 交换机发布计数（证明事件真的发出去了） */
function exchangePublishCount() {
  const env = fs.readFileSync('docker/.env', 'utf8');
  const user = (env.match(/^RABBITMQ_USER=(.*)$/m) || [])[1]?.trim();
  const pass = (env.match(/^RABBITMQ_PASSWORD=(.*)$/m) || [])[1]?.trim();
  if (!user) return null;
  const r = spawnSync('curl', [
    '-s', '-u', `${user}:${pass}`, '--max-time', '8',
    'http://localhost:15672/api/exchanges/%2F/learning.topic',
  ], { encoding: 'utf8' });
  try {
    return JSON.parse(r.stdout)?.message_stats?.publish_in ?? 0;
  } catch {
    return null;
  }
}

/** XXL-JOB admin 登录并返回 Cookie */
async function xxlLogin() {
  const body = new URLSearchParams({ userName: 'admin', password: '123456' });
  const res = await fetch(`${XXL}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  const json = await res.json();
  const cookie = (res.headers.get('set-cookie') || '').split(';')[0];
  return json.code === 200 ? cookie : null;
}

async function xxlPost(cookie, path, form) {
  const res = await fetch(`${XXL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookie },
    body: new URLSearchParams(form).toString(),
  });
  return res.json();
}

/**
 * 完整建课流程：基本信息 → 目录(1天2动作) → 绑视频 → 教练 → 上架。
 * 返回 { courseId, sectionIds }；notShelf=true 时只建草稿不报名也不上架。
 */
async function buildCourse({ token, name, free, price, validDuration, notShelf = false }) {
  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, free, price, validDuration,
      goalType: 1, trainingLevel: 2, totalDays: 21,
      equipment: '哑铃,单杠', courseIntroduce: 'M3-1 验收用课程',
    },
  });
  const courseId = base.json?.data?.id;
  if (!courseId) return null;

  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{
      type: 1, name: 'Day 1 胸部', index: 1,
      sections: [{ type: 2, name: '哑铃卧推' }, { type: 2, name: '哑铃飞鸟' }],
    }],
  });
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const actions = tree.json?.data?.[0]?.sections || [];
  const sectionIds = actions.map((s) => s.id);

  const buf = Buffer.from(`m3-1-video-${Date.now()}`);
  const sig = await call('POST',
    `/ms/medias/upload-signature?filename=${encodeURIComponent(name)}.mp4&size=${buf.length}`, { token });
  const mediaId = sig.json?.data?.mediaId;
  if (sig.json?.data?.uploadUrl) {
    await fetch(sig.json.data.uploadUrl, { method: 'PUT', body: buf }).catch(() => null);
    await call('POST', `/ms/medias/${mediaId}/confirm`, { token, body: { duration: 180 } });
  }
  await call('POST', `/cs/courses/media/save/${courseId}`, {
    token,
    body: sectionIds.map((cataId) => ({
      cataId, mediaId, videoName: `${name}.mp4`, mediaDuration: 180,
      sets: 4, reps: 10, restSeconds: 90, actionType: 1,
    })),
  });
  await call('POST', '/cs/courses/teachers/save', {
    token, body: { id: courseId, teachers: [{ id: COACH_USER_ID, isShow: true }] },
  });
  if (notShelf) return { courseId, sectionIds };

  const up = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  return up.json?.code === 200 ? { courseId, sectionIds } : null;
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M3-1 课表与学习记录 —— 端到端验收');
  console.log('========================================');

  // ==================== 1. XXL-JOB 环境 ====================
  section('1. XXL-JOB 调度中心');
  const cookie = await xxlLogin();
  check('admin 登录成功', !!cookie);
  const groups = cookie ? await xxlPost(cookie, '/jobgroup/pageList', { start: 0, length: 50 }) : null;
  const groupList = groups?.data || [];
  const gl = groupList.find((g) => g.appname === 'fm-learning');
  const gm = groupList.find((g) => g.appname === 'fm-media');
  check('★ 执行器 fm-learning 已注册', !!gl);
  check('★ 执行器 fm-media 已注册', !!gm);
  check('★ fm-learning 执行器地址已自动注册（addressList 非空）',
    !!gl && typeof gl.registryList === 'string' ? gl.registryList.length > 0 : !!(gl && gl.addressList),
    `registry=${gl && (gl.registryList || gl.addressList)}`);
  const jobs = cookie ? await xxlPost(cookie, '/jobinfo/pageList', { jobGroup: 0, triggerStatus: -1, start: 0, length: 50 }) : null;
  const jobList = jobs?.data || [];
  check('★ 任务 lessonExpireJob 已预置', jobList.some((j) => j.executorHandler === 'lessonExpireJob'));
  check('★ 任务 mediaCleanupJob 已预置', jobList.some((j) => j.executorHandler === 'mediaCleanupJob'));
  const lessonJob = jobList.find((j) => j.executorHandler === 'lessonExpireJob');

  // ==================== 2. 登录 ====================
  section('2. 登录获取 token');
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

  // ==================== 3. 准备课程 ====================
  section('3. 准备测试课程（免费2门 / 付费1门 / 未上架1门）');
  const suffix = String(Date.now()).slice(-6);
  const a = await buildCourse({
    token, name: `M3-1学习课A${suffix}`, free: 1, price: 0, validDuration: 90,
  });
  check('课程A（免费·有效期90天）已上架', !!a);
  const b = await buildCourse({
    token, name: `M3-1付费课B${suffix}`, free: 2, price: 9900, validDuration: 90,
  });
  check('课程B（付费）已上架', !!b);
  const c = await buildCourse({
    token, name: `M3-1未上架课C${suffix}`, free: 1, price: 0, validDuration: 90, notShelf: true,
  });
  check('课程C（未上架）已建草稿', !!c);
  if (!a) return summary();

  // ==================== 4. 报名 ====================
  section('4. 报名');
  const enrollA = await call('POST', `/ls/lessons/${a.courseId}`, { token });
  check('免费课报名成功', enrollA.json?.code === 200, `msg=${enrollA.json?.msg}`);
  const lessonA = await call('GET', `/ls/lessons/${a.courseId}`, { token });
  check('★ 课表初始状态为未学习(0)', lessonA.json?.data?.status === 0,
    `status=${lessonA.json?.data?.status}`);
  check('★ expire_time 已按 validDuration 天算出', !!lessonA.json?.data?.expireTime,
    `expireTime=${lessonA.json?.data?.expireTime}`);
  await call('POST', `/ls/lessons/${a.courseId}`, { token });
  const lessonA2 = await call('GET', `/ls/lessons/${a.courseId}`, { token });
  check('★ 重复报名幂等（无新数据无报错）',
    enrollA.json?.code === 200 && lessonA2.json?.data?.id === lessonA.json?.data?.id);
  const enrollB = await call('POST', `/ls/lessons/${b.courseId}`, { token });
  check('付费课被拒且提示明确', enrollB.json?.code !== 200,
    `msg=${enrollB.json?.msg}`);
  const enrollC = await call('POST', `/ls/lessons/${c.courseId}`, { token });
  check('未上架课程被拒', enrollC.json?.code !== 200, `msg=${enrollC.json?.msg}`);
  /**
   * ★★ 必须**翻页找**，不能假设"刚报名的课一定在第 1 页"。
   *
   *   ⚠️ 实测：演示学员的课程数会随着各脚本不断报名而增长（已到 **12 门 > pageSize 10**），
   *   于是目标课落到**第 2 页** → 断言报 `我的课程分页含课程A —— total=12`，
   *   看着像"报名没生效"，其实只是**找错了页**。
   *   （m7-4 的注释里早就记过同一条：「别假设它一定在第 1 页」—— m3-1 这里漏了。）
   *
   *   ★ 与 #C35 同族，本质是**把"数据的当前状态"当成了"实现行为"**：
   *     数据是多少门、排在哪个位置，都不是实现要保证的事；
   *     实现保证的是"报名之后这门课**在**我的课程里"。
   */
  let lessonsTotal = 0
  let foundA = false
  for (let p = 1; p <= 20 && !foundA; p++) {
    const pg = await call('GET', `/ls/lessons/page?pageNo=${p}&pageSize=10`, { token });
    const list = pg.json?.data?.list || [];
    lessonsTotal = pg.json?.data?.total ?? lessonsTotal;
    if (list.length === 0) break;
    foundA = list.some((x) => x.courseId === a.courseId);
  }
  check('★ 我的课程里能找到课程A（**翻页找**，不假设在第 1 页）', foundA,
    `total=${lessonsTotal}`);

  // ==================== 5. 进度上报：分布式合并 ====================
  section('5. ★ 进度上报：未过半走分布式合并');
  const lessonId = lessonA.json?.data?.id;
  const s1 = a.sectionIds[0];
  const s2 = a.sectionIds[1];

  const mqBefore = exchangePublishCount();

  const r1 = await call('POST', '/ls/learning-records', {
    token, body: { lessonId, sectionId: s1, moment: 60, duration: 180 },
  });
  check('首次上报（60/180 未过半）成功', r1.json?.code === 200, `msg=${r1.json?.msg}`);
  const cacheKey = `learning:record:${lessonId}:${s1}`;
  check('★ Redis 进度缓存真实存在', redis('EXISTS', cacheKey) === '1');
  // 查一次，没查到再补查一次 —— 把「偶发查不到」和「真的没落库」区分开；
  // 诊断信息里带上 lessonId/sectionId，否则失败时只能看到 db= 空，无从下手
  let dbFirst = sql(`SELECT moment, finished FROM fm_learning.learning_record WHERE lesson_id=${lessonId} AND section_id=${s1}`);
  if (!dbFirst.startsWith('60')) {
    await sleep(500);
    dbFirst = sql(`SELECT moment, finished FROM fm_learning.learning_record WHERE lesson_id=${lessonId} AND section_id=${s1}`);
  }
  check('★ 首次上报直接落库（moment=60, 未完成）', dbFirst.startsWith('60'),
    `db=${JSON.stringify(dbFirst)} lessonId=${lessonId} sectionId=${s1}`);

  const r2 = await call('POST', '/ls/learning-records', {
    token, body: { lessonId, sectionId: s1, moment: 80, duration: 180 },
  });
  check('再次上报（80/180 未过半）成功', r2.json?.code === 200);
  // ★ r2 走的是「缓存 + ZSet 任务」路径（代码里此路径不直接写库），
  //   所以此刻：缓存=90（待合并的新值）、DB=60（旧值）、ZSet 有任务。
  //   ⚠️ ZSCORE 检查必须在 5 秒内完成（docker exec 约 0.3s，足够快）
  const cacheNow = redis('GET', cacheKey);
  check('★ 待合并的新值先写进缓存（moment=80）', cacheNow.includes('"moment":80'), `cache=${cacheNow.slice(0, 60)}`);
  check('★ ZSet 延迟任务真实存在', redis('ZSCORE', 'learning:record:delay', `${lessonId}:${s1}`) !== '');

  // 停播 5 秒 + 消费轮询 1 秒 → 三方对比
  await sleep(7000);
  const mergedAfter = sql(`SELECT moment FROM fm_learning.learning_record WHERE lesson_id=${lessonId} AND section_id=${s1}`);
  check('★ 停播后 DB 已更新为最新 moment', mergedAfter === '80', `moment=${mergedAfter}`);
  check('★ 停播后 Redis 缓存已清理', redis('EXISTS', cacheKey) === '0');
  check('★ 停播后 ZSet 任务已消费', redis('ZSCORE', 'learning:record:delay', `${lessonId}:${s1}`) === '');

  // ==================== 6. 过半判定与课表推进 ====================
  section('6. ★ 过半判定 → 课表推进 → MQ 事件');
  const fin1 = await call('POST', '/ls/learning-records', {
    token, body: { lessonId, sectionId: s1, moment: 100, duration: 180 },
  });
  check('过半上报（100/180）成功', fin1.json?.code === 200);
  const prog1 = await call('GET', `/ls/learning-records/course/${a.courseId}`, { token });
  const rec1 = (prog1.json?.data?.records || []).find((x) => String(x.sectionId) === String(s1));
  check('★ 动作1 标记完成', rec1?.finished === true, `finished=${rec1?.finished}`);
  const lessonAfter1 = await call('GET', `/ls/lessons/${a.courseId}`, { token });
  check('★ 课程状态转为学习中(1)', lessonAfter1.json?.data?.status === 1,
    `status=${lessonAfter1.json?.data?.status}`);
  check('★ learned_sections 原子累加为 1', lessonAfter1.json?.data?.learnedSections === 1,
    `learnedSections=${lessonAfter1.json?.data?.learnedSections}`);

  // ⚠️ RabbitMQ 的 message_stats 是周期性采集（默认 5s），发完立刻读拿到的是旧值
  let mqMid = null;
  for (let i = 0; i < 10; i++) {
    await sleep(1500);
    mqMid = exchangePublishCount();
    if (mqBefore === null || mqMid === null) break;
    if (mqMid > mqBefore) break;
  }
  check('★ 完成动作发出 section.learned 事件（计数增量 +1）',
    mqBefore !== null && mqMid !== null && mqMid > mqBefore,
    `${mqBefore} → ${mqMid}`);

  await call('POST', '/ls/learning-records', {
    token, body: { lessonId, sectionId: s2, moment: 180, duration: 180 },
  });
  const lessonAfter2 = await call('GET', `/ls/lessons/${a.courseId}`, { token });
  check('★ 全部动作完成 → 课程已学完(2)', lessonAfter2.json?.data?.status === 2,
    `status=${lessonAfter2.json?.data?.status}`);
  check('★ learned_sections = 2', lessonAfter2.json?.data?.learnedSections === 2,
    `learnedSections=${lessonAfter2.json?.data?.learnedSections}`);

  // ==================== 7. 断点续播 ====================
  section('7. 断点续播');
  const prog2 = await call('GET', `/ls/learning-records/course/${a.courseId}`, { token });
  check('latest_section_id 指向最后学习的动作', String(prog2.json?.data?.latestSectionId) === String(s2),
    `latest=${prog2.json?.data?.latestSectionId}`);
  check('两个动作的进度记录齐全', (prog2.json?.data?.records || []).length === 2,
    `records=${(prog2.json?.data?.records || []).length}`);

  // ==================== 8. 学习计划 ====================
  section('8. 学习计划');
  // ★★ 先读一次"当前的本周计划总数"，断言**增量 == 4**。
  //
  //   原实现写的是 `weekTotalPlan === 4`（硬编码），但它的口径是
  //   **该学员所有"进行中"计划的 week_freq 之和**（见 LearningPlanPageVO 的注释）——
  //   脚本每跑一次就多建一门课的计划，于是这个和会从 4 一路累积
  //   （实测失败时是 22 = 库里已有的 18 + 本次的 4）。
  //   也就是说：**实现完全正确，断言却会失败**。
  //   → 这条要测的是"这次建计划带来了多少"，那就在建之前读一次、算差值。
  const planBefore = (await call('GET', '/ls/lessons/plans?pageNo=1&pageSize=10', { token }))
    .json?.data?.weekTotalPlan ?? 0;
  const planRes = await call('POST', '/ls/lessons/plans', {
    token, body: { courseId: a.courseId, freq: 4 },
  });
  check('创建学习计划成功', planRes.json?.code === 200, `msg=${planRes.json?.msg}`);
  const plans = await call('GET', '/ls/lessons/plans?pageNo=1&pageSize=10', { token });
  check('★ 本周计划总数 +4（★ 断言增量，不是硬编码值——它会随运行次数累积）',
    plans.json?.data?.weekTotalPlan === planBefore + 4,
    `before=${planBefore} after=${plans.json?.data?.weekTotalPlan}`);
  check('★ 本周已练 >= 2（两节本周完成；多次运行会有残留）', plans.json?.data?.weekFinished >= 2,
    `weekFinished=${plans.json?.data?.weekFinished}`);
  check('计划列表含课程A', (plans.json?.data?.list || []).some((x) => x.courseId === a.courseId));

  // ==================== 9. 过期任务（XXL-JOB 真实触发） ====================
  section('9. ★ 课表过期（XXL-JOB 触发）');
  const d = await buildCourse({
    token, name: `M3-1过期课D${suffix}`, free: 1, price: 0, validDuration: 1,
  });
  check('课程D（有效期1天）已上架', !!d);
  await call('POST', `/ls/lessons/${d.courseId}`, { token });
  const lessonD = await call('GET', `/ls/lessons/${d.courseId}`, { token });
  await call('POST', '/ls/learning-records', {
    token, body: { lessonId: lessonD.json?.data?.id, sectionId: d.sectionIds[0], moment: 100, duration: 180 },
  });
  // 直接把 expire_time 改到过去（验收不等 1 天）
  sql(`UPDATE fm_learning.learning_lesson SET expire_time = NOW() - INTERVAL 1 DAY WHERE id = ${lessonD.json?.data?.id}`);
  const triggered = await xxlPost(cookie, '/jobinfo/trigger', {
    id: lessonJob?.id, executorParam: '',
  });
  check('★ 通过 admin API 触发 lessonExpireJob 成功', triggered?.code === 200,
    `code=${triggered?.code}, msg=${triggered?.msg}`);
  await sleep(2500);
  const lessonDAfter = await call('GET', `/ls/lessons/${d.courseId}`, { token });
  check('★ 课表被任务置为已过期(3)', lessonDAfter.json?.data?.status === 3,
    `status=${lessonDAfter.json?.data?.status}`);
  const progD = await call('GET', `/ls/learning-records/course/${d.courseId}`, { token });
  check('★ 已学进度保留（过期不清记录）', (progD.json?.data?.records || []).length === 1,
    `records=${(progD.json?.data?.records || []).length}`);
  // 内部兜底接口
  const manualScan = await call('GET', '/lessons/internal/expire-scan', { local: 8086 });
  // 内部接口不走网关 → 响应不被 R 包装，直接返回数字
  check('内部兜底扫描接口可用（返回处理条数）',
    manualScan.status === 200 && (typeof manualScan.json === 'number' || manualScan.json?.code === 200),
    `status=${manualScan.status}, body=${manualScan.text.slice(0, 40)}`);

  // ==================== 10. 鉴权 ====================
  section('10. 鉴权');
  const noTokenLessons = await call('GET', '/ls/lessons/page?pageNo=1&pageSize=5');
  check('未登录访问课表被拦截 401', noTokenLessons.status === 401, `status=${noTokenLessons.status}`);
  const noTokenRecords = await call('GET', '/ls/learning-records/course/1');
  check('未登录访问学习记录被拦截 401', noTokenRecords.status === 401, `status=${noTokenRecords.status}`);

  // ==================== 11. 清理 ====================
  section('11. 清理测试数据');
  for (const courseId of [a.courseId, b.courseId, d.courseId].filter(Boolean)) {
    await call('POST', '/cs/courses/downShelf', { token, body: { id: courseId } });
    await call('DELETE', `/cs/courses/delete/${courseId}`, { token });
  }
  if (c?.courseId) await call('DELETE', `/cs/courses/delete/${c.courseId}`, { token });
  sql(`DELETE FROM fm_learning.learning_lesson WHERE course_id IN (${a.courseId},${b.courseId},${c?.courseId},${d?.courseId})`);
  // ⚠️ learning_record 表**没有 course_id 字段** —— 原来那句 `WHERE course_id IS NULL OR ...`
  //    会整条报错（ERROR 1054），清理从来没生效过，残留一路累积到 30 条。
  //    改成 NOT IN 子查询：lesson 表被清空后 NOT IN 空集恒为真，正好把孤儿记录全清掉。
  //    （试过 `DELETE r FROM ... LEFT JOIN` 的写法，MySQL 报 1046 No database selected ——
  //     多表 DELETE 带别名需要默认库上下文，命令行直连时没有，所以不用它。）
  sql(`DELETE FROM fm_learning.learning_record WHERE lesson_id NOT IN (SELECT id FROM fm_learning.learning_lesson)`);
  console.log('     测试数据已清理');

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
