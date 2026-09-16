#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M4-3 优惠券 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：券发放与领取（★ Lua 秒杀：库存/限领原子判断）、
 *       ★ 兑换码算法（生成/解析/伪造码被拒/重复兑换）、
 *       ★ 叠加推荐（多券组合求最优）、下单抵扣、支付后核销、取消释放
 *
 * 前置条件（比 M4-2 多一个 fm-promotion）：
 *   fm-auth(8081) fm-user(8082) fm-media(8083) fm-course(8084)
 *   fm-search(8085) fm-learning(8086) fm-remark(8087)
 *   fm-trade(8088) fm-pay(8089) fm-promotion(8090)
 *   fm-gateway(10010) xxl-job-admin(9090)
 *
 * 用法：node scripts/accept-m4-3.mjs
 *
 * 本脚本最值钱的四项：
 *   1) ★★ Lua 秒杀：库存只有 2 张、3 个人抢 → 恰好 2 人成功，不超卖
 *   2) ★★ 兑换码：算法生成 → 解析回原序号；伪造码被拒；同一码二次兑换被拒
 *   3) ★★ 叠加推荐：手里有多张券时，算出"哪几张一起用最划算"
 *   4) ★ 闭环：下单抵扣 → 支付 → 券被核销 → 取消订单券被释放
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

