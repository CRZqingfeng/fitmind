#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M4-1 交易基础 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：购物车（加/删/查/上限/失效标注）、预下单、下单（幂等/快照/金额回源）、
 *       订单状态机与条件更新、取消订单、★ 超时取消（延迟队列 + 兜底扫描 + 不误杀已支付）、
 *       订单查询与鉴权
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、
 *   fm-search(8085)、fm-learning(8086)、fm-remark(8087)、fm-trade(8088)、
 *   fm-gateway(10010)、xxl-job-admin(9090)
 *
 * 用法：node scripts/accept-m4-1.mjs
 *
 * 本脚本最值钱的四项：
 *   1) ★ 金额回源：直接改课程表的价格，预下单金额必须跟着变
 *        （证明"绝不信前端传的价格"不是一句口号）
 *   2) ★ 订单 id 幂等：同一个 orderId 提交两次，DB 里只有一笔订单
 *   3) ★ 条件更新：把订单改成"已支付"后再跑超时任务，它**不能被关掉**
 *        —— 这是"支付回调与超时取消并发竞争"的核心保障
 *   4) ★ 兜底扫描真的能救场：只改 create_time、不往延迟队列投消息，
 *        订单照样被关闭（Redis 丢数据时的保险绳）
 * ============================================================
 */
import { spawnSync, execSync } from 'child_process';
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





/**
 * 建课并上架。
 *
 * @param free 1-免费 2-付费（M4-1 的核心用例要付费课）
 * @param upShelf 是否直接上架（false 时返回草稿状态，调用方可以继续改）
 */
async function buildCourse({ token, name, suffix, free = 2, price = 19900, upShelf = true }) {
  const cateName = `M4-1分类${suffix}`;
  await call('POST', '/cs/categorys/add', { token, body: { name: cateName, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cateName);
  if (!cate) return null;

  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, firstCateId: cate.id, free, price,
      goalType: 1, trainingLevel: 2, totalDays: 21, validDuration: 90,
      equipment: '哑铃', courseIntroduce: 'M4-1 交易验收用课程',
    },
  });
  const courseId = base.json?.data?.id;
  if (!courseId) return { cateId: cate.id };

  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{
      type: 1, name: 'Day 1 胸部', index: 1,
      sections: [{ type: 2, name: '哑铃卧推' }],
    }],
  });
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const chapterId = tree.json?.data?.[0]?.id;
  const sectionIds = (tree.json?.data?.[0]?.sections || []).map((s) => s.id);

  const buf = Buffer.from(`m4-1-video-${Date.now()}`);
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

  if (upShelf) {
    const up = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
    if (up.json?.code !== 200) return { cateId: cate.id, courseId, upFailed: up.json?.msg };
  }
  return { courseId, chapterId, sectionIds, cateId: cate.id };
}

/** 预下单（拿 orderId + 金额预览） */
async function prePlace(token, courseIds) {
  return call('GET', `/ts/orders/prePlace?courseIds=${courseIds.join(',')}`, { token });
}

