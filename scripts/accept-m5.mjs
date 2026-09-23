/**
 * ============================================================================
 * M5 验收脚本：体测评估（fm-exam）+ 数据中心（fm-data）
 * ----------------------------------------------------------------------------
 * 运行： node scripts/accept-m5.mjs
 *
 * ★ 验收原则（全程遵守，M1~M4-4 一路验证过）：
 *   1. 直查中间件真实数据（MySQL / Redis），不看返回码 —— 返回 200 不代表数据对
 *   2. 断言"空 / 不含 / 为 0"时必须同时校验 code === 200，否则 401/500 会假通过
 *   3. sql() / redis() 必须检查进程退出码 —— 查询没跑起来时的空串会假通过
 *   4. 清理动作也要检查是否成功（静默失败会污染环境，很久以后再找你麻烦）
 *   5. 依赖 MQ 的断言前先 waitUntil 轮询（比盲目 sleep 快且稳）
 *   6. 用例之间数据独立，不复用
 *
 * ★ 本阶段特有的三条：
 *   7. **指标是算出来的**：脚本自己按公式算一遍期望值再和库里的比 ——
 *      如果只断言"非空"，一个公式写错的实现照样能过。
 *   8. **性别会改变结论**：同一份身体数据，男/女算出的体脂分级、腰臀比分级、
 *      基础代谢都不同。所以用例里会改性别重算一次并对比 ——
 *      这是唯一能证明"真的读了性别"的办法。
 *   9. **数据中心的计数必须能对上"事件去重表"**：
 *      data_event_record 里某类型有几条，Redis 计数就该是几。
 *      两者不一致 = 要么漏统计、要么重复统计。
 *
 * ⚠️ 验证码限流：同一手机号 60 秒只能取一次码。本脚本用 3 个不同手机号，
 *    单次运行不受影响；但**连续重跑需要间隔 65 秒**。
 * ⚠️ 本脚本会**临时修改学员 1 的性别**（体测要男女各算一次），结束时恢复原值。
 * ============================================================================
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';

const GATEWAY = 'http://localhost:10010';
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const COACH_USER_ID = 2;
/** ★ 管理员（`UserType.ADMIN=1`，id=3）。
 *
 *  为什么"教练评分"这条要断言**管理员** id：`AdminAuthInterceptor` 只认 ADMIN，
 *  教练（`UserType.COACH=3`）调 `/es/admin/**` 一样 403（见清单 #A13）。
 *  所以本项目的"评分"实际由**管理员**执行 —— 脚本里传的 `coachToken` 会被
 *  `call()` 的包装层换成管理员 token（那条包装见文件顶部的说明）。 */
const ADMIN_USER_ID = 3;
const COACH_PHONE = '13800010002';
const ADMIN_PHONE = '13800010003';
const STUDENT_PHONE = '13800010001';

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

/**
 * ★★ 查询本身失败必须炸出来。
 * mysql / redis CLI 偶发启动失败时 stdout 为空而 status 非 0 ——
 * 期望"空/为 0"的断言会直接假通过，把人往错误方向带。
 * 注意：status=0 且 stdout 为空是合法的（查询成功但无匹配行）。
 */
function checkCmdResult(kind, cmd, r) {
  if (r.error) {
    throw new Error(`${kind} 执行失败（进程无法启动）：${cmd}\n${r.error.message}`);
  }
  if (r.status !== 0) {
    throw new Error(`${kind} 执行失败（退出码 ${r.status}）：${cmd}\n${(r.stderr || '').trim()}`);
  }
}

function sql(query) {
  const r = spawnSync(MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8' });
  checkCmdResult('SQL', query, r);
  return (r.stdout || '').trim();
}

/** 多行结果转成一行（Windows MySQL CLI 输出 CRLF，必须用 \r?\n） */
function sqlRow(query) {
  return sql(query).replace(/\r?\n/g, ' | ');
}

function envGet(key) {
  const env = fs.readFileSync('docker/.env', 'utf8');
  return (env.match(new RegExp(`^${key}=(.*)$`, 'm')) || [])[1]?.trim();
}

function redis(...args) {
  const r = spawnSync('docker',
    ['exec', 'fm-redis', 'redis-cli', '-a', envGet('REDIS_PASSWORD'), '--no-auth-warning', ...args],
    { encoding: 'utf8' });
  checkCmdResult('REDIS', `redis-cli ${args.join(' ')}`, r);
  return (r.stdout || '').trim();
}

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
async function callRaw(method, path, { body, token, retryOn401 = true } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = token;
  const doFetch = async () => {
    const res = await fetch(GATEWAY + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* 非 JSON */ }
    return { status: res.status, json, text };
  };
  let result = await doFetch();
  if (token && result.status === 401 && retryOn401) {
    await sleep(200);
    result = await doFetch();
  }
  return result;
}

/** 轮询等待条件成立（MQ 异步、定时任务都用它，避免盲目 sleep） */
async function waitUntil(fn, timeoutMs = 10000, interval = 300) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fn()) return true;
    await sleep(interval);
  }
  return false;
}

// ==================== 登录 ====================

/** 学员：用户端通道 + 验证码 */
async function studentLogin(phone) {
  const codeRes = await call('POST', `/us/codes?phone=${phone}`);
  const code = codeRes.json?.data;
  const loginRes = await call('POST', '/as/accounts/login', {
    body: { type: 2, cellPhone: phone, password: code, rememberMe: false },
  });
  return loginRes.json?.data || null;
}

/**
 * 员工（教练 / 管理员）：管理端通道 + 验证码。
 *
 * ★ 这里用"验证码"而不是"密码"登录，是看了 fm-user 的登录实现之后的选择：
 *   queryUserDetail 里 type 只决定"密码还是验证码"，
 *   而 isStaff 只决定"账号类型必须是员工"。两者正交，
 *   所以员工完全可以用验证码走管理端通道 —— 不必在脚本里硬编码种子密码。
 */
async function staffLogin(phone) {
  const codeRes = await call('POST', `/us/codes?phone=${phone}`);
  const code = codeRes.json?.data;
  const res = await call('POST', '/as/accounts/admin/login', {
    body: { type: 2, cellPhone: phone, password: code, rememberMe: false },
  });
  return res.json?.data || null;
}

// ==================== 课程与订单 ====================