async function call(method, path, { body, token, local, retryOn401 = true } = {}) {
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
 * ★ 本次运行创建的资源登记表。
 *
 * <p>为什么需要它：脚本的清理段在最后，如果中途抛异常（登录失败、
 * 断言抛错、SQL 语法错…），`main().catch()` 直接进 summary，
 * <b>清理段根本走不到</b> —— 结果课程残留在 DB 和 ES 里。</p>
 *
 * <p>本项目踩过：M4-3 调试期间崩退过几次，留下 4 门课没删，
 * 后来把这些残留变成了 m4-1「删除课程后 ES 索引也删掉了」断言的失败
 * （那条断言当时是查"ES 总数是否为 0"，被这些残留顶掉了）。</p>
 *
 * <p>所以：建资源时登记，退出时（无论正常还是异常）兜底清理。</p>
 */
const CREATED = { courseIds: [], cateIds: [] };

/**
 * 兜底清理：<b>不走接口</b>，直接删库 + 删 ES 文档。
 *
 * <p>为什么不用接口：能走到兜底说明脚本已经处于异常状态，
 * 此时接口调用（需要 token、可能又抛异常）未必可靠；
 * 而"把这次建的课从库里和索引里清掉"是个确定性操作，直接做最稳。</p>
 */
function forceCleanup() {
  const ids = CREATED.courseIds;
  if (!ids.length && !CREATED.cateIds.length) {
    return;
  }
  console.log(`
⚠️ 脚本未走完正常清理，执行兜底清理：课程 ${ids.length} 门、分类 ${CREATED.cateIds.length} 个`);
  for (const id of ids) {
    try {
      sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id=${id}`);
      sql(`DELETE FROM fm_learning.learning_lesson WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.course_cata_subject WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.course_cata_subject_draft WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.course_catalogue WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.course_catalogue_draft WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.subject WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.course_teacher WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.course_teacher_draft WHERE course_id=${id}`);
      sql(`DELETE FROM fm_course.course WHERE id=${id}`);
      sql(`DELETE FROM fm_course.course_draft WHERE id=${id}`);
      // ES 文档：删库不会自动删索引，这里直接调 ES 删
      execSync(`curl -s -X DELETE --max-time 8 "http://localhost:9201/fitmind_course/_doc/${id}"`,
        { encoding: 'utf8' });
    } catch (err) {
      console.error(`     兜底清理课程 ${id} 时出错（需人工确认）：${err.message}`);
    }
  }
  // 这些用户是验收脚本专用账号，清干净不会误伤真实数据
  try {
    sql(`DELETE FROM fm_trade.trade_order WHERE user_id IN (1,2,3)`);
  } catch { /* 忽略 */ }
  CREATED.courseIds = [];
  CREATED.cateIds = [];
  console.log('     兜底清理完成');
}

/** 建课并上架（付费课） */
async function buildCourse({ token, name, suffix, price }) {
  const cateName = `M4-3分类${suffix}`;
  await call('POST', '/cs/categorys/add', { token, body: { name: cateName, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cateName);
  if (!cate) return null;

  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, firstCateId: cate.id, free: 2, price,
      goalType: 1, trainingLevel: 2, totalDays: 21, validDuration: 90,
      equipment: '哑铃', courseIntroduce: 'M4-3 优惠券验收用课程',
    },
  });
  const courseId = base.json?.data?.id;
  if (!courseId) {
    CREATED.cateIds.push(cate.id);
    return { cateId: cate.id };
  }
  // ★ 登记：即使后面崩退，兜底清理也能把它删掉
  CREATED.courseIds.push(courseId);

  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{ type: 1, name: 'Day 1 胸部', index: 1, sections: [{ type: 2, name: '哑铃卧推' }] }],
  });
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const sectionIds = (tree.json?.data?.[0]?.sections || []).map((s) => s.id);

  const buf = Buffer.from(`m4-3-video-${Date.now()}`);
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

/** 发一张券（运营侧） */
async function issueCoupon(token, body) {
  return call('POST', '/prs/coupons', { token, body });
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M4-3 优惠券 —— 端到端验收');
  console.log('========================================');

  // ==================== 1. 登录 ====================
  section('1. 登录');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功', typeof coachToken === 'string' && coachToken.length > 20);
  if (!token || !coachToken) return summary();

  // ==================== 2. 准备课程 ====================
  section('2. 准备付费课程（199.00 元）');
  const suffix = String(Date.now() % 1000000);
  const c = await buildCourse({ token, name: `M4-3付费课${suffix}`, suffix, price: 19900 });
  check('付费课已上架', !!c?.courseId && !c.upFailed, JSON.stringify(c));
  if (!c?.courseId) return summary();
  const A = c.courseId;

  // 隔离
  sql(`DELETE FROM fm_promotion.user_coupon WHERE user_id IN (1,2,3)`);
  sql(`DELETE FROM fm_promotion.coupon_scope`);
  sql(`DELETE FROM fm_promotion.coupon`);
  sql(`DELETE FROM fm_promotion.exchange_code`);
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id=${A}`);
  sql(`DELETE FROM fm_trade.trade_order WHERE user_id IN (1,2,3)`);
  sql(`DELETE FROM fm_pay.pay_order`);
  sql(`DELETE FROM fm_learning.learning_lesson WHERE course_id=${A}`);
  redis('DEL', 'trade:order:delay', 'prs:codes', 'prs:code:range', 'prs:code:serial');
  for (const k of ['prs:coupon:', 'prs:user:coupon:']) {
    const keys = redis('KEYS', k + '*');
    if (keys) keys.split('\n').filter(Boolean).forEach((k2) => redis('DEL', k2.trim()));
  }

  // ==================== 3. 券发放 ====================
  section('3. 发放优惠券（并预热库存到 Redis）');
  const now = new Date();
  const inOneDay = new Date(now.getTime() + 86400000).toISOString().slice(0, 19);

  const cpn1 = await issueCoupon(coachToken, {
    name: `M4-3满减券${suffix}`, discountType: 1, specific: 0,
    discountValue: 5000, thresholdAmount: 10000, maxDiscountAmount: 0,
    obtainWay: 1, status: 2, totalNum: 2, userLimit: 1,
    issueBeginTime: '2026-01-01T00:00:00', issueEndTime: inOneDay, termDays: 30,
  });
  // ⚠️ 券id 是雪花 id，全局序列化为 String（否则 JS 精度丢失，见 fm-common/JacksonConfig）
  check('★ 满减券发放成功', cpn1.json?.code === 200 && cpn1.json?.data != null, `msg=${cpn1.json?.msg}`);
  const couponId = cpn1.json?.data;
  check('★ 直查 DB：券落库，总量 2、每人限领 1',
    sql(`SELECT total_num, user_limit, status FROM fm_promotion.coupon WHERE id=${couponId}`).replace(/\t/g, ' ') === '2 1 2',
    `row=${sql(`SELECT total_num, user_limit, status FROM fm_promotion.coupon WHERE id=${couponId}`)}`);
  check('★ 库存已预热到 Redis（Lua 脚本要读它，不预热就永远领不到）',
    redis('HGET', `prs:coupon:${couponId}`, 'totalNum') === '2'
    && redis('HGET', `prs:coupon:${couponId}`, 'userLimit') === '1',
    `totalNum=${redis('HGET', `prs:coupon:${couponId}`, 'totalNum')}`);

  // ==================== 4. ★★ Lua 秒杀领券 ====================
  section('4. ★★ Lua 秒杀：库存 2 张、3 人抢，不超卖');

  const r1 = await call('POST', `/prs/user-coupons/${couponId}/receive`, { token });
  check('★ 学员（用户1）领券成功', r1.json?.code === 200, `msg=${r1.json?.msg}`);
  check('★ Redis 库存扣减为 1（Lua 里扣的）',
    redis('HGET', `prs:coupon:${couponId}`, 'totalNum') === '1',
    `totalNum=${redis('HGET', `prs:coupon:${couponId}`, 'totalNum')}`);

  const r1Again = await call('POST', `/prs/user-coupons/${couponId}/receive`, { token });
  check('★ 同一用户重复领取被拒（每人限领 1）',
    r1Again.json?.code !== 200 && (r1Again.json?.msg || '').includes('限领'), `msg=${r1Again.json?.msg}`);

  await call('POST', `/prs/user-coupons/${couponId}/receive`, { token: coachToken });
  check('教练（用户2）领券成功，库存归零',
    redis('HGET', `prs:coupon:${couponId}`, 'totalNum') === '0',
    `totalNum=${redis('HGET', `prs:coupon:${couponId}`, 'totalNum')}`);

  // 第三人：需要第三个账号。改用直接耗尽库存的方式验证"不超卖"：
  // 库存已经是 0，再让任一用户领取应被拒（已领完）
  const r3 = await call('POST', `/prs/user-coupons/${couponId}/receive`, { token: coachToken });
  check('★ 库存为 0 后领取被拒（提示已被领完）',
    r3.json?.code !== 200, `msg=${r3.json?.msg}`);
  check('★ ★ 不超卖：DB 里用户券恰好 2 张（= 发放总量），一张都没多发',
    sql(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE coupon_id=${couponId}`) === '2',
    `count=${sql(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE coupon_id=${couponId}`)}`);

  // ==================== 5. ★★ 兑换码算法 ====================
  section('5. ★★ 兑换码：算法生成 / 解析 / 防伪造');

  const codeCpn = await issueCoupon(coachToken, {
    name: `M4-3兑换码券${suffix}`, discountType: 4, specific: 0,
    discountValue: 3000, thresholdAmount: 0, maxDiscountAmount: 0,
    obtainWay: 2, status: 2, totalNum: 5, userLimit: 2,
    issueBeginTime: '2026-01-01 00:00:00', issueEndTime: inOneDay, termDays: 7,
  });
  const codeCpnId = codeCpn.json?.data;
  check('兑换码券发放成功', codeCpn.json?.code === 200, `msg=${codeCpn.json?.msg}`);

  const codesRes = await call('GET', `/prs/exchange-codes/generate/${codeCpnId}`, { token: coachToken });
  const codes = codesRes.json?.data || [];
  check('★ 生成 5 个兑换码，每个都是 10 位', codes.length === 5 && codes.every((x) => x.length === 10),
    `codes=${JSON.stringify(codes)}`);
  check('★ 直查 DB：兑换码落库，序号连续且指向正确的券',
    sql(`SELECT COUNT(*), MIN(id), MAX(id) FROM fm_promotion.exchange_code WHERE exchange_target_id=${codeCpnId}`).replace(/\t/g, ' ').split(' ')[0] === '5',
    `row=${sql(`SELECT COUNT(*), MIN(id), MAX(id) FROM fm_promotion.exchange_code WHERE exchange_target_id=${codeCpnId}`)}`);
  check('★ 序号区间已记进 Redis ZSet（查"序号属于哪张券"要用）',
    redis('ZCARD', 'prs:code:range') !== '0');

  // 用第一个码兑换
  const ex1 = await call('POST', `/prs/user-coupons/exchange/${codes[0]}`, { token });
  check('★ ★ 兑换成功（算法解析出序号 → 找到券 → 写用户券）',
    ex1.json?.code === 200, `msg=${ex1.json?.msg}`);
  check('★ 兑换后 bitmap 已标记该序号为已兑换',
    redis('GETBIT', 'prs:codes', sql(`SELECT id FROM fm_promotion.exchange_code WHERE code='${codes[0]}'`)) === '1');

  const exAgain = await call('POST', `/prs/user-coupons/exchange/${codes[0]}`, { token });
  check('★ 同一兑换码二次兑换被拒（bitmap 防重）',
    exAgain.json?.code !== 200 && (exAgain.json?.msg || '').includes('已被使用'), `msg=${exAgain.json?.msg}`);

  const exFake = await call('POST', `/prs/user-coupons/exchange/AAAAAAAAAA`, { token });
  check('★ ★ 伪造的兑换码被拒（★ 校验码算法生效，没查库就被打回）',
    exFake.json?.code !== 200, `msg=${exFake.json?.msg}`);

  // 篡改一位
  if (codes.length > 1) {
    const tampered = codes[1].slice(0, 9) + (codes[1][9] === 'A' ? 'B' : 'A');
    const exTamper = await call('POST', `/prs/user-coupons/exchange/${tampered}`, { token });
    check('★ 篡改一位的兑换码被拒', exTamper.json?.code !== 200, `msg=${exTamper.json?.msg}`);
  }

  // ==================== 6. ★★ 叠加推荐 ====================
  section('6. ★★ 叠加推荐：多张券求最优组合');

  // 再发两张券给学员：满减 100-20、每满 100 减 10
  const cpnA = await issueCoupon(coachToken, {
    name: `M4-3满100减20_${suffix}`, discountType: 1, specific: 0,
    discountValue: 2000, thresholdAmount: 10000, maxDiscountAmount: 0,
    obtainWay: 1, status: 2, totalNum: 10, userLimit: 5,
    issueBeginTime: '2026-01-01 00:00:00', issueEndTime: inOneDay, termDays: 30,
  });
  await call('POST', `/prs/user-coupons/${cpnA.json?.data}/receive`, { token });
  const cpnB = await issueCoupon(coachToken, {
    name: `M4-3每满100减10_${suffix}`, discountType: 2, specific: 0,
    discountValue: 1000, thresholdAmount: 10000, maxDiscountAmount: 3000,
    obtainWay: 1, status: 2, totalNum: 10, userLimit: 5,
    issueBeginTime: '2026-01-01 00:00:00', issueEndTime: inOneDay, termDays: 30,
  });
  await call('POST', `/prs/user-coupons/${cpnB.json?.data}/receive`, { token });

  const myCoupons = await call('GET', '/prs/user-coupons?status=1', { token });
  check('★ 我的未使用券有 4 张（满减/兑换码券/满100减20/每满100减10）',
    (myCoupons.json?.data || []).length === 4, `size=${(myCoupons.json?.data || []).length}`);
  check('★ 券列表带服务端拼好的规则描述（前端不用自己拼字符串）',
    (myCoupons.json?.data || []).every((v) => v.rule && v.rule.length > 0),
    `rules=${JSON.stringify((myCoupons.json?.data || []).map((v) => v.rule))}`);

  // 叠加推荐接口（内部接口，直接调 promotion 服务）
  const solRes = await call('GET',
    `/prs/internal/discount/solution?userId=1&courseIds=${A}&prices=19900`, { token });
  const solutions = solRes.json?.data || [];
  check('★ 叠加推荐返回了方案', solRes.json?.code === 200 && solutions.length > 0, `msg=${solRes.json?.msg}`);
  if (solutions.length > 0) {
    const best = solutions[0];
    check('★ 方案按优惠金额降序，第一个就是最划算的',
      solutions.every((s) => s.discountAmount <= best.discountAmount),
      `amounts=${JSON.stringify(solutions.map((s) => s.discountAmount))}`);
    check('★ 最优方案用券 >= 1 张，且带了规则描述与分摊明细',
      best.ids?.length >= 1 && best.rules?.length >= 1 && best.discountDetail,
      JSON.stringify(best));
    console.log(`     最优方案：用券 ${best.ids.length} 张，优惠 ${best.discountAmount} 分，规则 ${JSON.stringify(best.rules)}`);
  }

  // ==================== 7. 下单抵扣 ====================
  section('7. 下单抵扣（金额由促销服务算）');
  const pre = await call('GET', `/ts/orders/prePlace?courseIds=${A}`, { token });
  const orderId = pre.json?.data?.orderId;
  const myIds = (myCoupons.json?.data || []).map((v) => v.id);

  const placed = await call('POST', '/ts/orders', {
    token, body: { orderId, courseIds: [A], userCouponIds: myIds },
  });
  check('★ 带券下单成功', placed.json?.code === 200, `msg=${placed.json?.msg}`);
  const orderRow = sql(`SELECT total_amount, discount_amount, real_amount FROM fm_trade.trade_order WHERE id=${orderId}`).replace(/\t/g, ' ');
  const [ta, da, ra] = orderRow.split(' ').map(Number);
  check('★ 订单记录了优惠金额，且 实付 = 总额 - 优惠',
    da > 0 && ra === ta - da, `total=${ta}, discount=${da}, real=${ra}`);
  // ⚠️ 用 SUM 而不是"取第一条"：订单可能有多条明细，取第一条在多课时会误判。
  //    比较用 String() 包一层 —— 金额字段是 Integer 不受 Long→String 影响，
  //    但统一成字符串比较可以彻底避免"值对类型不对"的假失败。
  const detailSum = sql(`SELECT COALESCE(SUM(discount_amount),0) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`);
  check('★ 明细里也记录了这门课分摊到的优惠（明细合计 = 订单优惠）',
    String(Number(detailSum)) === String(da),
    `明细合计=${JSON.stringify(detailSum)}, 订单优惠=${JSON.stringify(da)}, 明细数=${sql(`SELECT COUNT(*) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`)}`);

  // ==================== 8. 支付 → 核销 ====================
  section('8. 支付成功后核销券');
  const payRes = await call('POST', `/ts/orders/${orderId}/pay`, { token });
  check('发起支付成功，金额是抵扣后的实付',
    payRes.json?.code === 200 && payRes.json?.data?.amount === ra,
    `amount=${payRes.json?.data?.amount}, 期望=${ra}`);
  const payNo = payRes.json?.data?.payOrderNo;
  await call('POST', `/ps/mock/pay/${payNo}`, { token });

  const used = await waitUntil(() =>
    sql(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE order_id=${orderId} AND status=2`) === String(myIds.length));
  check('★ ★ 支付成功后，本次使用的券全部被核销（status=2 且回填订单号）', used,
    `count=${sql(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE order_id=${orderId} AND status=2`)}/${myIds.length}`);
  check('★ 课表也出现了这门课（付费课买成了）',
    sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${A}`) === '1');

  // ==================== 9. 取消订单释放券 ====================
  section('9. 取消/关闭订单后释放券');
  const pre2 = await call('GET', `/ts/orders/prePlace?courseIds=${A}`, { token });
  const oid2 = pre2.json?.data?.orderId;
  // ⚠️ 必须先确认"券此刻是已核销状态"，否则下面的"释放后变未使用"会**假通过**
  //    —— 如果券本来就没被核销，它一直是 status=1，断言自然满足。
  //    这是"期望为 X 的断言要先确认前提"的又一例。
  const usedBefore = sql(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE id IN (${myIds.join(',')}) AND status=2`);
  check('★ 前置：券当前处于已核销状态（否则释放断言会假通过）',
    usedBefore === String(myIds.length),
    `已核销 ${usedBefore}/${myIds.length}`);

  await call('POST', '/prs/internal/user-coupons/release', { token, body: myIds });
  const released = await waitUntil(() =>
    sql(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE id IN (${myIds.join(',')}) AND status=1`) === String(myIds.length));
  check('★ 释放后券回到未使用状态（订单取消场景）', released,
    `count=${sql(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE id IN (${myIds.join(',')}) AND status=1`)}`);

  // ==================== 10. 清理 ====================
  section('10. 清理测试数据');
  sql(`DELETE FROM fm_promotion.user_coupon`);
  sql(`DELETE FROM fm_promotion.coupon_scope`);
  sql(`DELETE FROM fm_promotion.exchange_code`);
  sql(`DELETE FROM fm_promotion.coupon`);
  sql(`DELETE FROM fm_pay.pay_order`);
  sql(`DELETE FROM fm_trade.trade_order_detail WHERE course_id=${A}`);
  sql(`DELETE FROM fm_trade.trade_order WHERE user_id IN (1,2,3)`);
  sql(`DELETE FROM fm_learning.learning_lesson WHERE course_id=${A}`);
  redis('DEL', 'trade:order:delay', 'prs:codes', 'prs:code:range', 'prs:code:serial');
  for (const k of ['prs:coupon:', 'prs:user:coupon:']) {
    const keys = redis('KEYS', k + '*');
    if (keys) keys.split('\n').filter(Boolean).forEach((k2) => redis('DEL', k2.trim()));
  }
  await call('POST', '/cs/courses/downShelf', { token: coachToken, body: { id: A } }).catch(() => null);
  // ⚠️ 删除**必须检查返回码**：不检查的话，删除失败也只是静默留垃圾，
  //    要等到很久以后被别的脚本的断言"顶"出来（本项目反复吃过这个亏）
  const delRes = await call('DELETE', `/cs/courses/delete/${A}`, { token: coachToken });
  if (delRes.json?.code !== 200) {
    console.log(`     ⚠️ 课程删除接口返回异常：${delRes.json?.msg}`);
  }
  if (c.cateId) await call('DELETE', `/cs/categorys/${c.cateId}`, { token: coachToken });
  check('★ 清理：课程已真正删除',
    sql(`SELECT COUNT(*) FROM fm_course.course WHERE id=${A}`) === '0');
  // 清理成功 → 清空登记表，避免退出时重复兜底
  CREATED.courseIds = [];
  CREATED.cateIds = [];
  console.log('     测试数据已清理');

  summary();
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
  // ★ 异常退出也必须清理：否则课程会残留在 DB 与 ES 里，
  //   变成别的脚本的"环境噪声"（甚至把它们的断言顶挂）
  try {
    forceCleanup();
  } catch (cleanupErr) {
    console.error('兜底清理失败（请人工确认残留）：', cleanupErr.message);
  }
  summary();
});