/** 改课程正式表的价格（验证"金额回源"用） */
function setCoursePrice(courseId, price) {
  sql(`UPDATE fm_course.course SET price = ${price} WHERE id = ${courseId}`);
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M4-1 交易基础 —— 端到端验收');
  console.log('========================================');

  // ==================== 0. XXL-JOB ====================
  section('0. XXL-JOB：订单超时任务已预置');
  const cookie = await xxlLogin();
  check('admin 登录成功', !!cookie);
  const jobs = cookie ? await xxlPost(cookie, '/jobinfo/pageList', { jobGroup: 4, triggerStatus: -1, start: 0, length: 50 }) : null;
  const timeoutJob = (jobs?.data || []).find((j) => j.executorHandler === 'orderTimeoutCancelJob');
  check('★ orderTimeoutCancelJob 已挂在 fm-trade 执行器（分组 4）下', !!timeoutJob,
    `jobs=${(jobs?.data || []).map((j) => j.executorHandler).join(',')}`);
  if (timeoutJob) {
    const stopped = await xxlPost(cookie, '/jobinfo/stop', { id: timeoutJob.id });
    check('验收期间暂停每分钟调度（脚本精确控制触发时机）', stopped?.code === 200);
  }

  // ==================== 1. 登录 ====================
  section('1. 登录');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功', typeof coachToken === 'string' && coachToken.length > 20);
  if (!token || !coachToken) return summary();

  // ==================== 2. 准备课程 ====================
  section('2. 准备课程（两门付费课 + 一门免费课）');
  const suffix = String(Date.now() % 1000000);
  const c1 = await buildCourse({ token, name: `M4-1付费课A${suffix}`, suffix, free: 2, price: 19900 });
  const c2 = await buildCourse({ token, name: `M4-1付费课B${suffix}`, suffix, free: 2, price: 29900 });
  const cFree = await buildCourse({ token, name: `M4-1免费课${suffix}`, suffix, free: 1, price: 0 });
  // 第三门付费课专供"取消 / 超时"用例：前两门课在别的用例里会被下单，
  // 复用它们会撞上"该课程已有待支付订单"的校验（用例之间必须是独立的数据）
  const c3 = await buildCourse({ token, name: `M4-1付费课C${suffix}`, suffix, free: 2, price: 9900 });
  check('付费课 A 已上架（199.00 元）', !!c1?.courseId && !c1.upFailed, JSON.stringify(c1));
  check('付费课 B 已上架（299.00 元）', !!c2?.courseId && !c2.upFailed, JSON.stringify(c2));
  check('付费课 C 已上架（99.00 元，取消用例专用）', !!c3?.courseId && !c3.upFailed, JSON.stringify(c3));
  check('免费课已上架', !!cFree?.courseId, JSON.stringify(cFree));
  if (!c1?.courseId || !c2?.courseId || !c3?.courseId || !cFree?.courseId) return summary();
  const A = c1.courseId, B = c2.courseId, C = c3.courseId, F = cFree.courseId;

  // 隔离：清掉本次课程的历史交易数据
  sql(`DELETE FROM fm_trade.trade_cart WHERE user_id IN (1,2)`);
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id IN (${A},${B},${C},${F})`);
  // ★ 明细与订单必须**同一维度**删（先明细、后订单）—— 否则漏出孤儿明细（#C34）
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id IN (SELECT id FROM fm_trade.trade_order WHERE user_id IN (1,2))`);
  sql(`DELETE FROM fm_trade.trade_order WHERE user_id IN (1,2)`);
  redis('DEL', 'trade:order:delay');

  // ==================== 3. 购物车 ====================
  section('3. 购物车');
  const add1 = await call('POST', '/ts/carts', { token, body: { courseId: A } });
  check('加入购物车成功', add1.json?.code === 200, `msg=${add1.json?.msg}`);
  check('★ 直查 DB：购物车落了课程名/封面/价格快照',
    sql(`SELECT course_name, price FROM fm_trade.trade_cart WHERE user_id=1 AND course_id=${A}`).includes(`M4-1付费课A${suffix}`)
    && sql(`SELECT price FROM fm_trade.trade_cart WHERE user_id=1 AND course_id=${A}`) === '19900');

  const addDup = await call('POST', '/ts/carts', { token, body: { courseId: A } });
  check('★ 重复加入被拒（唯一索引兜底，不是靠先查再插）',
    addDup.json?.code !== 200 && (addDup.json?.msg || '').includes('已经在购物车'), `msg=${addDup.json?.msg}`);

  await call('POST', '/ts/carts', { token, body: { courseId: B } });
  const carts = await call('GET', '/ts/carts', { token });
  check('购物车列表返回 2 条', (carts.json?.data || []).length === 2);
  const cartA = (carts.json?.data || []).find((c) => String(c.courseId) === String(A));
  check('★ 列表里的价格是课程最新价（19900）', cartA?.price === 19900, `price=${cartA?.price}`);
  check('有效课程 invalid=false', cartA?.invalid === false);

  // 免费课加购 → 应被拒（直接报名即可，不该进购物车）
  const addFree = await call('POST', '/ts/carts', { token, body: { courseId: F } });
  check('★ 免费课不能加入购物车（提示直接报名）',
    addFree.json?.code !== 200 && (addFree.json?.msg || '').includes('免费'), `msg=${addFree.json?.msg}`);

  // 容量上限：直接用 SQL 造满
  const maxCart = 10;
  for (let i = 0; i < maxCart; i++) {
    sql(`INSERT IGNORE INTO fm_trade.trade_cart(user_id, course_id, course_name, price) VALUES (2, ${900000 + i}, '占位课${i}', 100)`);
  }
  const addOver = await call('POST', '/ts/carts', { token: coachToken, body: { courseId: A } });
  check('★ 购物车满 10 门后再加被拒', addOver.json?.code !== 200 && (addOver.json?.msg || '').includes('10'),
    `msg=${addOver.json?.msg}`);
  sql(`DELETE FROM fm_trade.trade_cart WHERE user_id = 2`);

  // 失效课程标注：把 B 下架
  await call('POST', '/cs/courses/downShelf', { token: coachToken, body: { id: B } });
  const carts2 = await call('GET', '/ts/carts', { token });
  const cartB = (carts2.json?.data || []).find((c) => String(c.courseId) === String(B));
  check('★ 已下架的课程在购物车里被标为失效（不影响其他条目）',
    cartB?.invalid === true && cartB?.invalidReason, `invalid=${cartB?.invalid}, reason=${cartB?.invalidReason}`);
  const cartA2 = (carts2.json?.data || []).find((c) => String(c.courseId) === String(A));
  check('同一列表里 A 仍然有效（失效是逐条判断的）', cartA2?.invalid === false);
  // 把 B 重新上架（后续还要用它）
  await call('POST', '/cs/courses/upShelf', { token: coachToken, body: { id: B } });

  // 移出（幂等）
  await call('DELETE', `/ts/carts/${A}`, { token });
  const removeAgain = await call('DELETE', `/ts/carts/${A}`, { token });
  check('★ 移出购物车是幂等的（重复移出不报错）', removeAgain.json?.code === 200);
  check('移出后购物车只剩 1 条', (await call('GET', '/ts/carts', { token })).json?.data?.length === 1);

  // ==================== 4. 预下单 ====================
  section('4. 预下单（不落库，只预览）');

  const pre1 = await prePlace(token, [A, B]);
  check('预下单成功并返回 orderId', !!pre1.json?.data?.orderId, `msg=${pre1.json?.msg}`);
  check('★ 金额 = 两门课之和 49800 分（19900 + 29900）',
    pre1.json?.data?.totalAmount === 49800 && pre1.json?.data?.realAmount === 49800,
    `total=${pre1.json?.data?.totalAmount}`);
  check('预下单返回支付截止时间', !!pre1.json?.data?.payOutTime);
  check('★ 预下单不落库（DB 里还是没有订单）',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE user_id=1`) === '0');

  // ★ 直接改课程价格，验证金额回源
  setCoursePrice(A, 9900);
  const pre2 = await prePlace(token, [A, B]);
  check('★ ★ 课程表降价后，预下单金额跟着变成 39800（金额来自课程服务，不是前端传的）',
    pre2.json?.data?.totalAmount === 39800, `total=${pre2.json?.data?.totalAmount}`);
  setCoursePrice(A, 19900);

  const preFree = await prePlace(token, [F]);
  check('★ 免费课不能预下单（提示直接报名）',
    preFree.json?.code !== 200 && (preFree.json?.msg || '').includes('免费'), `msg=${preFree.json?.msg}`);

  const preNotExist = await prePlace(token, [999999999]);
  check('不存在的课程预下单被拒', preNotExist.json?.code !== 200, `msg=${preNotExist.json?.msg}`);

  // ==================== 5. 下单 ====================
  section('5. 下单（幂等 + 快照 + 延迟消息）');

  const orderId = pre1.json?.data?.orderId;
  const place1 = await call('POST', '/ts/orders', {
    token, body: { orderId, courseIds: [A, B] },
  });
  check('下单成功', place1.json?.code === 200 && place1.json?.data?.created === true,
    `msg=${place1.json?.msg}, created=${place1.json?.data?.created}`);
  check('★ 订单状态为待支付(1)，金额 49800',
    sql(`SELECT status, total_amount, real_amount FROM fm_trade.trade_order WHERE id=${orderId}`).replace(/\t/g, ' ') === '1 49800 49800',
    `row=${sql(`SELECT status, total_amount, real_amount FROM fm_trade.trade_order WHERE id=${orderId}`)}`);
  check('★ 明细 2 条，且都是"下单时的快照"',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`) === '2'
    && sql(`SELECT GROUP_CONCAT(course_name ORDER BY course_id) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`).includes(`M4-1付费课A${suffix}`),
    `names=${sql(`SELECT GROUP_CONCAT(course_name) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`)}`);
  check('★ 明细里存了有效期快照（90 天）',
    sql(`SELECT valid_duration FROM fm_trade.trade_order_detail WHERE order_id=${orderId} LIMIT 1`) === '90');
  check('明细状态与父单一致（待支付）',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_order_detail WHERE order_id=${orderId} AND status=1`) === '2');

  // ★ 快照的证明：改掉课程名，订单明细不受影响
  sql(`UPDATE fm_course.course SET name = '课程改名了' WHERE id = ${A}`);
  check('★ ★ 课程改名后，订单明细里仍是下单时的名字（快照生效）',
    sql(`SELECT course_name FROM fm_trade.trade_order_detail WHERE order_id=${orderId} AND course_id=${A}`).includes(`M4-1付费课A${suffix}`),
    `name=${sql(`SELECT course_name FROM fm_trade.trade_order_detail WHERE order_id=${orderId} AND course_id=${A}`)}`);
  sql(`UPDATE fm_course.course SET name = 'M4-1付费课A${suffix}' WHERE id = ${A}`);

  // ★ 幂等：同一个订单 id 再提交一次
  const placeAgain = await call('POST', '/ts/orders', { token, body: { orderId, courseIds: [A, B] } });
  check('★ ★ 同一 orderId 重复提交：返回同一笔订单且 created=false',
    placeAgain.json?.code === 200 && placeAgain.json?.data?.created === false,
    `created=${placeAgain.json?.data?.created}, msg=${placeAgain.json?.msg}`);
  check('★ ★ DB 里仍然只有 1 笔订单（没有重复下单）',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE id=${orderId}`) === '1');
  check('明细也仍然只有 2 条',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`) === '2');

  // 下单后购物车被清
  check('★ 下单后购物车里对应的课程被移除',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_cart WHERE user_id=1`) === '0',
    `cart=${sql(`SELECT COUNT(*) FROM fm_trade.trade_cart WHERE user_id=1`)}`);

  // 延迟队列
  check('★ 延迟队列里能查到这笔订单（ZSet member = orderId）',
    redis('ZSCORE', 'trade:order:delay', String(orderId)) !== '');

  // 重复购买拦截
  const preBought = await prePlace(token, [A]);
  check('★ 已有待支付订单时再次下单同一课程被拒',
    preBought.json?.code !== 200 && (preBought.json?.msg || '').includes('待支付'), `msg=${preBought.json?.msg}`);

  // ==================== 6. 订单查询 ====================
  section('6. 订单查询');
  const page1 = await call('GET', '/ts/orders/page?pageNo=1&pageSize=10', { token });
  check('我的订单列表返回 1 笔', (page1.json?.data?.list || []).length === 1);
  const vo = (page1.json?.data?.list || [])[0] || {};
  check('★ 服务端直接给出中文状态描述与按钮状态（前端不用映射数字）',
    vo.statusDesc === '待支付' && vo.canPay === true && vo.canCancel === true,
    `desc=${vo.statusDesc}, canPay=${vo.canPay}`);
  check('列表里带明细（课程名）',
    (vo.details || []).length === 2 && vo.details[0].courseName);
  check('★ 待支付订单给出支付截止时间', !!vo.payOutTime);

  const pageFilter = await call('GET', '/ts/orders/page?status=3&pageNo=1&pageSize=10', { token });
  check('按状态筛选生效（查已关闭 → 0 条）', (pageFilter.json?.data?.list || []).length === 0);

  const detail = await call('GET', `/ts/orders/${orderId}`, { token });
  check('订单详情可查', detail.json?.code === 200 && detail.json?.data?.id);
  const otherDetail = await call('GET', `/ts/orders/${orderId}`, { token: coachToken });
  check('★ 查别人的订单被拒（只能操作自己的）', otherDetail.json?.code !== 200, `msg=${otherDetail.json?.msg}`);

  // ==================== 7. 取消订单 ====================
  section('7. 取消订单');

  const order2 = (await prePlace(token, [C])).json?.data?.orderId;
  await call('POST', '/ts/orders', { token, body: { orderId: order2, courseIds: [C] } });
  check('为取消用例单独下了一笔订单', sql(`SELECT status FROM fm_trade.trade_order WHERE id=${order2}`) === '1');

  const cancel1 = await call('PUT', `/ts/orders/${order2}/cancel`, { token });
  check('取消订单成功', cancel1.json?.code === 200, `msg=${cancel1.json?.msg}`);
  check('★ 状态变已关闭(3)、写入关闭时间与原因',
    sql(`SELECT status, message FROM fm_trade.trade_order WHERE id=${order2}`).replace(/\t/g, ' ') === '3 用户取消'
    && sql(`SELECT close_time IS NOT NULL FROM fm_trade.trade_order WHERE id=${order2}`) === '1',
    `row=${sql(`SELECT status, message FROM fm_trade.trade_order WHERE id=${order2}`)}`);
  check('★ 取消后从延迟队列摘掉（不再被定时任务扫到）',
    redis('ZSCORE', 'trade:order:delay', String(order2)) === '');

  const cancelAgain = await call('PUT', `/ts/orders/${order2}/cancel`, { token });
  check('★ 重复取消被拒（状态已不是待支付）', cancelAgain.json?.code !== 200, `msg=${cancelAgain.json?.msg}`);

  const cancelOther = await call('PUT', `/ts/orders/${orderId}/cancel`, { token: coachToken });
  check('★ 取消别人的订单被拒', cancelOther.json?.code !== 200, `msg=${cancelOther.json?.msg}`);

  // 取消后可以重新下单同一课程（★ 这条依赖"关闭订单时同步明细状态"）
  const preAgain = await prePlace(token, [C]);
  check('★ 取消后再买同一课程可以正常预下单（不被历史订单挡住）', preAgain.json?.code === 200);

  // ==================== 8. ★ 超时取消 ====================
  section('8. ★ 超时取消（延迟队列 + 兜底扫描 + 不误杀已支付）');

  // 场景一：延迟队列里到期的订单
  redis('ZADD', 'trade:order:delay', '1', String(orderId));  // score=1（1970 年，早已到期）
  await xxlPost(cookie, '/jobinfo/trigger', { id: timeoutJob?.id, executorParam: '' });
  let timeoutStatus = '';
  for (let i = 0; i < 8; i++) {
    await sleep(1500);
    timeoutStatus = sql(`SELECT status, message FROM fm_trade.trade_order WHERE id=${orderId}`);
    if (timeoutStatus.startsWith('3')) break;
  }
  check('★ ★ 延迟队列到期的订单被自动关闭（状态 3 / 超时未支付）',
    timeoutStatus.replace(/\t/g, ' ') === '3 超时未支付', `row=${JSON.stringify(timeoutStatus)}`);
  check('★ 关闭时写入 close_time',
    sql(`SELECT close_time IS NOT NULL FROM fm_trade.trade_order WHERE id=${orderId}`) === '1');

  // 场景二：★ 已支付的订单不能被超时任务误杀
  const order3 = (await prePlace(token, [A])).json?.data?.orderId;
  await call('POST', '/ts/orders', { token, body: { orderId: order3, courseIds: [A] } });
  // 用 SQL 模拟"支付成功"（M4-2 才会真的支付，这里只验证超时任务的条件更新）
  sql(`UPDATE fm_trade.trade_order SET status = 2, pay_time = NOW() WHERE id = ${order3}`);
  sql(`UPDATE fm_trade.trade_order_detail SET status = 2 WHERE order_id = ${order3}`);
  sql(`UPDATE fm_trade.trade_order SET create_time = DATE_SUB(NOW(), INTERVAL 40 MINUTE) WHERE id = ${order3}`);
  redis('ZADD', 'trade:order:delay', '1', String(order3));

  await xxlPost(cookie, '/jobinfo/trigger', { id: timeoutJob?.id, executorParam: '' });
  await sleep(4000);
  check('★ ★ 已支付的订单不会被超时任务关闭（条件更新 WHERE status=待支付 挡住）',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${order3}`) === '2',
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${order3}`)}`);

  // 场景三：★ 兜底扫描（只改 create_time，不投延迟消息）
  const order4 = (await prePlace(token, [B])).json?.data?.orderId;
  await call('POST', '/ts/orders', { token, body: { orderId: order4, courseIds: [B] } });
  sql(`UPDATE fm_trade.trade_order SET create_time = DATE_SUB(NOW(), INTERVAL 40 MINUTE) WHERE id = ${order4}`);
  redis('ZREM', 'trade:order:delay', String(order4));   // 把延迟消息摘掉，模拟"Redis 丢了这条"
  check('前置：延迟队列里已没有这笔订单',
    redis('ZSCORE', 'trade:order:delay', String(order4)) === '');

  await xxlPost(cookie, '/jobinfo/trigger', { id: timeoutJob?.id, executorParam: '' });
  let backupStatus = '';
  for (let i = 0; i < 8; i++) {
    await sleep(1500);
    backupStatus = sql(`SELECT status, message FROM fm_trade.trade_order WHERE id=${order4}`);
    if (backupStatus.startsWith('3')) break;
  }
  check('★ ★ 兜底扫描救场：延迟消息丢了，扫库照样把它关掉',
    backupStatus.replace(/\t/g, ' ') === '3 超时未支付', `row=${JSON.stringify(backupStatus)}`);

  // ==================== 9. 状态机与条件更新 ====================
  section('9. 状态机与条件更新（并发安全的地基）');
  const closedRow = sql(`SELECT status FROM fm_trade.trade_order WHERE id=${order4}`);
  check('已关闭订单的状态不会再被改成已支付（流转表里 CLOSED → PAYED 不存在）',
    closedRow === '3');
  check('★ 当前库里订单状态分布正常',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE user_id=1 AND status NOT IN (1,2,3,5,6)`) === '0',
    `非法状态数=${sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE user_id=1 AND status NOT IN (1,2,3,5,6)`)}`);

  // ==================== 10. 鉴权 ====================
  section('10. 鉴权');
  const noToken = await call('GET', '/ts/carts');
  check('未登录访问购物车 401', noToken.status === 401, `status=${noToken.status}`);
  const noTokenOrder = await call('GET', '/ts/orders/page');
  check('未登录访问订单 401', noTokenOrder.status === 401, `status=${noTokenOrder.status}`);

  // ==================== 11. 清理 ====================
  section('11. 清理测试数据');
  if (timeoutJob?.id) {
    const restarted = await xxlPost(cookie, '/jobinfo/start', { id: timeoutJob.id });
    console.log(`     周期调度已恢复：${restarted?.code === 200 ? 'OK' : JSON.stringify(restarted)}`);
  }
  sql(`DELETE FROM fm_trade.trade_cart WHERE user_id IN (1,2)`);
  for (const cid of [A, B, C, F]) {
    sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id = ${cid}`);
  }
  // ★ 明细与订单必须**同一维度**删（先明细、后订单）—— 否则漏出孤儿明细（#C34）
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id IN (SELECT id FROM fm_trade.trade_order WHERE user_id IN (1,2))`);
  sql(`DELETE FROM fm_trade.trade_order WHERE user_id IN (1,2)`);
  redis('DEL', 'trade:order:delay');
  for (const c of [c1, c2, c3, cFree]) {
    if (c?.courseId) {
      await call('POST', '/cs/courses/downShelf', { token: coachToken, body: { id: c.courseId } }).catch(() => null);
      await call('DELETE', `/cs/courses/delete/${c.courseId}`, { token: coachToken });
    }
    if (c?.cateId) await call('DELETE', `/cs/categorys/${c.cateId}`, { token: coachToken });
  }
  // ★ 删课程必须连 ES 索引一起删：否则用户还能搜到一门已经不存在的课。
  //   （这是 2026-09-14 收尾时发现的真 bug：delete() 原本完全不发 MQ 消息）
  await sleep(2500);
  //
  // ⚠️ 这里**不能**断言"ES 文档总数为 0" —— 环境里可能有其他脚本正在用的课程文档，
  //    那个断言就把"环境干不干净"当成了"这功能对不对"，属于**依赖全局环境状态**
  //    （同类坑：m2-3 的"免费筛选"曾被别的脚本残留顶出第一页）。
  //    正确做法：只检查**本次这几门课**是否还在索引里。
  const esDocStatus = (id) => execSync(
    `curl -s --noproxy "*" -o /dev/null -w "%{http_code}" --max-time 8 "http://localhost:9201/fitmind_course/_doc/${id}"`,
    { encoding: 'utf8' }).toString().trim();
  const leftInEs = [A, B, C, F].filter((id) => esDocStatus(id) !== '404');
  check('★ ★ 删除课程后 ES 索引也删掉了（不能让用户搜到已删除的课）',
    leftInEs.length === 0,
    `仍在索引中的课程：${JSON.stringify(leftInEs)}（404 表示已删）`);

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