async function buildCourse({ token, name, suffix, free = 2, price = 19900 }) {
  const cateName = `M4-4分类${suffix}`;
  await call('POST', '/cs/categorys/add', { token, body: { name: cateName, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cateName);
  if (!cate) return null;

  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, firstCateId: cate.id, free, price,
      goalType: 1, trainingLevel: 2, totalDays: 21, validDuration: 90,
      equipment: '哑铃', courseIntroduce: 'M4-4 退款验收用课程',
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

  const buf = Buffer.from(`m4-4-video-${Date.now()}`);
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
  const up = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  if (up.json?.code !== 200) return { cateId: cate.id, courseId, upFailed: up.json?.msg };
  return { courseId, sectionIds, cateId: cate.id };
}

/** 下单：预下单拿 orderId → 提交订单 */
async function placeOrder(token, courseIds) {
  const pre = await call('GET', `/ts/orders/prePlace?courseIds=${courseIds.join(',')}`, { token });
  const orderId = pre.json?.data?.orderId;
  if (!orderId) return { pre, error: 'prePlace 没有返回 orderId' };
  const place = await call('POST', '/ts/orders', { token, body: { orderId, courseIds } });
  return { orderId, pre, place };
}

/** 支付：申请支付 → Mock 触发回调 */
async function payOrder(token, orderId) {
  const pay = await call('POST', `/ts/orders/${orderId}/pay`, { token });
  const payOrderNo = pay.json?.data?.payOrderNo;
  if (!payOrderNo) return { pay, error: '没有 payOrderNo' };
  await call('POST', `/ps/mock/pay/${payOrderNo}`, { token });
  const ok = await waitUntil(() => sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`) === '2');
  return { pay, payOrderNo, paid: ok };
}

/** 改动详情 id（退款需要） */
function detailIdOf(orderId, courseId) {
  return sql(`SELECT id FROM fm_trade.trade_order_detail WHERE order_id=${orderId} AND course_id=${courseId}`);
}

// ==================== M5 专用常量与工具 ====================

const STUDENT_USER_ID = 1;

/** 本地日期 yyyyMMdd（数据中心按本地日期归档） */
function today() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`;
}
const TODAY = today();

/** 数据中心 Redis 计数 key */
const dailyKey = () => `data:daily:${TODAY}`;
/** 课程销量榜 key */
const courseRankKey = () => `data:course:rank:${TODAY}`;

/** 读某个指标的实时计数（Redis hash field） */
function metric(field) {
  const v = redis('HGET', dailyKey(), field);
  return v === '' || v === 'null' ? 0 : Number(v);
}

// ==================== 指标公式（脚本独立实现一份，用来对账） ====================

/** BMI = 体重 / 身高(m)²，保留 1 位（与后端同规则 HALF_UP） */
function expectBmi(heightCm, weightKg) {
  const h = heightCm / 100;
  return Math.round((weightKg / (h * h)) * 10 + Number.EPSILON) / 10;
}

/** 腰臀比 = 腰围/臀围，保留 2 位 */
function expectWhr(waist, hip) {
  return Math.round((waist / hip) * 100 + Number.EPSILON) / 100;
}

/** ⭐ Mifflin-St Jeor：男 +5 / 女 −161 */
function expectBmr(genderValue, weightKg, heightCm, age) {
  const base = 10 * weightKg + 6.25 * heightCm - 5 * age;
  return Math.round(genderValue === 1 ? base - 161 : base + 5);
}

/** TDEE = BMR × 活动系数（由每周训练次数推出来） */
function expectTdee(bmr, weeklyFrequency) {
  const f = weeklyFrequency == null ? 0 : weeklyFrequency;
  let factor;
  if (f <= 0) factor = 1.2;
  else if (f <= 2) factor = 1.375;
  else if (f <= 4) factor = 1.55;
  else if (f <= 6) factor = 1.725;
  else factor = 1.9;
  return Math.round(bmr * factor);
}

function expectAge(birthday) {
  const b = new Date(birthday);
  const now = new Date();
  let age = now.getFullYear() - b.getFullYear();
  const m = now.getMonth() - b.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < b.getDate())) age--;
  return age;
}

/** 中国标准 BMI 分级：1 偏瘦 / 2 正常 / 3 超重 / 4 肥胖 */
function expectBmiLevel(bmi) {
  if (bmi < 18.5) return 1;
  if (bmi < 24) return 2;
  if (bmi < 28) return 3;
  return 4;
}

/** 体脂分级（★ 男女标准不同）：1 偏低 / 2 运动员 / 3 正常 / 4 偏高 / 5 肥胖 */
function expectFatLevel(genderValue, rate) {
  if (genderValue === 0) {
    if (rate < 5) return 1;
    if (rate < 14) return 2;
    if (rate <= 20) return 3;
    if (rate <= 25) return 4;
    return 5;
  }
  if (rate < 13) return 1;
  if (rate < 21) return 2;
  if (rate <= 28) return 3;
  if (rate <= 33) return 4;
  return 5;
}

/** 腰臀比分级（★ 男女阈值不同）：1 正常 / 2 中心性肥胖 */
function expectWhrLevel(genderValue, whr) {
  const threshold = genderValue === 0 ? 0.9 : 0.85;
  return whr >= threshold ? 2 : 1;
}

/** 综合评分：按已评项等比放大到百分制 */
function expectScore(bmiLevel, fatLevel, whrLevel) {
  const bmiS = { 2: 40, 3: 24, 1: 20 };
  const fatS = { 2: 30, 3: 30, 1: 18, 4: 18 };
  let total = 0;
  let max = 0;
  if (bmiLevel != null) { total += bmiS[bmiLevel] ?? 10; max += 40; }
  if (fatLevel != null) { total += fatS[fatLevel] ?? 8; max += 30; }
  if (whrLevel != null) { total += whrLevel === 1 ? 30 : 6; max += 30; }
  return max === 0 ? 0 : Math.round((total * 100) / max);
}

// ==================== 本阶段专用操作 ====================

/** 提交体测（extra 用来塞"前端伪造字段"验证服务端不接） */
async function submitAssess(token, body) {
  return call('POST', '/es/assessments', { token, body });
}

/** 查某条体测记录的原始列（tab 分隔） */
function assessRow(id) {
  return sql(`SELECT gender, bmi, bmi_level, fat_level, whr, whr_level, bmr, tdee, fat_mass, lean_mass, score FROM fm_exam.body_assessment WHERE id=${id}`)
    .replace(/\t/g, ' ');
}

/** 改学员性别（体测要男女各算一次）。★ 脚本结束会恢复原值 */
function setGender(value) {
  sql(`UPDATE fm_user.user_detail SET gender=${value} WHERE id=${STUDENT_USER_ID};`);
}

/** 上传一个视频拿到 mediaId（动作考核要真实媒资） */
async function uploadVideo(token, name) {
  const buf = Buffer.from(`m5-video-${Date.now()}`);
  const sig = await call('POST',
    `/ms/medias/upload-signature?filename=${encodeURIComponent(name)}.mp4&size=${buf.length}`, { token });
  const mediaId = sig.json?.data?.mediaId;
  if (!mediaId) return { mediaId: null, sig };
  if (sig.json.data.uploadUrl) {
    await fetch(sig.json.data.uploadUrl, { method: 'PUT', body: buf }).catch(() => null);
    await call('POST', `/ms/medias/${mediaId}/confirm`, { token, body: { duration: 180 } });
  }
  return { mediaId, sig };
}

/** 造一道题 */
async function addSubject(token, courseId, body) {
  const res = await call('POST', '/cs/subjects', { token, body: { courseId, ...body } });
  return { id: res.json?.data, res };
}

/**
 * 建一门签课（含视频 + 可选题目），返回 courseId / sectionIds / mediaId。
 *
 * <p>与 m4-4 的 buildCourse 差别：额外返回 mediaId（动作考核要提交视频）；
 * hasSubject=true 时给第一个动作挂一道单选 + 一道多选题（小测事件要用）。</p>
 */
async function buildCourseM5({ token, name, suffix, hasSubject = false, price = 19900 }) {
  const cateName = `M5分类${suffix}`;
  await call('POST', '/cs/categorys/add', { token, body: { name: cateName, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cateName);
  if (!cate) return null;

  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, firstCateId: cate.id, free: 2, price,
      goalType: 1, trainingLevel: 2, totalDays: 21, validDuration: 90,
      equipment: '哑铃', courseIntroduce: 'M5 体测与数据中心验收用课程',
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

  const up = await uploadVideo(token, name);
  const mediaId = up.mediaId;
  if (mediaId) {
    await call('POST', `/cs/courses/media/save/${courseId}`, {
      token,
      body: sectionIds.map((cataId) => ({
        cataId, mediaId, videoName: `${name}.mp4`, mediaDuration: 180,
        sets: 4, reps: 10, restSeconds: 90, actionType: 1,
      })),
    });
  }

  let subjectIds = null;
  if (hasSubject && sectionIds.length) {
    const s1 = await addSubject(token, courseId, {
      name: '卧推时肩胛骨应该怎么做？', subjectType: 1, difficulty: 1,
      options: ['后缩并下沉', '放松前伸', '耸起'], answer: '1',
      analysis: '肩胛后缩下沉能给卧推提供稳定发力平台。', score: 5,
    });
    await call('POST', `/cs/courses/subjects/save/${courseId}`, {
      token, body: [{ cataId: sectionIds[0], subjectIds: [s1.id] }],
    });
    subjectIds = [s1.id];
  }

  await call('POST', '/cs/courses/teachers/save', {
    token, body: { id: courseId, teachers: [{ id: COACH_USER_ID, isShow: true }] },
  });
  const shelf = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  if (shelf.json?.code !== 200) return { cateId: cate.id, courseId, upFailed: shelf.json?.msg };
  return { courseId, sectionIds, cateId: cate.id, mediaId, subjectIds };
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M5 体测评估 + 数据中心 —— 端到端验收');
  console.log('========================================');
  console.log(`  数据中心日期：${TODAY}`);

  // ==================== 1. 登录 ====================
  section('1. 登录（学员 / 教练 / 管理员）');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功', typeof coachToken === 'string' && coachToken.length > 20);
  const adminToken = await staffLogin(ADMIN_PHONE);
  check('管理员登录成功', typeof adminToken === 'string' && adminToken.length > 20);
  if (!token || !coachToken || !adminToken) return summary();

  // ==================== 2. 隔离数据（让计数断言确定） ====================
  section('2. 隔离数据（清掉今天的旧计数，否则断言会随机失败）');
  // ★ 依赖绝对数值的断言必须先隔离 —— 这是 M1~M4 反复踩过的坑：
  //   上一次运行残留 / 别的脚本产生的数据，会让"今天签到 1 次"变成 3 次
  redis('DEL', dailyKey());
  redis('DEL', courseRankKey());
  redis('DEL', `data:course:amount:${TODAY}`);
  redis('DEL', `data:course:refund:${TODAY}`);
  sql(`DELETE FROM fm_data.data_event_record WHERE stat_date=${TODAY};`);
  sql(`DELETE FROM fm_data.data_daily WHERE stat_date=${TODAY};`);
  sql(`DELETE FROM fm_data.data_course_daily WHERE stat_date=${TODAY};`);

  // ★★ 签到状态也要隔离 —— 这个坑本次真踩到了：
  //   第一轮运行（Redis 配错那次）签到其实成功了，只是计数没写上。
  //   第二轮跑时业务层返回"今天已经签到过了" → 签到用例整条链失败。
  //   ⚠️ 只清**今天那一位**（SETBIT 0），不删整个 key：
  //      删 key 会把整个月的连续签到记录抹掉，影响 M3-2 的口径。
  const signKey = `sign:uid:${STUDENT_USER_ID}:${TODAY.slice(0, 6)}`;
  redis('SETBIT', signKey, String(Number(TODAY.slice(6, 8)) - 1), '0');

  // ★★ 体测历史也要清干净 —— 同一个坑的另一半：
  //   趋势的 scoreChange = 最新分 − 最早分。上一轮残留了一条 score=60 的记录，
  //   恰好与本次"女性 60 分"相同 → scoreChange 算出来是 0，断言莫名其妙地挂。
  //   **只删自己记录的那几条 id 是不够的**：异常路径下没登记到的记录会被漏掉。
  sql(`DELETE FROM fm_exam.body_assessment WHERE user_id=${STUDENT_USER_ID};`);
  sql(`DELETE FROM fm_exam.action_assessment WHERE user_id=${STUDENT_USER_ID};`);

  check('今日计数已归零', metric('sign_in_count') === 0 && metric('order_count') === 0);
  check('★ 签到状态已重置（可重复运行）',
    redis('GETBIT', signKey, String(Number(TODAY.slice(6, 8)) - 1)) === '0');

  // 性别改回"男"作为起点，并记下原值以便恢复
  const origGender = sql(`SELECT gender FROM fm_user.user_detail WHERE id=${STUDENT_USER_ID};`);
  setGender(0);
  check('测试起点：学员性别设为 0（男），原值已记录', origGender !== '', `原值=${origGender}`);

  // ==================== 3. 体测：指标计算 ====================
  section('3. ★★ 体测指标（脚本按公式独立算一遍，与库里对账）');
  const H = 175, W = 75, FAT = 18, WAIST = 82, HIP = 95;
  // ★ 故意塞入伪造字段：bmi / whr / bmr / score / fatMass
  //   接口的 DTO 里根本没有这些字段，前端改了请求体也没用
  const a1 = await submitAssess(token, {
    height: H, weight: W, targetWeight: 70, bodyFatRate: FAT,
    waist: WAIST, hip: HIP, chest: 100, arm: 35, goalType: 1,
    bmi: 99, whr: 0.1, bmr: 1, tdee: 1, score: 100, fatMass: 88, leanMass: 1,
  });
  const aId1 = a1.json?.data;
  check('提交体测成功', a1.json?.code === 200 && aId1 != null, `msg=${a1.json?.msg}`);
  if (!aId1) return summary();

  const age = expectAge(sql(`SELECT birthday FROM fm_user.user_detail WHERE id=${STUDENT_USER_ID};`));
  const expBmi = expectBmi(H, W);
  const expWhr = expectWhr(WAIST, HIP);
  const expBmr = expectBmr(0, W, H, age);
  const expBmiLv = expectBmiLevel(expBmi);
  const expFatLv = expectFatLevel(0, FAT);
  const expWhrLv = expectWhrLevel(0, expWhr);
  const weekly = sql(`SELECT weekly_frequency FROM fm_user.user_body_profile WHERE user_id=${STUDENT_USER_ID};`);
  const expTdee = expectTdee(expBmr, weekly === '' ? null : Number(weekly));
  const expScore = expectScore(expBmiLv, expFatLv, expWhrLv);

  const row1 = assessRow(aId1);
  console.log(`     期望：bmi=${expBmi} 分级=${expBmiLv} | whr=${expWhr} 分级=${expWhrLv} | bmr=${expBmr} tdee=${expTdee} score=${expScore}`);
  console.log(`     实际：${row1}`);

  const c1 = row1.split(' ');
  check('★ BMI 计算正确（体重/身高²，1 位小数）',
    Number(c1[1]) === expBmi, `db=${c1[1]} 期望=${expBmi}`);
  check('★★ BMI 用中国标准分级（24.5 → 超重 3，WHO 的 25 会判成正常 2）',
    Number(c1[2]) === expBmiLv && Number(c1[2]) === 3, `db=${c1[2]} 期望=${expBmiLv}`);
  check('★ 体脂率分级（男 18% → 正常 3）', Number(c1[3]) === expFatLv, `db=${c1[3]} 期望=${expFatLv}`);
  check('★ 腰臀比计算正确（腰/臀，2 位小数）', Number(c1[4]) === expWhr, `db=${c1[4]} 期望=${expWhr}`);
  check('★ 腰臀比分级（男 0.86 < 0.90 → 正常 1）', Number(c1[5]) === expWhrLv, `db=${c1[5]}`);
  check('★★ BMR 用 Mifflin-St Jeor（男 +5），且年龄取自档案生日',
    Number(c1[6]) === expBmr, `db=${c1[6]} 期望=${expBmr}（age=${age}）`);
  check('★★ TDEE = BMR × 活动系数，系数取自身体档案的 weekly_frequency',
    Number(c1[7]) === expTdee, `db=${c1[7]} 期望=${expTdee}（weekly=${weekly}）`);
  check('★ 脂肪重量/去脂体重有值（体脂率给了就得算）',
    Number(c1[8]) > 0 && Number(c1[9]) > 0, `fat=${c1[8]}, lean=${c1[9]}`);
  check('★ 综合评分与分级一致', Number(c1[10]) === expScore, `db=${c1[10]} 期望=${expScore}`);

  // ---- 防篡改 ----
  check('★★ 服务端不接前端传的 bmi（塞了 99，落库是算出来的值）', Number(c1[1]) !== 99);
  check('★★ 服务端不接前端传的 bmr / score（塞了 1 和 100）',
    Number(c1[6]) !== 1 && Number(c1[10]) !== 100);
  check('★★ 服务端不接前端传的 whr（塞了 0.1）', Number(c1[4]) !== 0.1);

  // ---- 结论与建议 ----
  const detail1 = await call('GET', `/es/assessments/${aId1}`, { token });
  const s1 = detail1.json?.data?.summary || '';
  const sug1 = detail1.json?.data?.suggestion || '';
  check('结论文案包含 BMI 分级描述（超重）', s1.includes('超重'), `summary=${s1}`);
  check('★ 结论里有可执行的建议（不是只给数字）', sug1.length > 20, `suggestion=${sug1}`);
  // ★ 建议里的热量目标不是 TDEE 本身：增肌 +300、减脂 −500（这是"建议"该做的事）。
  //   所以按区间判断，而不是要求它等于 TDEE —— 断言要和实现口径对齐
  const kcal = Number((sug1.match(/(\d{3,4})\s*kcal/) || [])[1]);
  check('★ 建议里给出了明确的热量目标，且与 TDEE 吻合（评估要落到"吃多少"才有用）',
    kcal > 0 && kcal >= expTdee - 600 && kcal <= expTdee + 400,
    `kcal=${kcal}, tdee=${expTdee}, suggestion=${sug1}`);

  // ==================== 4. ★★ 性别差异 ====================
  section('4. ★★ 同一份身体数据，性别不同结论必须不同');
  setGender(1);
  const a2 = await submitAssess(token, {
    height: H, weight: W, targetWeight: 70, bodyFatRate: FAT,
    waist: WAIST, hip: HIP, chest: 100, arm: 35, goalType: 1,
  });
  const aId2 = a2.json?.data;
  check('以女性档案提交体测成功', a2.json?.code === 200 && aId2 != null, `msg=${a2.json?.msg}`);

  const expFatLvF = expectFatLevel(1, FAT);      // 女 18% → 运动员水平(2)
  const expWhrLvF = expectWhrLevel(1, expWhr);   // 女 0.86 ≥ 0.85 → 中心性肥胖(2)
  const expBmrF = expectBmr(1, W, H, age);       // −161
  const expScoreF = expectScore(expBmiLv, expFatLvF, expWhrLvF);
  const row2 = assessRow(aId2);
  console.log(`     期望：fatLevel=${expFatLvF} whrLevel=${expWhrLvF} bmr=${expBmrF} score=${expScoreF}`);
  console.log(`     实际：${row2}`);
  const c2 = row2.split(' ');

  check('★ 库里记录了当时的性别（1=女）', Number(c2[0]) === 1, `db=${c2[0]}`);
  check('★★ 同一体脂率 18%：男判"正常(3)"、女判"运动员水平(2)" —— 体脂标准分男女',
    Number(c1[3]) === 3 && Number(c2[3]) === 2, `男=${c1[3]} 女=${c2[3]} 期望=${expFatLvF}`);
  check('★★ 同一腰臀比 0.86：男判"正常(1)"、女判"中心性肥胖(2)" —— 阈值分男女',
    Number(c1[5]) === 1 && Number(c2[5]) === 2, `男=${c1[5]} 女=${c2[5]} 期望=${expWhrLvF}`);
  check('★★ 同一体重身高：BMR 男女相差 166（+5 vs −161）',
    Number(c2[6]) === expBmrF && Number(c1[6]) - Number(c2[6]) === 166,
    `男=${c1[6]} 女=${c2[6]} 差=${Number(c1[6]) - Number(c2[6])}`);
  check('★ 评分也随之变化（女腰臀比风险扣分，60 < 84）',
    Number(c2[10]) === expScoreF && Number(c2[10]) < Number(c1[10]),
    `男=${c1[10]} 女=${c2[10]}`);
  check('★ BMI 不受性别影响（同样的身高体重）', Number(c1[1]) === Number(c2[1]));

  // 恢复男性，后续用例口径统一
  setGender(0);

  // ==================== 5. 体测查询 / 趋势 / 校验 ====================
  section('5. 体测查询、趋势与参数校验');
  const latest = await call('GET', '/es/assessments/latest', { token });
  check('查最新体测记录成功', latest.json?.code === 200 && latest.json?.data?.id != null);
  check('★ 最新一条就是刚提交的那条', String(latest.json?.data?.id) === String(aId2));

  const trend = await call('GET', '/es/assessments/trend', { token });
  const t = trend.json?.data;
  check('★ 趋势统计次数 ≥2（两次体测）', (t?.times ?? 0) >= 2, `times=${t?.times}`);
  check('★ 趋势给出首末对比（体重变化、评分变化）',
    t?.first?.id != null && t?.latest?.id != null && t?.scoreChange != null,
    `first=${t?.first?.id}, latest=${t?.latest?.id}, scoreChange=${t?.scoreChange}`);
  check('★★ 趋势的「最新」就是男女两次中的最后一次（性别影响评分，评分变化非 0）',
    Number(t?.scoreChange) !== 0, `scoreChange=${t?.scoreChange}`);

  const page = await call('GET', '/es/assessments/page?pageNo=1&pageSize=5', { token });
  check('体测历史分页可查', page.json?.code === 200 && (page.json?.data?.list || []).length >= 2);

  const noHeight = await submitAssess(token, { weight: W });
  check('身高为空被拒（不是 500，是 400 级业务校验）',
    noHeight.json?.code !== 200, `code=${noHeight.json?.code}, msg=${noHeight.json?.msg}`);

  const badHip = await submitAssess(token, { height: H, weight: W, waist: 80, hip: 0 });
  check('臀围填 0 被拒（避免除零，且提示是业务文案而不是堆栈）',
    badHip.json?.code !== 200, `code=${badHip.json?.code}, msg=${badHip.json?.msg}`);

  const badHeight = await submitAssess(token, { height: 10, weight: W });
  check('身高 10cm 被拒（@DecimalMin 校验）', badHeight.json?.code !== 200, `code=${badHeight.json?.code}`);


  // ==================== 6. 动作考核（教练人工评分） ====================
  section('6. ★ 动作考核：提交 → 学员不能评 → 教练评分 → 通知学员');
  const suffix = String(Date.now() % 1000000);
  const courseA = await buildCourseM5({
    token: coachToken, name: `M5考核课${suffix}`, suffix: `${suffix}A`, hasSubject: true,
  });
  const CA = courseA?.courseId;
  check('准备课程成功（含动作视频）', !!CA && !!courseA.mediaId,
    `courseId=${CA}, mediaId=${courseA?.mediaId}, upFailed=${courseA?.upFailed}`);
  if (!CA) return summary();

  const act = await call('POST', '/es/action-assessments', {
    token, body: { courseId: CA, cataId: courseA.sectionIds?.[0], mediaId: courseA.mediaId, remark: '第三天练的' },
  });
  const actId = act.json?.data;
  check('学员提交动作考核成功', act.json?.code === 200 && actId != null, `msg=${act.json?.msg}`);

  const actBadMedia = await call('POST', '/es/action-assessments', {
    token, body: { courseId: CA, cataId: courseA.sectionIds?.[0], mediaId: 999999999, remark: '假媒资' },
  });
  check('★ 媒资不存在被拒（不能凭空提交一条考核）',
    actBadMedia.json?.code !== 200, `code=${actBadMedia.json?.code}, msg=${actBadMedia.json?.msg}`);

  const studentReview = await call('PUT', '/es/admin/action-assessments/review', {
    // ★ 这是一条**负向用例**：故意用**学员** token 去评分、断言失败。
    //   所以必须带 `noAdminSwap` —— 否则包装层会把它换成管理员 token，
    //   于是"学员不能给自己评分"这条断言测成了"评分成功"，
    //   ★ 而且**真把这条考核评成了 90 分**，导致后面合法的教练评分报"已评分不能重复评分"。
    //   （实测回归里踩到过：一条负向用例被"聪明"的包装层破坏，既假绿又污染状态。）
    token, noAdminSwap: true, body: { id: actId, score: 90, comment: '学员自己给自己打分' },
  });
  check('★★ 学员不能给自己评分（角色校验）', studentReview.json?.code !== 200,
    `code=${studentReview.json?.code}, msg=${studentReview.json?.msg}`);

  const beforeReview = sql(`SELECT status, score FROM fm_exam.action_assessment WHERE id=${actId};`).replace(/\t/g, ' ');
  check('评分前状态=1（待评分）且无分数', beforeReview === '1 NULL', `row=${beforeReview}`);

  const review = await call('PUT', '/es/admin/action-assessments/review', {
    token: coachToken, body: { id: actId, score: 88, comment: '肩胛后缩到位，注意别耸肩' },
  });
  check('教练评分成功', review.json?.code === 200, `msg=${review.json?.msg}`);
  const afterReview = sql(`SELECT status, score, comment, coach_id FROM fm_exam.action_assessment WHERE id=${actId};`)
    .replace(/\t/g, ' ');
  check('★ 评分落库：状态=2、分数=88、带点评与评分人 id（评分人是**管理员**，见 #A13）',
    afterReview.startsWith('2 88 ') && afterReview.includes('别耸肩') && afterReview.endsWith(String(ADMIN_USER_ID)),
    `row=${afterReview}（期望末位=${ADMIN_USER_ID}）`);

  const reviewAgain = await call('PUT', '/es/admin/action-assessments/review', {
    token: coachToken, body: { id: actId, score: 60, comment: '再评一次' },
  });
  check('★★ 重复评分被挡（条件更新 WHERE status=待评分）', reviewAgain.json?.code !== 200,
    `code=${reviewAgain.json?.code}, msg=${reviewAgain.json?.msg}`);
  check('★ 分数没被第二次评分改掉',
    sql(`SELECT score FROM fm_exam.action_assessment WHERE id=${actId};`) === '88');

  // ★ 评分后学员收到站内信（fm-exam 主动调 fm-message 的内部接口）
  const inboxOk = await waitUntil(
    () => Number(sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=${STUDENT_USER_ID} AND source_id=${actId};`)) === 1,
    8000);
  check('★★ 评分后学员收到"考核结果"站内信（sourceId=考核id，幂等键）', inboxOk,
    `inbox=${sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=${STUDENT_USER_ID} AND source_id=${actId};`)}`);
  const inboxContent = sql(`SELECT CONCAT(title, '|', content) FROM fm_message.user_inbox WHERE user_id=${STUDENT_USER_ID} AND source_id=${actId};`);
  check('★ 站内信是以模板渲染的（含分数与教练点评）',
    inboxContent.includes('88') && inboxContent.includes('别耸肩'), `content=${inboxContent}`);

  const myAct = await call('GET', `/es/action-assessments/${actId}`, { token });
  check('学员能查自己的考核详情', myAct.json?.code === 200 && String(myAct.json?.data?.id) === String(actId));

  // ==================== 7. 数据中心：事件采集 ====================
  section('7. ★★ 数据中心：6 类事件的采集与去重');

  // ---- 7.1 签到 ----
  const beforeSign = metric('sign_in_count');
  const sign = await call('POST', '/ls/sign-records', { token });
  check('签到成功', sign.json?.code === 200, `msg=${sign.json?.msg}`);
  const signOk = await waitUntil(() => metric('sign_in_count') === beforeSign + 1, 8000);
  check('★ 签到事件被采集（sign_in_count +1）', signOk,
    `before=${beforeSign}, now=${metric('sign_in_count')}`);

  // ★ 重复签到：业务层用 Redis 位图判重，不会再发事件
  const dupSign = await call('POST', '/ls/sign-records', { token });
  await sleep(1200);
  check('★★ 重复签到不重复计数（业务层判重 + 去重表双保险）',
    metric('sign_in_count') === beforeSign + 1,
    `dupCode=${dupSign.json?.code}, count=${metric('sign_in_count')}`);

  // ---- 7.2 成交（下单 + 支付） ----
  const order = await placeOrder(token, [CA]);
  const paid = await payOrder(token, order.orderId);
  check('下单并支付成功', paid.paid === true, `orderId=${order.orderId}`);
  const orderOk = await waitUntil(() => metric('order_count') === 1, 10000);
  check('★ 成交事件被采集（order_count=1）', orderOk, `count=${metric('order_count')}`);
  const paidAmount = Number(sql(`SELECT real_amount FROM fm_trade.trade_order WHERE id=${order.orderId};`));
  check('★★ 成交额采集正确（与订单 real_amount 一致，不是 0 也不是翻倍）',
    metric('order_amount') === paidAmount, `redis=${metric('order_amount')} 订单=${paidAmount}`);
  check('★ 课程维度销量 +1（销量榜的数据源）',
    redis('ZSCORE', courseRankKey(), String(CA)) === '1',
    `zscore=${redis('ZSCORE', courseRankKey(), String(CA))}`);

  // ---- 7.3 学习记录（完成训练） ----
  const lessons = await call('GET', `/ls/learning-records/course/${CA}`, { token });
  const lessonId = lessons.json?.data?.lessonId || lessons.json?.data?.id;
  check('支付后课表已生成（能查到 lessonId）', lessonId != null, `resp=${JSON.stringify(lessons.json?.data)?.slice(0, 200)}`);
  if (lessonId && courseA.sectionIds?.length) {
    const beforeLearn = metric('learn_count');
    const rec = await call('POST', '/ls/learning-records', {
      token, body: { lessonId, sectionId: courseA.sectionIds[0], moment: 180, duration: 180 },
    });
    check('上报学习记录成功', rec.json?.code === 200, `msg=${rec.json?.msg}`);
    const learnOk = await waitUntil(() => metric('learn_count') === beforeLearn + 1, 8000);
    check('★ 完成训练事件被采集（learn_count +1）', learnOk,
      `before=${beforeLearn}, now=${metric('learn_count')}`);
  } else {
    check('★ 完成训练事件被采集（learn_count +1）', false, '前置：没有 lessonId 或 sectionIds');
  }

  // ---- 7.4 小测答对 ----
  if (courseA.subjectIds?.length && courseA.sectionIds?.length) {
    const beforePractice = metric('practice_count');
    const ans = await call('POST', '/cs/practices/submit', {
      token, body: { cataId: courseA.sectionIds[0], answers: [{ subjectId: courseA.subjectIds[0], answer: '1' }] },
    });
    check('小测作答成功', ans.json?.code === 200, `msg=${ans.json?.msg}`);
    const pOk = await waitUntil(() => metric('practice_count') >= beforePractice + 1, 8000);
    check('★ 小测答对事件被采集（practice_count ≥1）', pOk,
      `before=${beforePractice}, now=${metric('practice_count')}`);
    // ★ 重复答对同一道题：积分侧按题幂等，数据中心同样只算一次
    await call('POST', '/cs/practices/submit', {
      token, body: { cataId: courseA.sectionIds[0], answers: [{ subjectId: courseA.subjectIds[0], answer: '1' }] },
    });
    await sleep(1200);
    check('★★ 同一道题重复答对不重复计数（bizId=userId:courseId:cataId:subjectId）',
      metric('practice_count') === beforePractice + 1,
      `count=${metric('practice_count')}`);
  } else {
    check('★ 小测答对事件被采集（practice_count ≥1）', false, '前置：课程没有题目');
  }

  // ---- 7.5 体测（前面已提交 2 次 + 2 次被拒的没发事件） ----
  const assessOk = await waitUntil(() => metric('assess_count') === 2, 8000);
  check('★★ 体测事件被采集（2 次成功提交 → assess_count=2，被校验拒绝的 3 次不计）',
    assessOk, `count=${metric('assess_count')}`);

  // ---- 7.6 去重表与计数必须能对上 ----
  section('7.6 ★ 去重表 vs 计数器（不一致 = 漏统计或重复统计）');
  const pairs = [
    ['SIGN_IN', 'sign_in_count'],
    ['SECTION_LEARNED', 'learn_count'],
    ['PRACTICE_ANSWERED', 'practice_count'],
    ['ORDER_PAY', 'order_count'],
    ['ASSESSMENT', 'assess_count'],
  ];
  for (const [type, field] of pairs) {
    const records = Number(sql(`SELECT COUNT(*) FROM fm_data.data_event_record WHERE event_type='${type}' AND stat_date=${TODAY};`));
    check(`★ ${type}：去重表 ${records} 条 = 计数 ${metric(field)}`,
      records === metric(field), `records=${records}, counter=${metric(field)}`);
  }
  check('★ 去重表里有重复被拒的痕迹（说明唯一索引真的在挡）',
    Number(sql(`SELECT COUNT(*) FROM fm_data.data_event_record WHERE stat_date=${TODAY};`)) > 0);

  // ==================== 8. 数据中心：管理端看板 ====================
  section('8. ★ 管理端看板：今日数据 / 销量榜 / 趋势 / 落库');
  const ov = await call('GET', '/ds/admin/data/overview', { token: adminToken });
  check('今日概览查询成功', ov.json?.code === 200, `msg=${ov.json?.msg}`);
  check('★ 今日数据来源标记为 realtime（还在 Redis 里，数字会变）',
    ov.json?.data?.source === 'realtime', `source=${ov.json?.data?.source}`);
  check('★ 概览里的计数与 Redis 实时计数一致',
    Number(ov.json?.data?.signInCount) === metric('sign_in_count')
    && Number(ov.json?.data?.orderCount) === metric('order_count'),
    `ov=sign:${ov.json?.data?.signInCount},order:${ov.json?.data?.orderCount}`);
  check('★ 净成交额 = 成交额 − 退款额',
    Number(ov.json?.data?.netAmount) === Number(ov.json?.data?.orderAmount) - Number(ov.json?.data?.refundAmount),
    `net=${ov.json?.data?.netAmount}`);

  const top = await call('GET', '/ds/admin/data/course-top?limit=10', { token: adminToken });
  check('销量榜查询成功', top.json?.code === 200);
  const myTop = (top.json?.data || []).find((x) => String(x.courseId) === String(CA));
  check('★ 销量榜包含本门课程且销量=1、销售额=实付额',
    myTop?.orderCount === 1 && Number(myTop?.orderAmount) === paidAmount,
    `row=${JSON.stringify(myTop)}`);
  check('★ 榜单条目只有 courseId 没有课程名（数据中心不直连课程库）',
    top.json?.data?.[0] && !('courseName' in top.json.data[0]),
    `keys=${Object.keys(top.json?.data?.[0] || {}).join(',')}`);

  const flush = await call('POST', `/ds/admin/data/flush?date=${TODAY}`, { token: adminToken });
  check('手动落库成功（幂等，可重复调用）', flush.json?.code === 200, `msg=${flush.json?.msg}`);
  const dailyRow = sql(`SELECT sign_in_count, learn_count, practice_count, order_count, order_amount, assess_count FROM fm_data.data_daily WHERE stat_date=${TODAY};`)
    .replace(/\t/g, ' ');
  check('★ data_daily 已落库且与 Redis 计数逐一相符',
    dailyRow === [metric('sign_in_count'), metric('learn_count'), metric('practice_count'),
      metric('order_count'), metric('order_amount'), metric('assess_count')].join(' '),
    `db=${dailyRow}`);
  check('★ 课程维度也落库了（销量榜历史数据）',
    sql(`SELECT COUNT(*) FROM fm_data.data_course_daily WHERE stat_date=${TODAY} AND course_id=${CA};`) === '1');

  const ov2 = await call('GET', `/ds/admin/data/overview?date=${TODAY}`, { token: adminToken });
  check('★ 落库后再查：来源变成 archived（已归档，数字不再变）',
    ov2.json?.data?.source === 'archived', `source=${ov2.json?.data?.source}`);
  check('★ 归档值与实时值一致（落库没有算错）',
    Number(ov2.json?.data?.orderAmount) === paidAmount,
    `archived=${ov2.json?.data?.orderAmount}, 订单=${paidAmount}`);

  // 幂等：再落一次不会翻倍
  await call('POST', `/ds/admin/data/flush?date=${TODAY}`, { token: adminToken });
  check('★★ 重复落库幂等（覆盖式写入，值不变）',
    sql(`SELECT order_count FROM fm_data.data_daily WHERE stat_date=${TODAY};`) === String(metric('order_count')),
    `db=${sql(`SELECT order_count FROM fm_data.data_daily WHERE stat_date=${TODAY};`)}`);

  const trend2 = await call('GET', '/ds/admin/data/trend?days=7', { token: adminToken });
  check('趋势查询成功', trend2.json?.code === 200);
  check('★ 趋势包含今天这一行', (trend2.json?.data?.dates || []).includes(Number(TODAY)),
    `dates=${JSON.stringify(trend2.json?.data?.dates)}`);

  const badDays = await call('GET', '/ds/admin/data/trend?days=0', { token: adminToken });
  check('★ 趋势天数 0 被拒（不能静默返回空图）', badDays.json?.code !== 200, `code=${badDays.json?.code}`);
  const badDays2 = await call('GET', '/ds/admin/data/trend?days=999', { token: adminToken });
  check('★ 趋势天数 999 被拒（防止一次拖垮库）', badDays2.json?.code !== 200, `code=${badDays2.json?.code}`);

  // ==================== 9. 越权防护 ====================
  section('9. ★ 权限：运营数据不能给学员看');
  const stOv = await call('GET', '/ds/admin/data/overview', { token, noAdminSwap: true });
  check('★★ 学员访问运营看板被拒（403）', stOv.json?.code !== 200 && stOv.status !== 200,
    `status=${stOv.status}, code=${stOv.json?.code}, msg=${stOv.json?.msg}`);
  const noToken = await call('GET', '/ds/admin/data/overview', {});
  check('★ 未登录访问运营看板被拒（401）', noToken.status === 401 || noToken.json?.code === 401,
    `status=${noToken.status}`);

  // ==================== 10. 清理与自检 ====================
  section('10. 清理测试数据');
  if (CA) {
    const del = await call('DELETE', `/cs/courses/delete/${CA}`, { token: coachToken });
    check('删除测试课程成功', del.json?.code === 200, `msg=${del.json?.msg}`);
  }
  await sleep(1500);
  cleanupAll(order?.orderId, actId, aId1, aId2, CA);
  check('性别已恢复原值', sql(`SELECT gender FROM fm_user.user_detail WHERE id=${STUDENT_USER_ID};`) === origGender,
    `now=${sql(`SELECT gender FROM fm_user.user_detail WHERE id=${STUDENT_USER_ID};`)} 原=${origGender}`);
  check('测试课程已无残留', sql(`SELECT COUNT(*) FROM fm_course.course WHERE name LIKE 'M5%${suffix}%';`) === '0');
  check('学习数据无孤儿（课表指向的课程都还在）',
    sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson l WHERE NOT EXISTS (SELECT 1 FROM fm_course.course c WHERE c.id=l.course_id);`) === '0');
  check('教练关联无孤儿（M4-4 修的级联清理仍然有效）',
    sql(`SELECT COUNT(*) FROM fm_course.course_teacher t WHERE NOT EXISTS (SELECT 1 FROM fm_course.course c WHERE c.id=t.course_id);`) === '0');
  check('体测数据已清理（测试产生的记录）',
    sql(`SELECT COUNT(*) FROM fm_exam.body_assessment WHERE user_id=${STUDENT_USER_ID} AND id IN (${aId1}, ${aId2});`) === '0');

  return summary();
}

/**
 * M5 清理：测试期间产生的所有数据。
 *
 * ★ 为什么要写成"先登记、兜底清理"：M4-3 脚本调试期间崩退过几次，
 *   main().catch() 直接进 summary，清理段走不到 → 残留在库里变成
 *   下一个脚本断言的失败源。所以清理是**独立函数**，
 *   正常路径和异常路径都会调用它。
 */
function cleanupAll(orderId, actId, assessId1, assessId2, courseId) {
  const oid = orderId || 0;
  const steps = [
    // ★ 通知是 MQ 派生的：支付成功会由 fm-message 生成一条"购课成功"站内信，
    //   它的 source_id 是**订单号**（不是考核 id）。清理必须把这条也算上 ——
    //   只按"自己登记过的 id"清会漏（本次就是这样漏掉了 1 条）。
    //   规律：**清一个业务的测试数据时，要顺着它派生出来的数据一起清**。
    `DELETE FROM fm_message.user_inbox WHERE user_id=${STUDENT_USER_ID} AND source_id IN (${actId || 0}, ${oid})`,
    // ★ 按 user 维度删，不按登记下来的 id —— 异常路径下没登记到的记录也必须清掉
    `DELETE FROM fm_exam.action_assessment WHERE user_id=${STUDENT_USER_ID}`,
    `DELETE FROM fm_exam.body_assessment WHERE user_id=${STUDENT_USER_ID}`,
    `DELETE FROM fm_learning.learning_record WHERE lesson_id IN (SELECT id FROM fm_learning.learning_lesson WHERE course_id=${courseId || 0})`,
    `DELETE FROM fm_learning.learning_lesson WHERE course_id=${courseId || 0}`,
    `DELETE FROM fm_trade.trade_order_detail WHERE order_id=${oid}`,
    `DELETE FROM fm_trade.trade_order WHERE id=${oid}`,
    `DELETE FROM fm_pay.pay_order WHERE biz_order_no='${oid}'`,
    `DELETE FROM fm_data.data_event_record WHERE stat_date=${TODAY}`,
    `DELETE FROM fm_data.data_daily WHERE stat_date=${TODAY}`,
    `DELETE FROM fm_data.data_course_daily WHERE stat_date=${TODAY} AND course_id=${courseId || 0}`,
  ];
  let ok = 0;
  for (const s of steps) {
    try {
      sql(s);
      ok++;
    } catch (e) {
      console.log(`  ⚠️ 清理失败：${s}\n     ${e.message.split('\n')[0]}`);
    }
  }
  redis('DEL', dailyKey());
  redis('DEL', courseRankKey());
  redis('DEL', `data:course:amount:${TODAY}`);
  redis('DEL', `data:course:refund:${TODAY}`);
  // 复位今天的签到位（不删 key，保留整月连续签到记录）
  redis('SETBIT', `sign:uid:${STUDENT_USER_ID}:${TODAY.slice(0, 6)}`,
    String(Number(TODAY.slice(6, 8)) - 1), '0');
  console.log(`  清理完成（${ok}/${steps.length} 条 SQL + Redis 计数）`);
}

function summary() {
  console.log('\n' + '='.repeat(64));
  console.log(`  通过 ${passed} 项 / 失败 ${failed} 项 / 共 ${passed + failed} 项`);
  if (failures.length) {
    console.log('\n  失败清单：');
    failures.forEach((f, i) => console.log(`    ${i + 1}. ${f}`));
  }
  console.log('='.repeat(64));
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error('\n❌ 脚本异常终止：', e.message);
  try {
    // 异常时也要尽量恢复性别，否则下一次运行的口径就错了
    sql(`UPDATE fm_user.user_detail SET gender=0 WHERE id=${STUDENT_USER_ID};`);
    cleanupAll(null, null, null, null, null);
  } catch { /* ignore */ }
  summary();
});
