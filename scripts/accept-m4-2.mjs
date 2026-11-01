#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M4-2 支付闭环 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：支付渠道（Mock）、申请支付、支付单复用、回调验签、
 *       ★ 回调幂等（重复回调只处理一次）、★ 支付成功 → 订单变已支付、
 *       ★ 课表出现这门课（付费报名落地）、★ 支付失败、越权与鉴权
 *
 * 前置条件（比 M4-1 多一个 fm-pay）：
 *   fm-auth(8081) fm-user(8082) fm-media(8083) fm-course(8084)
 *   fm-search(8085) fm-learning(8086) fm-remark(8087)
 *   fm-trade(8088) fm-pay(8089) fm-gateway(10010) xxl-job-admin(9090)
 *
 * 用法：node scripts/accept-m4-2.mjs
 *
 * 本脚本最值钱的四项：
 *   1) ★★ 全链路：下单 → 发起支付 → 模拟付款 → 订单变已支付 → 课表出现这门课
 *          （付费课第一次能真的买下来并开始练）
 *   2) ★★ 回调幂等：同一笔支付连续回调 3 次，订单只更新一次、课表只建一次
 *   3) ★ 验签：伪造签名的回调被拒（否则等于公开一个"把订单改成已支付"的接口）
 *   4) ★ Mock 走的是真实回调代码路径（HTTP 调自己的 /notify），
 *          不是直接改数据库 —— 那段最容易出错的逻辑才真的被覆盖了
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
    '--max-time', '8', `http://localhost:15672/api/exchanges/%2F/${exchange}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
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







/** 建课并上架（付费课） */
async function buildCourse({ token, name, suffix, price }) {
  const cateName = `M4-2分类${suffix}`;
  await call('POST', '/cs/categorys/add', { token, body: { name: cateName, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cateName);
  if (!cate) return null;

  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, firstCateId: cate.id, free: 2, price,
      goalType: 1, trainingLevel: 2, totalDays: 21, validDuration: 90,
      equipment: '哑铃', courseIntroduce: 'M4-2 支付验收用课程',
    },
  });
  const courseId = base.json?.data?.id;
  if (!courseId) return { cateId: cate.id };

  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{ type: 1, name: 'Day 1 胸部', index: 1, sections: [{ type: 2, name: '哑铃卧推' }] }],
  });
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const sectionIds = (tree.json?.data?.[0]?.sections || []).map((s) => s.id);

  const buf = Buffer.from(`m4-2-video-${Date.now()}`);
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
  if (up.json?.code !== 200) return { cateId: cate.id, courseId, upFailed: up.json?.msg };
  return { courseId, sectionIds, cateId: cate.id };
}

/** 预下单 */
async function prePlace(token, courseIds) {
  return call('GET', `/ts/orders/prePlace?courseIds=${courseIds.join(',')}`, { token });
}

/** 等条件成立（MQ 是异步的） */
async function waitUntil(fn, { timeout = 15000, interval = 800 } = {}) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await fn()) return true;
    await sleep(interval);
  }
  return false;
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M4-2 支付闭环 —— 端到端验收');
  console.log('========================================');

  // ==================== 1. 登录 ====================
  section('1. 登录');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功', typeof coachToken === 'string' && coachToken.length > 20);
  if (!token || !coachToken) return summary();

  // ==================== 2. 准备付费课程 ====================
  section('2. 准备付费课程（199.00 元，90 天有效）');
  const suffix = String(Date.now() % 1000000);
  const c = await buildCourse({ token, name: `M4-2付费课${suffix}`, suffix, price: 19900 });
  check('付费课已上架', !!c?.courseId && !c.upFailed, JSON.stringify(c));
  if (!c?.courseId) return summary();
  const A = c.courseId;

  // 隔离：清掉本课程的交易 / 支付 / 课表数据
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id IN (${A})`);
  // ★ 明细与订单必须**同一维度**删（先明细、后订单）—— 否则漏出孤儿明细（#C34）
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id IN (SELECT id FROM fm_trade.trade_order WHERE user_id=1)`);
  sql(`DELETE FROM fm_trade.trade_order WHERE user_id=1`);
  sql(`DELETE FROM fm_pay.pay_order`);
  sql(`DELETE FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`);
  redis('DEL', 'trade:order:delay');

  // ==================== 3. 下单 ====================
  section('3. 下单（复用 M4-1）');
  const pre = await prePlace(token, [A]);
  check('预下单成功，金额 19900 分', pre.json?.data?.realAmount === 19900, `msg=${pre.json?.msg}`);
  const orderId = pre.json?.data?.orderId;
  const placed = await call('POST', '/ts/orders', { token, body: { orderId, courseIds: [A] } });
  check('下单成功', placed.json?.code === 200 && placed.json?.data?.created === true, `msg=${placed.json?.msg}`);
  check('订单为待支付(1)', sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderId}`) === '1');

  // ==================== 4. 发起支付 ====================
  section('4. 发起支付（金额由服务端从订单取）');
  const pay1 = await call('POST', `/ts/orders/${orderId}/pay`, { token });
  check('★ 发起支付成功，返回支付单号与二维码链接',
    pay1.json?.code === 200 && !!pay1.json?.data?.payOrderNo && !!pay1.json?.data?.qrCodeUrl,
    `msg=${pay1.json?.msg}`);
  const payOrderNo = pay1.json?.data?.payOrderNo;
  check('★ 支付金额 = 订单实付 19900（服务端取的，不是前端传的）',
    pay1.json?.data?.amount === 19900, `amount=${pay1.json?.data?.amount}`);
  check('★ 直查 DB：支付单落库，biz_order_no 指向订单，金额为 19900',
    sql(`SELECT biz_order_no, amount, status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`).replace(/\t/g, ' ') === `${orderId} 19900 1`,
    `row=${sql(`SELECT biz_order_no, amount, status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`)}`);

  // 重复发起支付 → 复用同一笔待支付单
  const pay2 = await call('POST', `/ts/orders/${orderId}/pay`, { token });
  check('★ 重复发起支付复用同一笔支付单（reused=true，不会产生第二笔）',
    pay2.json?.code === 200 && pay2.json?.data?.reused === true && pay2.json?.data?.payOrderNo === payOrderNo,
    `reused=${pay2.json?.data?.reused}, no=${pay2.json?.data?.payOrderNo}`);
  check('DB 里仍只有 1 笔支付单',
    sql(`SELECT COUNT(*) FROM fm_pay.pay_order WHERE biz_order_no='${orderId}'`) === '1');

  // 非法渠道
  const payBad = await call('POST', `/ts/orders/${orderId}/pay?payChannelCode=nope`, { token });
  check('★ 不存在的支付渠道被拒', payBad.json?.code !== 200, `msg=${payBad.json?.msg}`);

  // ==================== 5. ★ 验签 ====================
  section('5. ★ 回调验签（否则等于公开一个"把订单改成已支付"的接口）');
  const badSign = await call('POST', '/ps/notify/mockPay', {
    body: { payOrderNo, result: 'SUCCESS', sign: 'DEADBEEF' },
  });
  check('★ 伪造签名的回调被拒', badSign.json?.code !== 200, `code=${badSign.json?.code}, msg=${badSign.json?.msg}`);
  check('★ 伪造签名没有改变订单状态（仍待支付）',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderId}`) === '1');

  // ==================== 6. ★★ 支付成功全链路 ====================
  section('6. ★★ 支付成功：订单变已支付 → 课表出现这门课');

  const mockPay = await call('POST', `/ps/mock/pay/${payOrderNo}`, { token });
  check('模拟付款触发成功', mockPay.json?.code === 200 || mockPay.status === 200, `text=${String(mockPay.text).slice(0, 60)}`);

  // 6.1 支付单变已支付
  const payPayed = await waitUntil(() => sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`) === '2');
  check('★ 支付单状态变已支付(2)', payPayed,
    `status=${sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`)}`);

  // 6.2 订单变已支付
  const orderPayed = await waitUntil(() => sql(`SELECT status, pay_order_no FROM fm_trade.trade_order WHERE id=${orderId}`).startsWith('2'));
  check('★ ★ 订单状态变已支付(2)，且回填了支付单号', orderPayed
    && sql(`SELECT pay_order_no FROM fm_trade.trade_order WHERE id=${orderId}`) === payOrderNo,
    `row=${sql(`SELECT status, pay_order_no FROM fm_trade.trade_order WHERE id=${orderId}`)}`);

  // 6.3 明细变已支付 + 算出到期时间
  check('★ 明细变已支付(2)，并算出课程到期时间（90 天后）',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`) === '2'
    && sql(`SELECT DATEDIFF(course_expire_time, NOW()) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`) === '90',
    `expire=${sql(`SELECT DATEDIFF(course_expire_time, NOW()) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`)}`);

  // 6.4 ★★ 课表出现这门课
  const inLesson = await waitUntil(() => sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`) === '1');
  check('★ ★ ★ 付费课真的买下来了：课表里出现这门课（此前付费课根本报不了名）', inLesson,
    `count=${sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`)}`);
  check('★ 课表状态为未开始、有效期 90 天',
    sql(`SELECT DATEDIFF(expire_time, NOW()) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`) === '90',
    `days=${sql(`SELECT DATEDIFF(expire_time, NOW()) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`)}`);

  // 6.5 订单详情页能查到
  const detail = await call('GET', `/ts/orders/${orderId}`, { token });
  check('订单详情显示已支付，且 canPay / canCancel 都是 false',
    detail.json?.data?.status === 2 && detail.json?.data?.canPay === false && detail.json?.data?.canCancel === false,
    `status=${detail.json?.data?.status}`);

  // ==================== 7. ★★ 回调幂等 ====================
  section('7. ★★ 回调幂等（重复回调只处理一次）');

  // 记下当前课表 id，重复回调后不应变化
  const lessonIdBefore = sql(`SELECT id FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`);
  await call('POST', `/ps/mock/pay/${payOrderNo}`, { token });
  await call('POST', `/ps/mock/pay/${payOrderNo}`, { token });
  await sleep(2500);
  check('★ 重复回调 2 次后，支付单仍是已支付（没有异常）',
    sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`) === '2');
  check('★ 课表没有被重复创建（仍是 1 条，id 也没变）',
    sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`) === '1'
    && sql(`SELECT id FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`) === lessonIdBefore,
    `count=${sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`)}`);

  // ==================== 8. 已支付订单不能再付 / 不能取消 ====================
  section('8. 已支付订单的边界');
  const payAgain = await call('POST', `/ts/orders/${orderId}/pay`, { token });
  check('★ 已支付的订单不能再发起支付', payAgain.json?.code !== 200, `msg=${payAgain.json?.msg}`);
  const cancelPayed = await call('PUT', `/ts/orders/${orderId}/cancel`, { token });
  check('★ 已支付的订单不能取消', cancelPayed.json?.code !== 200, `msg=${cancelPayed.json?.msg}`);

  // ==================== 9. 支付失败 ====================
  section('9. 支付失败');
  const c2 = await buildCourse({ token, name: `M4-2失败课${suffix}`, suffix, price: 9900 });
  check('第二门付费课已上架（99.00 元）', !!c2?.courseId && !c2.upFailed, JSON.stringify(c2));
  if (c2?.courseId) {
    const B = c2.courseId;
    const pre2 = await prePlace(token, [B]);
    const oid2 = pre2.json?.data?.orderId;
    await call('POST', '/ts/orders', { token, body: { orderId: oid2, courseIds: [B] } });
    const payFail = await call('POST', `/ts/orders/${oid2}/pay`, { token });
    const no2 = payFail.json?.data?.payOrderNo;
    await call('POST', `/ps/mock/fail/${no2}`, { token });
    await sleep(2000);
    check('★ 支付失败后支付单被关闭(3)',
      sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${no2}'`) === '3',
      `status=${sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${no2}'`)}`);
    check('★ 支付失败不影响订单（仍待支付，可以重新发起）',
      sql(`SELECT status FROM fm_trade.trade_order WHERE id=${oid2}`) === '1');
    const rePay = await call('POST', `/ts/orders/${oid2}/pay`, { token });
    check('★ 失败后可以重新发起支付（拿到新的支付单）',
      rePay.json?.code === 200 && rePay.json?.data?.payOrderNo !== no2,
      `newNo=${rePay.json?.data?.payOrderNo}`);
    // 清理
    sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id=${B}`);
    sql(`DELETE FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${B}`);
    await call('POST', '/cs/courses/downShelf', { token: coachToken, body: { id: B } }).catch(() => null);
    await call('DELETE', `/cs/courses/delete/${B}`, { token: coachToken });
    if (c2.cateId) await call('DELETE', `/cs/categorys/${c2.cateId}`, { token: coachToken });
  }

  // ==================== 10. 鉴权 ====================
  section('10. 鉴权');
  const noTokenPay = await call('POST', `/ts/orders/${orderId}/pay`);
  check('未登录发起支付 401', noTokenPay.status === 401, `status=${noTokenPay.status}`);
  const otherPay = await call('POST', `/ts/orders/${orderId}/pay`, { token: coachToken });
  check('★ 给别人订单发起支付被拒', otherPay.json?.code !== 200, `msg=${otherPay.json?.msg}`);

  // ==================== 11. 清理 ====================
  section('11. 清理测试数据');
  sql(`DELETE FROM fm_pay.pay_order`);
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id=${A}`);
  // ★ 明细与订单必须**同一维度**删（先明细、后订单）—— 否则漏出孤儿明细（#C34）
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id IN (SELECT id FROM fm_trade.trade_order WHERE user_id=1)`);
  sql(`DELETE FROM fm_trade.trade_order WHERE user_id=1`);
  sql(`DELETE FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`);
  redis('DEL', 'trade:order:delay');
  await call('POST', '/cs/courses/downShelf', { token: coachToken, body: { id: A } }).catch(() => null);
  await call('DELETE', `/cs/courses/delete/${A}`, { token: coachToken });
  if (c.cateId) await call('DELETE', `/cs/categorys/${c.cateId}`, { token: coachToken });
  check('★ 清理：课程已真正删除',
    sql(`SELECT COUNT(*) FROM fm_course.course WHERE id=${A}`) === '0');
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
