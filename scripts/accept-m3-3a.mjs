#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M3-3a 统一点赞 + 互动问答 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：点赞判重/计数/落库/标脏/XXL-JOB 同步/MQ 通知/覆盖语义、
 *       积分幂等对照、取消点赞、提问/回答/楼中楼/匿名、管理端角色校验、
 *       隐藏级联、删除级联、鉴权
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、
 *   fm-search(8085)、fm-learning(8086)、fm-remark(8087)、
 *   fm-gateway(10010)、xxl-job-admin(9090)
 *
 * 用法：node scripts/accept-m3-3a.mjs
 *
 * 本脚本最值钱的四项：
 *   1) ★ 点赞三处一致：Redis Set 成员 / liked_record 明细 / SCARD 计数
 *   2) ★ 覆盖语义：「先污染 liked_times=99，再让真实值变成 3」→ 结果必须是 3。
 *         如果是累加，结果会是 102 —— 这条把"覆盖天然幂等"钉死了
 *   3) ★ 最新回答：answer_times=1 且 latest_answer_id = 新回答 id（修常见实现笔误）
 *   4) ★ 管理端角色校验：学员 token 调 /ls/admin/** 必须 403
 * ============================================================
 */

import { spawnSync } from 'child_process';
import fs from 'fs';

const GATEWAY = 'http://localhost:10010';
const REMARK = 'http://localhost:8087';
const XXL = 'http://localhost:9090/xxl-job-admin';
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const COACH_USER_ID = 2;
const COACH_PHONE = '13800010002';
const ADMIN_PHONE = '13800010003';
const STUDENT_PHONE = '13800010001';
const SEED_PASSWORD = '123456';

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
async function callRaw(method, path, { body, token, local, retryOn401 = true } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = token;
  const base = local ? `http://localhost:${local}` : GATEWAY;
  const doFetch = async () => {
    const res = await fetch(base + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* 非 JSON */
    }
    return { status: res.status, json, text };
  };
  let result = await doFetch();
  // ⚠️ 网关是「本地公钥验签」，实测存在极低概率的单次验签失败（80 次压测 0 次，
  //    但长跑里出现过一次）。对带 token 的请求做一次重试：
  //    · 如果是瞬时抖动 → 重试成功，避免"环境抖动"污染验收结论；
  //    · 如果 token 真的失效 → 重试照样 401，断言仍然失败，不掩盖真实问题。
  if (token && result.status === 401 && retryOn401) {
    await new Promise((r) => setTimeout(r, 200));
    result = await doFetch();
  }
  return result;
}

function sql(query) {
  const r = spawnSync(MYSQL, ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query], { encoding: 'utf8' });
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
  const r = spawnSync('docker', ['exec', 'fm-redis', 'redis-cli', '-a', envGet('REDIS_PASSWORD'), '--no-auth-warning', ...args], { encoding: 'utf8' });
  return (r.stdout || '').trim();
}

function rabbitAuth() {
  return 'Basic ' + Buffer.from(`${envGet('RABBITMQ_USER')}:${envGet('RABBITMQ_PASSWORD')}`).toString('base64');
}

/** 用管理 API 直接往交换机发消息 —— 用来验证「重复投递」下的幂等行为 */
async function mqPublish(exchange, routingKey, payload) {
  const res = await fetch(`http://localhost:15672/api/exchanges/%2F/${exchange}/publish`, {
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

/** 交换机发布计数（证明消息真的发出去了；计数是 5 秒采集一次，需要轮询） */
function exchangePublishCount(exchange) {
  const r = spawnSync('curl', ['-s', '-u', `${envGet('RABBITMQ_USER')}:${envGet('RABBITMQ_PASSWORD')}`,
    '--max-time', '8', `http://localhost:15672/api/exchanges/%2F/${exchange}`], { encoding: 'utf8' });
  try {
    return JSON.parse(r.stdout)?.message_stats?.publish_in ?? 0;
  } catch {
    return null;
  }
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

/** 学员走验证码登录，staff（教练/管理员）走密码登录 */
async function studentLogin(phone) {
  const codeRes = await call('POST', `/us/codes?phone=${phone}`);
  const verifyCode = codeRes.json?.data;
  if (!verifyCode) return null;
  const loginRes = await call('POST', '/as/accounts/login', {
    body: { type: 2, cellPhone: phone, password: verifyCode, rememberMe: false },
  });
  return loginRes.json?.data || null;
}

async function staffLogin(phone) {
  // ⚠️ 这里的 type 是「登录方式」：1-密码 2-验证码，不是用户类型
  const res = await call('POST', '/as/accounts/admin/login', {
    body: { type: 1, cellPhone: phone, password: SEED_PASSWORD, rememberMe: false },
  });
  return res.json?.data || null;
}

/** 完整建课并上架：基本信息 → 目录(1训练日2动作) → 绑视频 → 教练 → 上架 */
async function buildCourse({ token, name, free, price, validDuration }) {
  // 分类（每次用唯一名字，避免与残留数据混淆）
  const cateName = `M3-3a分类${Date.now()}`;
  await call('POST', '/cs/categorys/add', { token, body: { name: cateName, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cateName);
  if (!cate) return null;

  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, firstCateId: cate.id, free, price,
      goalType: 1, trainingLevel: 2, totalDays: 21, validDuration,
      equipment: '哑铃,单杠', courseIntroduce: 'M3-3a 验收用课程',
    },
  });
  const courseId = base.json?.data?.id;
  if (!courseId) return { cateId: cate.id };

  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{
      type: 1, name: 'Day 1 胸部', index: 1,
      sections: [{ type: 2, name: '哑铃卧推' }, { type: 2, name: '哑铃飞鸟' }],
    }],
  });
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const chapterId = tree.json?.data?.[0]?.id;
  const sectionIds = (tree.json?.data?.[0]?.sections || []).map((s) => s.id);

  // 上架要求每个动作都绑视频
  const buf = Buffer.from(`m3-3a-video-${Date.now()}`);
  const sig = await call('POST', `/ms/medias/upload-signature?filename=${encodeURIComponent(name)}.mp4&size=${buf.length}`, { token });
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
  const up = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  if (up.json?.code !== 200) return { cateId: cate.id, courseId };
  return { courseId, chapterId, sectionIds, cateId: cate.id };
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M3-3a 统一点赞 + 互动问答 —— 端到端验收');
  console.log('========================================');

  // ==================== 0. XXL-JOB 环境 ====================
  section('0. XXL-JOB 调度中心');
  const cookie = await xxlLogin();
  check('admin 登录成功', !!cookie);
  const groups = cookie ? await xxlPost(cookie, '/jobgroup/pageList', { start: 0, length: 50 }) : null;
  const remarkGroup = (groups?.data || []).find((g) => g.appname === 'fm-remark');
  check('★ 执行器 fm-remark 已注册', !!remarkGroup);
  const jobs = cookie ? await xxlPost(cookie, '/jobinfo/pageList', { jobGroup: 0, triggerStatus: -1, start: 0, length: 50 }) : null;
  const jobList = jobs?.data || [];
  const syncJob = jobList.find((j) => j.executorHandler === 'likedTimesSyncJob');
  check('★ 任务 likedTimesSyncJob 已预置（每 20 秒）', !!syncJob,
    `handlers=${jobList.map((j) => j.executorHandler).join(',')}`);
  check('同步任务路由策略为 FAILOVER（覆盖语义下无需分片广播）',
    syncJob?.executorRouteStrategy === 'FAILOVER', `route=${syncJob?.executorRouteStrategy}`);

  // ★ 验收期间先停掉这条 20 秒一次的周期调度：它会在我们断言"脏标还在吗"之前
  //   把脏标 SPOP 掉，造成随机失败。停掉后由脚本用 admin API 精确控制触发时机，
  //   验收结束时再恢复。（这是"控制变量"，不是绕过测试）
  const stopped = syncJob ? await xxlPost(cookie, '/jobinfo/stop', { id: syncJob.id }) : null;
  check('验收期间暂停周期调度（避免与断言抢时序）', stopped?.code === 200,
    `code=${stopped?.code}, msg=${stopped?.msg}`);

  // ==================== 1. 登录 ====================
  section('1. 登录获取三种角色 token');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功（staff 通道，密码登录）', typeof coachToken === 'string' && coachToken.length > 20);
  const adminToken = await staffLogin(ADMIN_PHONE);
  check('管理员登录成功（type=1 用户）', typeof adminToken === 'string' && adminToken.length > 20);
  if (!token || !coachToken || !adminToken) return summary();

  // ==================== 2. 准备课程 ====================
  section('2. 准备验收课程');
  const suffix = String(Date.now() % 1000000);
  const course = await buildCourse({
    token, name: `M3-3a问答课${suffix}`, free: 1, price: 0, validDuration: 90,
  });
  check('验收课程已上架（拿到 courseId / chapterId / sectionId）',
    !!(course?.courseId && course?.chapterId && course?.sectionIds?.length),
    JSON.stringify(course));
  if (!course?.courseId) return summary();
  const { courseId, chapterId } = course;
  const sectionId = course.sectionIds[0];

  // 隔离：清掉本用户在这门课上的历史问答（多次运行残留）
  sql(`DELETE FROM fm_learning.interaction_reply WHERE question_id IN (SELECT id FROM fm_learning.interaction_question WHERE course_id=${courseId})`);
  sql(`DELETE FROM fm_learning.interaction_question WHERE course_id=${courseId}`);

  // ==================== 3. 提问（先有问答对象，才能点赞） ====================
  section('3. 互动问答：提问');
  const q1 = await call('POST', '/ls/questions', {
    token,
    body: {
      courseId, chapterId, sectionId,
      title: '哑铃卧推手腕疼怎么办', description: '第三组时手腕内侧发酸', anonymity: false,
    },
  });
  check('学员提问成功', q1.json?.code === 200, `msg=${q1.json?.msg}`);
  const q1Id = sql(`SELECT id FROM fm_learning.interaction_question WHERE course_id=${courseId} ORDER BY id DESC LIMIT 1`);
  const q1Row = sql(`SELECT status, answer_times, hidden, user_id FROM fm_learning.interaction_question WHERE id=${q1Id}`);
  check('★ 直查 DB：新问题 status=0(未查看)、answer_times=0、hidden=0',
    q1Row.replace(/\t/g, ' ') === '0 0 0 1', `row=${JSON.stringify(q1Row)}`);

  // 匿名提问
  const q2 = await call('POST', '/ls/questions', {
    token,
    body: {
      courseId, chapterId, sectionId,
      title: '匿名：练完胸部酸痛几天算正常', description: '已经第三天了', anonymity: true,
    },
  });
  check('匿名提问成功', q2.json?.code === 200);
  const q2Id = sql(`SELECT id FROM fm_learning.interaction_question WHERE course_id=${courseId} AND anonymity=1 ORDER BY id DESC LIMIT 1`);
  const q2Hidden = sql(`SELECT user_id, anonymity FROM fm_learning.interaction_question WHERE id=${q2Id}`);
  check('★ 匿名问题的 user_id 仍真实落库（只是不对外展示）',
    q2Hidden.replace(/\t/g, ' ') === '1 1', `row=${JSON.stringify(q2Hidden)}`);

  const qPage = await call('GET', `/ls/questions/page?courseId=${courseId}&pageNo=1&pageSize=10`, { token });
  check('问题分页按课程命中（2 条）', (qPage.json?.data?.list || []).length === 2,
    `total=${qPage.json?.data?.total}`);
  const anonVo = (qPage.json?.data?.list || []).find((v) => String(v.id) === String(q2Id));
  check('★ 匿名问题在列表里不返回姓名/头像',
    anonVo && !anonVo.userName && !anonVo.userIcon, `userName=${anonVo?.userName}`);
  const minePage = await call('GET', `/ls/questions/page?courseId=${courseId}&onlyMine=true&pageNo=1&pageSize=10`, { token: coachToken });
  check('onlyMine 过滤生效（教练名下 0 条，且请求本身是成功的）',
    minePage.json?.code === 200 && (minePage.json?.data?.list || []).length === 0,
    `status=${minePage.status}, size=${(minePage.json?.data?.list || []).length}`);

  // ==================== 4. 回答与楼中楼 ====================
  section('4. 互动问答：回答与楼中楼');
  const a1 = await call('POST', '/ls/replies', {
    token, body: { questionId: q1Id, content: '握距窄一点，手腕保持中立位试试', anonymity: false },
  });
  check('学员回答成功', a1.json?.code === 200, `msg=${a1.json?.msg}`);
  const a1Id = sql(`SELECT id FROM fm_learning.interaction_reply WHERE question_id=${q1Id} AND answer_id=0 ORDER BY id DESC LIMIT 1`);
  const q1After = sql(`SELECT answer_times, latest_answer_id FROM fm_learning.interaction_question WHERE id=${q1Id}`);
  check('★ 回答后 answer_times = 1', q1After.startsWith('1'),
    `row=${JSON.stringify(q1After)}`);
  check('★ latest_answer_id 等于新回答 id（修掉常见实现把回答id写成上级id的笔误）',
    q1After.split('\t')[1] === String(a1Id), `latest=${q1After.split('\t')[1]}, 实际回答id=${a1Id}`);
  check('★ 顶层回答的 answer_id = 0（不是 NULL，查询才能一把梭）',
    sql(`SELECT answer_id FROM fm_learning.interaction_reply WHERE id=${a1Id}`) === '0');

  // 教练回答
  const a2 = await call('POST', '/ls/replies', {
    token: coachToken, body: { questionId: q1Id, content: '也可以先降重量，把动作模式练稳', anonymity: false },
  });
  check('教练回答成功（staff 身份可回答）', a2.json?.code === 200, `msg=${a2.json?.msg}`);
  check('回答数为 2', sql(`SELECT answer_times FROM fm_learning.interaction_question WHERE id=${q1Id}`) === '2');
  const a2Id = sql(`SELECT id FROM fm_learning.interaction_reply WHERE question_id=${q1Id} AND user_id=${COACH_USER_ID} AND answer_id=0 ORDER BY id DESC LIMIT 1`);
  check('★ 教练回答不加积分（服务端判定 userType=3，非学员）',
    sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE user_id=${COACH_USER_ID} AND type=3`) === '0',
    `coachPoints=${sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE user_id=${COACH_USER_ID} AND type=3`)}`);

  // 楼中楼：学员评论教练的回答
  const c1 = await call('POST', '/ls/replies', {
    token,
    body: { questionId: q1Id, answerId: a2Id, targetReplyId: a2Id, targetUserId: COACH_USER_ID, content: '好的，我试试降重量' },
  });
  check('楼中楼评论成功', c1.json?.code === 200);
  const c1Id = sql(`SELECT id FROM fm_learning.interaction_reply WHERE question_id=${q1Id} AND answer_id=${a2Id} ORDER BY id DESC LIMIT 1`);
  check('★ 评论的 answer_id 指向顶层回答、且上级 reply_times=1',
    sql(`SELECT reply_times FROM fm_learning.interaction_reply WHERE id=${a2Id}`) === '1' && !!c1Id,
    `replyTimes=${sql(`SELECT reply_times FROM fm_learning.interaction_reply WHERE id=${a2Id}`)}`);

  const replyPage = await call('GET', `/ls/replies/page?questionId=${q1Id}&pageNo=1&pageSize=10`, { token });
  check('回答分页（顶层）只返回 2 条，不含评论', (replyPage.json?.data?.list || []).length === 2,
    `size=${(replyPage.json?.data?.list || []).length}`);
  const commentPage = await call('GET', `/ls/replies/page?answerId=${a2Id}&pageNo=1&pageSize=10`, { token });
  check('评论分页返回 1 条', (commentPage.json?.data?.list || []).length === 1);

  // ==================== 5. 点赞核心 ====================
  section('5. ★ 统一点赞：判重 / 计数 / 落库 / 标脏');
  const setKey = `like:set:QA:${a1Id}`;
  const dirtyKey = 'like:dirty:QA';
  sql(`DELETE FROM fm_remark.liked_record WHERE biz_type='QA' AND biz_id IN (${a1Id},${a2Id},${c1Id})`);
  redis('SREM', setKey, '1', '2', '3');
  redis('DEL', dirtyKey);

  const like1 = await call('POST', '/rs/remarks/likes', { token, body: { bizId: a1Id, bizType: 'QA', liked: true } });
  check('学员点赞成功', like1.json?.code === 200, `msg=${like1.json?.msg}`);
  check('★ 直查 Redis：Set 里真的有该用户（SISMEMBER=1）', redis('SISMEMBER', setKey, '1') === '1');
  check('★ 直查 DB：点赞明细落库 1 行',
    sql(`SELECT COUNT(*) FROM fm_remark.liked_record WHERE biz_type='QA' AND biz_id=${a1Id}`) === '1');

  await call('POST', '/rs/remarks/likes', { token, body: { bizId: a1Id, bizType: 'QA', liked: true } });
  check('★ 重复点赞：Set 基数仍 1（SADD 返回 0 即判重）', redis('SCARD', setKey) === '1');
  check('★ 重复点赞：明细仍 1 行（唯一索引兜底）',
    sql(`SELECT COUNT(*) FROM fm_remark.liked_record WHERE biz_type='QA' AND biz_id=${a1Id}`) === '1');

  await call('POST', '/rs/remarks/likes', { token: coachToken, body: { bizId: a1Id, bizType: 'QA', liked: true } });
  check('★ 第二人点赞后 SCARD = 2（Set 基数即点赞数）', redis('SCARD', setKey) === '2',
    `scard=${redis('SCARD', setKey)}`);

  const likedList = await call('GET', `/rs/remarks/likes/list?bizType=QA&bizIds=${a1Id},${a2Id},${c1Id}`, { token });
  const likedArr = likedList.json?.data || [];
  check('★ 批量查点赞状态（pipeline）只返回我点过的',
    likedArr.length === 1 && String(likedArr[0]) === String(a1Id), `liked=${JSON.stringify(likedArr)}`);

  check('★ 真实变更才标脏：like:dirty:QA 含该回答id',
    redis('SISMEMBER', dirtyKey, String(a1Id)) === '1');

  const mqBefore = exchangePublishCount('like.record.topic');
  const triggered = await xxlPost(cookie, '/jobinfo/trigger', { id: syncJob?.id, executorParam: '' });
  check('★ 通过 admin API 真实触发 likedTimesSyncJob', triggered?.code === 200,
    `code=${triggered?.code}, msg=${triggered?.msg}`);
  await sleep(2500);
  check('★ 脏标已被 SPOP 消费清空（原子弹出，多实例不会重复处理）',
    redis('SISMEMBER', dirtyKey, String(a1Id)) === '0');

  // 交换机计数是 5 秒采集一次，需要重试
  let mqAfter = mqBefore;
  for (let i = 0; i < 8 && mqAfter <= mqBefore; i++) {
    await sleep(1500);
    mqAfter = exchangePublishCount('like.record.topic');
  }
  check('★ 交换机 like.record.topic 发布计数增加（聚合消息真的发出去了）',
    mqBefore !== null && mqAfter !== null && mqAfter > mqBefore, `${mqBefore} → ${mqAfter}`);

  let likedTimes = '';
  for (let i = 0; i < 8; i++) {
    likedTimes = sql(`SELECT liked_times FROM fm_learning.interaction_reply WHERE id=${a1Id}`);
    if (likedTimes === '2') break;
    await sleep(1200);
  }
  check('★ 回答表 liked_times 被异步同步为 2（覆盖更新）', likedTimes === '2', `liked_times=${likedTimes}`);

  // ==================== 6. ★ 覆盖语义 vs 累加语义 ====================
  section('6. ★ 覆盖语义（点赞数） vs 累加语义（积分）');
  // 先把字段污染成 99，再让真实值变成 3（管理员也点赞）
  sql(`UPDATE fm_learning.interaction_reply SET liked_times = 99 WHERE id = ${a1Id}`);
  await call('POST', '/rs/remarks/likes', { token: adminToken, body: { bizId: a1Id, bizType: 'QA', liked: true } });
  await xxlPost(cookie, '/jobinfo/trigger', { id: syncJob?.id, executorParam: '' });
  let afterPollute = '';
  for (let i = 0; i < 10; i++) {
    await sleep(1200);
    afterPollute = sql(`SELECT liked_times FROM fm_learning.interaction_reply WHERE id=${a1Id}`);
    if (afterPollute === '3') break;
  }
  check('★ 污染成 99 后同步 → 结果为真实人数 3（覆盖，不是 99+3=102）',
    afterPollute === '3', `liked_times=${afterPollute}`);
  check('★ SCARD 与冗余字段一致（3 人点赞）', redis('SCARD', setKey) === '3');

  // 直接重发同一条 MQ 消息 → 覆盖成同值，不能变成 6
  await mqPublish('like.record.topic', 'QA.times.changed', [{ bizId: Number(a1Id), likedTimes: 3 }]);
  await sleep(2500);
  const afterRedeliver = sql(`SELECT liked_times FROM fm_learning.interaction_reply WHERE id=${a1Id}`);
  check('★ 重复投递同一条消息 → 值仍为 3（覆盖天然幂等，若是累加会变 6）',
    afterRedeliver === '3', `liked_times=${afterRedeliver}`);

  // 积分对照：累加语义必须靠唯一索引
  //
  // ★★ 先清掉**今天**已得的 QA(3) 积分，否则会撞上每日上限。
  //
  //   实现里 `PointsRecordType.QA(3, "课程问答", 20)` 有每日上限 20 分，
  //   到上限后消费端是「**整笔丢弃**」（`PointsRecordServiceImpl` 第 1 步就是上限校验）。
  //   实测失败现场：今天已有 4 笔 × 5 = 20 分 → 这次 MQ 消息被丢弃 → 明细 0 行，
  //   于是断言 `1 → 1` 变成 `0 → 0` 失败 —— 而**实现是完全正确的**。
  //   同 m2-2c 对 `type=5`（动作小测）的处理：清理要覆盖"脚本自己产生、但之前没登记过"的数据。
  //   ★ 只删**别的** bizId：本 bizId（a1Id）那一行要留着 ——
  //     下面要断言的正是"它没有被重复加分"，删了就变成"0 行"、验不出累加语义。
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=1 AND type=3
       AND DATE(create_time)=CURDATE() AND biz_id <> '${a1Id}'`);
  const pointsBefore = sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE user_id=1 AND type=3 AND biz_id='${a1Id}'`);
  await mqPublish('learning.topic', 'reply.new', { userId: 1, bizId: String(a1Id) });
  await sleep(3000);
  const pointsAfter = sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE user_id=1 AND type=3 AND biz_id='${a1Id}'`);
  // ★ 判据是"重发之后**恰好 1 行**"（而不是"前后都是 1 行"）：
  //   后者会把"这一行本来就没建出来"也算进去，那是**另一个**问题（每日上限，上面已单独清理）。
  check('★ 积分（累加语义）重发同一 bizId → 明细仍 1 行，不加分',
    pointsAfter === '1', `${pointsBefore} → ${pointsAfter}`);

  // ==================== 7. 取消点赞 ====================
  section('7. 取消点赞');
  await call('POST', '/rs/remarks/likes', { token: adminToken, body: { bizId: a1Id, bizType: 'QA', liked: false } });
  check('★ 取消后 SCARD = 2', redis('SCARD', setKey) === '2', `scard=${redis('SCARD', setKey)}`);
  check('★ 取消后明细少一行（管理员那条被删）',
    sql(`SELECT COUNT(*) FROM fm_remark.liked_record WHERE biz_type='QA' AND biz_id=${a1Id}`) === '2');
  await call('POST', '/rs/remarks/likes', { token: adminToken, body: { bizId: a1Id, bizType: 'QA', liked: false } });
  check('★ 重复取消无副作用（SREM 返回 0 即空操作）', redis('SCARD', setKey) === '2');

  // ==================== 8. 排序 + 降级重建 ====================
  section('8. 排序与故障重建');
  sql(`UPDATE fm_learning.interaction_reply SET liked_times = 10 WHERE id = ${a2Id}`);
  const sorted = await call('GET', `/ls/replies/page?questionId=${q1Id}&pageNo=1&pageSize=10`, { token });
  check('★ 回答按 liked_times 倒序（点赞多的在前）',
    String(sorted.json?.data?.list?.[0]?.id) === String(a2Id),
    `first=${sorted.json?.data?.list?.[0]?.id}, expect=${a2Id}`);

  // 模拟 Redis 数据丢失：删掉 Set，再走 rebuild 从 DB 恢复
  redis('DEL', setKey);
  check('（构造）删掉 Set 后 SCARD = 0', redis('SCARD', setKey) === '0');
  const rebuilt = await call('POST', `/remarks/likes/rebuild?bizType=QA&bizIds=${a1Id}`, { local: 8087 });
  check('★ rebuild 内部接口从 DB 重建 Set',
    rebuilt.status === 200, `status=${rebuilt.status}, body=${rebuilt.text.slice(0, 40)}`);
  check('★ 重建后 SCARD 恢复为 2（与 DB 明细一致）', redis('SCARD', setKey) === '2',
    `scard=${redis('SCARD', setKey)}`);

  // ==================== 9. 管理端 ====================
  section('9. 管理端：角色校验与数据补齐');
  const studentAdmin = await call('GET', `/ls/admin/questions/page?pageNo=1&pageSize=5`, { token, noAdminSwap: true });
  check('★ 学员 token 调 /ls/admin/** → 403（常见实现只校验登录，我们补了角色）',
    studentAdmin.status === 403, `status=${studentAdmin.status}`);
  const coachAdmin = await call('GET', `/ls/admin/questions/page?pageNo=1&pageSize=5`, { token: coachToken, noAdminSwap: true });
  check('★ 教练 token 同样 403', coachAdmin.status === 403, `status=${coachAdmin.status}`);
  const adminPage = await call('GET', `/ls/admin/questions/page?courseId=${courseId}&pageNo=1&pageSize=10`, { token: adminToken });
  check('管理员 token 正常访问', adminPage.status === 200 && adminPage.json?.code === 200,
    `status=${adminPage.status}`);
  const adminVo = (adminPage.json?.data?.list || []).find((v) => String(v.id) === String(q2Id));
  check('★ 管理端补齐课程名 / 训练日名 / 动作名（跨服务批量接口）',
    adminVo?.courseName === `M3-3a问答课${suffix}` && adminVo?.chapterName === 'Day 1 胸部' && adminVo?.sectionName === '哑铃卧推',
    `course=${adminVo?.courseName}, chapter=${adminVo?.chapterName}, section=${adminVo?.sectionName}`);
  check('★ 管理端能看到匿名问题的真实提问者',
    !!adminVo?.userName, `userName=${adminVo?.userName}`);

  // 隐藏问题
  await call('PUT', `/ls/admin/questions/${q2Id}/hidden/true`, { token: adminToken });
  const hiddenDetail = await call('GET', `/ls/questions/${q2Id}`, { token });
  check('★ 隐藏后用户端详情查不到（返回 null）',
    hiddenDetail.json?.data === null, `data=${JSON.stringify(hiddenDetail.json?.data)}`);
  const adminStill = await call('GET', `/ls/admin/questions/page?courseId=${courseId}&pageNo=1&pageSize=10`, { token: adminToken });
  check('★ 隐藏后管理端仍可见',
    (adminStill.json?.data?.list || []).some((v) => String(v.id) === String(q2Id)));

  // 隐藏顶层回答 → 连带隐藏其下评论
  await call('PUT', `/ls/admin/replies/${a2Id}/hidden/true`, { token: adminToken });
  const c1Hidden = sql(`SELECT hidden FROM fm_learning.interaction_reply WHERE id=${c1Id}`);
  check('★ 隐藏顶层回答 → 其下评论一并被隐藏（直查 DB）', c1Hidden === '1', `hidden=${c1Hidden}`);
  const userReplyPage = await call('GET', `/ls/replies/page?questionId=${q1Id}&pageNo=1&pageSize=10`, { token });
  // ⚠️ "看不到"类断言必须同时校验 code：请求 401/500 时列表也是空的，会假通过
  check('★ 用户端回答列表不再出现被隐藏的回答',
    userReplyPage.json?.code === 200 && !(userReplyPage.json?.data?.list || []).some((v) => String(v.id) === String(a2Id)),
    `status=${userReplyPage.status}, code=${userReplyPage.json?.code}`);
  const adminReplyPage = await call('GET', `/ls/admin/replies/page?questionId=${q1Id}&pageNo=1&pageSize=10`, { token: adminToken });
  check('★ 管理端回答列表仍能看到被隐藏的',
    (adminReplyPage.json?.data?.list || []).some((v) => String(v.id) === String(a2Id)));

  // ==================== 10. 删除级联 ====================
  section('10. 删除问题（级联）');
  await call('DELETE', `/ls/questions/${q1Id}`, { token });
  await sleep(800);
  const leftQ = sql(`SELECT COUNT(*) FROM fm_learning.interaction_question WHERE id=${q1Id}`);
  const leftR = sql(`SELECT COUNT(*) FROM fm_learning.interaction_reply WHERE question_id=${q1Id}`);
  check('★ 删除问题后问题与回答/评论全部清空（直查 DB 均为 0）',
    leftQ === '0' && leftR === '0', `question=${leftQ}, reply=${leftR}`);
  const notMine = await call('DELETE', `/ls/questions/${q2Id}`, { token: coachToken });
  check('★ 删除他人的问题被拒绝', notMine.json?.code !== 200, `code=${notMine.json?.code}`);

  // ==================== 11. 鉴权 ====================
  section('11. 鉴权');
  const noTokenQ = await call('GET', `/ls/questions/page?courseId=${courseId}`);
  check('未登录访问问题列表被拦截 401', noTokenQ.status === 401, `status=${noTokenQ.status}`);
  const noTokenLike = await call('POST', '/rs/remarks/likes', { body: { bizId: a1Id, bizType: 'QA', liked: true } });
  check('未登录点赞被拦截 401', noTokenLike.status === 401, `status=${noTokenLike.status}`);

  // ==================== 12. 清理 ====================
  section('12. 清理测试数据');
  // ⚠️ 清理**必须检查返回码**：原来用学员 token 且不检查，删除失败也看不出来，
  //    结果课程一直留在库里、ES 里也留着（2026-09-14 发现 id=86 就是这么残留的，
  //    它后来把 m2-3 的"免费筛选"顶出了第一页）。
  //    改用教练 token（课程是教练建的），并校验结果。
  const downRes = await call('POST', '/cs/courses/downShelf', { token: coachToken, body: { id: courseId } });
  const delRes = await call('DELETE', `/cs/courses/delete/${courseId}`, { token: coachToken });
  if (delRes.json?.code !== 200) {
    console.log(`     ⚠️ 课程删除失败（courseId=${courseId}）：${delRes.json?.msg}`
      + ` | downShelf=${downRes.json?.code} ${downRes.json?.msg || ''}`);
  }
  if (course.cateId) await call('DELETE', `/cs/categorys/${course.cateId}`, { token: coachToken });
  check('★ 清理：课程已真正删除（不留 ES 孤儿文档）',
    sql(`SELECT COUNT(*) FROM fm_course.course WHERE id=${courseId}`) === '0',
    `courseId=${courseId}, msg=${delRes.json?.msg}`);
  // ⚠️ 除了删除课程，还要把本门课的问题/回答清干净：
  //    隐藏过的匿名问题（q2）不会走"删除问题"那条链路，课堂删掉后会变成孤儿数据
  sql(`DELETE FROM fm_learning.interaction_reply WHERE question_id IN (SELECT id FROM fm_learning.interaction_question WHERE course_id=${courseId})`);
  sql(`DELETE FROM fm_learning.interaction_question WHERE course_id=${courseId}`);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=1 AND type=3 AND biz_id IN ('${a1Id}','${a2Id}','${c1Id}')`);
  sql(`DELETE FROM fm_remark.liked_record WHERE biz_type='QA' AND biz_id IN (${a1Id},${a2Id},${c1Id})`);
  redis('DEL', setKey, `like:set:QA:${a2Id}`, `like:set:QA:${c1Id}`);
  redis('SREM', dirtyKey, String(a1Id), String(a2Id), String(c1Id));
  // 恢复周期调度（验收期间被暂停）
  if (syncJob?.id) {
    const restarted = await xxlPost(cookie, '/jobinfo/start', { id: syncJob.id });
    console.log(`     周期调度已恢复：${restarted?.code === 200 ? 'OK' : JSON.stringify(restarted)}`);
  }
  console.log('     测试数据已清理');

  summary();
}

function summary() {
  console.log('\n========================================');
  console.log(` 通过 ${passed} 项，失败 ${failed} 项`);
  if (failures.length) {
    console.log(' 失败项：');
    failures.forEach((f) => console.log(`   - ${f}`));
  }
  console.log('========================================');
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('脚本异常：', e);
  summary();
});
