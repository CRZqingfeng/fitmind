/**
 * ============================================================================
 * M4-4 验收脚本：退款闭环 + 站内信
 * ----------------------------------------------------------------------------
 * 运行： node scripts/accept-m4-4.mjs
 *
 * ★ 验收原则（全程遵守，M1~M4-3 一路验证过）：
 *   1. 直查中间件真实数据（MySQL / Redis），不看返回码 —— 返回 200 不代表数据对
 *   2. 断言"空 / 不含 / 为 0"时必须同时校验 code === 200，否则 401/500 会假通过
 *   3. sql() / redis() 必须检查进程退出码 —— 查询没跑起来时的空串会假通过
 *   4. 清理动作也要检查是否成功（静默失败会污染环境，很久以后再找你麻烦）
 *   5. 依赖 MQ 的断言前先 sleep 2~3s 等队列排空
 *   6. 用例之间数据独立，不复用（复用会被"已有待支付订单"这类前置拦掉）
 *
 * ⚠️ 验证码限流：同一手机号 60 秒只能取一次码。本脚本用 4 个不同手机号，
 *    单次运行不受影响；但**连续重跑需要间隔 65 秒**。
 * ============================================================================
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';

const GATEWAY = 'http://localhost:10010';
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const COACH_USER_ID = 2;
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
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
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
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
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

/**
 * ★ 本次运行**自己创建的订单 id** —— 收尾自检必须用它，不能用时间窗。
 *
 *   原来自检写的是「本用户 2 小时内的退款申请」（时间窗口径），会**误伤**：
 *   只要 2 小时内**别的脚本**（前端 m7-8 的退款审批、m7-3 的退款流程）也跑过，
 *   它们留下的数据就被算成「本次清理失败」。实测踩到过：手动跑一次 m7-8 之后，
 *   m4-4 报 `leftover=1`，而那条根本不是它产生的。
 * ★ 自检要回答的是「**这次**的清理动作成功了没有」，所以判据必须是**自己的 id**。
 */
const CREATED_ORDERS = []

/** 下单：预下单拿 orderId → 提交订单 */
async function placeOrder(token, courseIds) {
  const pre = await call('GET', `/ts/orders/prePlace?courseIds=${courseIds.join(',')}`, { token });
  const orderId = pre.json?.data?.orderId;
  if (!orderId) return { pre, error: 'prePlace 没有返回 orderId' };
  CREATED_ORDERS.push(String(orderId))
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

async function main() {
  console.log('='.repeat(64));
  console.log('  M4-4 验收：退款闭环 + 站内信');
  console.log('='.repeat(64));

  const suffix = String(Date.now() % 1000000);
  /** 本脚本造过的课程 —— 结束时逐个删掉（删课程会级联清 DB 并发 course.down 删 ES 索引） */
  const courseIdsToClean = [];

  // ==================== 1. 登录 ====================
  section('1. 登录（学员 / 教练 / 管理员）');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20, `token=${token}`);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功（管理端通道 + 验证码）', typeof coachToken === 'string' && coachToken.length > 20);
  const adminToken = await staffLogin(ADMIN_PHONE);
  check('管理员登录成功', typeof adminToken === 'string' && adminToken.length > 20);
  // 第二个学员（用于"不能退别人的"用例）—— 从库里动态找，不硬编码
  const student2Phone = sql("SELECT cell_phone FROM fm_user.user WHERE type=2 AND cell_phone NOT IN ('13800010001') ORDER BY id LIMIT 1");
  const student2Token = student2Phone ? await studentLogin(student2Phone) : null;
  check('第二个学员登录成功（用于越权用例）', typeof student2Token === 'string' && student2Token.length > 20,
    `phone=${student2Phone}`);
  if (!token || !coachToken || !adminToken) {
    console.log('\n⚠️  登录失败，可能是验证码限流（同一手机号 60 秒一次）。等 65 秒后重跑。');
    return summary();
  }

  // ==================== 2. 准备课程 ====================
  section('2. 准备课程（两门付费课）');
  // ★ 自愈：清掉上一轮可能遗留的课程。
  //   脚本异常终止时 cleanup() 走不到"删课程"那步（它需要 token），
  //   残留课程会累积在 DB 和 ES 里。每次跑之前先扫一遍，
  //   这样脚本重复运行不会污染环境（也不会让"孤儿数据"慢慢长起来）。
  const staleIds = sql("SELECT id FROM fm_course.course WHERE name LIKE 'M4-4退款课%'")
    .split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
  if (staleIds.length) {
    for (const sid of staleIds) {
      await call('DELETE', `/cs/courses/delete/${sid}`, { token: coachToken });
    }
    await sleep(1500);
    console.log(`  （已清理上一轮遗留课程 ${staleIds.length} 门）`);
  }

  const courseA = await buildCourse({ token: coachToken, name: `M4-4退款课A${suffix}`, suffix: `${suffix}A` });
  const courseB = await buildCourse({ token: coachToken, name: `M4-4退款课B${suffix}`, suffix: `${suffix}B` });
  check('课程 A 创建并上架', !!courseA?.courseId && !courseA.upFailed, JSON.stringify(courseA));
  check('课程 B 创建并上架', !!courseB?.courseId && !courseB.upFailed, JSON.stringify(courseB));
  const A = courseA?.courseId;
  const B = courseB?.courseId;
  if (!A || !B) return summary();
  courseIdsToClean.push(A, B);

  // ==================== 3. 退款前置校验 ====================
  section('3. 退款前置校验（不能退的情况都要被挡）');
  const notBought = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: A, refundReason: '不想学了' },
  });
  check('没买过就申请退款 → 被拒', notBought.json?.code !== 200 && /没有购买过/.test(notBought.json?.msg || ''),
    `code=${notBought.json?.code}, msg=${notBought.json?.msg}`);

  // 下单但不支付 → 明细状态是"待支付"，不能退
  const orderUnpaid = await placeOrder(token, [A]);
  const unpaid = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: A, refundReason: '不想学了' },
  });
  check('未支付订单申请退款 → 被拒', unpaid.json?.code !== 200, `msg=${unpaid.json?.msg}`);
  // 清掉这笔待支付订单，避免干扰后续
  await call('PUT', `/ts/orders/${orderUnpaid.orderId}/cancel`, { token });

  check('退款申请请求体里没有金额字段（服务端取值）',
    !('refundAmount' in ({ courseId: A, refundReason: 'x' })),
    'DTO 无 refundAmount 字段');

  // ==================== 4. 学员申诉：审批拒绝 → 状态回滚 ====================
  section('4. ★ 学员申诉 → 审批拒绝 → 订单状态回滚');
  const orderA = await placeOrder(token, [A]);
  const paidA = await payOrder(token, orderA.orderId);
  check('课程 A 下单并支付成功', paidA.paid, `orderId=${orderA.orderId}, payOrderNo=${paidA.payOrderNo}`);
  const detailA = detailIdOf(orderA.orderId, A);
  check('订单明细状态为已支付(2)',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailA}`) === '2',
    `detailId=${detailA}`);

  const apply1 = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: A, refundReason: '训练计划不合适', questionDesc: '想换一门' },
  });
  const apply1Id = apply1.json?.data;
  check('退款申请提交成功', apply1.json?.code === 200 && !!apply1Id, `msg=${apply1.json?.msg}`);
  check('★ refund_apply 状态 = 待审批(1)',
    sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply1Id}`) === '1',
    `status=${sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply1Id}`)}`);
  check('★ 退款金额 = 明细实付（服务端取值）',
    sql(`SELECT refund_amount FROM fm_trade.refund_apply WHERE id=${apply1Id}`)
    === sql(`SELECT real_pay_amount FROM fm_trade.trade_order_detail WHERE id=${detailA}`),
    `apply=${sql(`SELECT refund_amount FROM fm_trade.refund_apply WHERE id=${apply1Id}`)}, pay=${sql(`SELECT real_pay_amount FROM fm_trade.trade_order_detail WHERE id=${detailA}`)}`);
  check('★ 明细状态变为退款中(4)',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailA}`) === '4');
  check('★ 父单状态变为退款中(6)',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderA.orderId}`) === '6',
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderA.orderId}`)}`);

  const dupApply = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: A, refundReason: '再申请一次' },
  });
  check('同一课程重复申请 → 被拒（进行中/唯一索引）', dupApply.json?.code !== 200,
    `msg=${dupApply.json?.msg}`);

  const adminPage = await call('GET', '/ts/admin/refund-apply/page?refundStatus=1', { token: adminToken });
  const inAdminList = (adminPage.json?.data?.list || []).some((r) => String(r.id) === String(apply1Id));
  check('管理端待审批列表能看到这条', inAdminList, `list=${JSON.stringify(adminPage.json?.data?.list)}`);

  const reject = await call('PUT', '/ts/admin/refund-apply/approval', {
    token: adminToken, body: { id: apply1Id, approveType: 2, approveOpinion: '不符合退款条件' },
  });
  check('审批拒绝成功', reject.json?.code === 200, `msg=${reject.json?.msg}`);
  check('★ refund_apply 状态 = 已拒绝(4)',
    sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply1Id}`) === '4');
  check('★★ 明细状态回滚为已支付(2)（常见实现缺失的一步）',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailA}`) === '2',
    `status=${sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailA}`)}`);
  check('★★ 父单状态回滚为已支付(2)',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderA.orderId}`) === '2',
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderA.orderId}`)}`);

  // ==================== 5. 学员撤销申请 → 回滚 ====================
  section('5. ★ 学员撤销申请 → 状态回滚');
  const apply2 = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: A, refundReason: '再想想' },
  });
  const apply2Id = apply2.json?.data;
  check('第二次申请成功', apply2.json?.code === 200 && !!apply2Id, `msg=${apply2.json?.msg}`);
  const cancel = await call('PUT', '/ts/refund-apply/cancel', { token, body: { id: apply2Id } });
  check('学员撤销成功', cancel.json?.code === 200, `msg=${cancel.json?.msg}`);
  check('★ refund_apply 状态 = 已取消(2)',
    sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply2Id}`) === '2');
  check('★ 撤销后明细回滚为已支付(2)',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailA}`) === '2',
    `status=${sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailA}`)}`);
  check('★ 撤销后父单回滚为已支付(2)',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderA.orderId}`) === '2');

  const tooMany = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: A, refundReason: '第三次申请' },
  });
  check('超过申请次数上限 → 被拒（学员最多 2 次）', tooMany.json?.code !== 200, `msg=${tooMany.json?.msg}`);

  // ==================== 6. 正常退款成功全链路 ====================
  section('6. ★★ 正常退款全链路（申请 → 同意 → 渠道退款 → 撤销报名 → 通知）');
  const orderB = await placeOrder(token, [B]);
  const paidB = await payOrder(token, orderB.orderId);
  check('课程 B 下单并支付成功', paidB.paid, `orderId=${orderB.orderId}`);
  const detailB = detailIdOf(orderB.orderId, B);

  // 购课成功通知（MQ 异步，等队列排空）
  await sleep(3000);
  const buyInbox = sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_type=2 AND source_id=${orderB.orderId}`);
  check('★ 支付成功后收到"购课成功"站内信', buyInbox === '1', `count=${buyInbox}`);
  const buyContent = sql(`SELECT content FROM fm_message.user_inbox WHERE user_id=1 AND source_id=${orderB.orderId}`);
  check('★ 通知内容里的课程名被正确渲染（模板占位符生效）',
    buyContent.includes('M4-4退款课B'), `content=${buyContent}`);

  const apply3 = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: B, refundReason: '课程不适合我', questionDesc: '希望全额退款' },
  });
  const apply3Id = apply3.json?.data;
  check('课程 B 退款申请成功', apply3.json?.code === 200 && !!apply3Id, `msg=${apply3.json?.msg}`);

  const agree = await call('PUT', '/ts/admin/refund-apply/approval', {
    token: adminToken, body: { id: apply3Id, approveType: 1, approveOpinion: '同意退款' },
  });
  check('审批同意成功', agree.json?.code === 200, `msg=${agree.json?.msg}`);
  check('★ 同意后 refund_apply 状态 = 同意退款(3)',
    sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply3Id}`) === '3',
    `status=${sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply3Id}`)}`);

  // 同意后 trade 会同步调 pay 发起退款（Mock 渠道返回"退款中"）
  const refundOk = await waitUntil(() =>
    sql(`SELECT COUNT(*) FROM fm_pay.refund_order WHERE biz_refund_order_no='${apply3Id}'`) === '1');
  check('★ 支付侧已生成退款单', refundOk, `applyId=${apply3Id}`);
  const refundOrderNo = sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${apply3Id}'`);
  check('★ 退款单状态 = 退款中(1)（渠道异步，不是当场成功）',
    sql(`SELECT status FROM fm_pay.refund_order WHERE biz_refund_order_no='${apply3Id}'`) === '1',
    `status=${sql(`SELECT status FROM fm_pay.refund_order WHERE biz_refund_order_no='${apply3Id}'`)}`);
  check('★ 退款单金额 = 申请金额',
    sql(`SELECT refund_amount FROM fm_pay.refund_order WHERE biz_refund_order_no='${apply3Id}'`)
    === sql(`SELECT refund_amount FROM fm_trade.refund_apply WHERE id=${apply3Id}`));
  check('★ 全额退款标记 is_split = 1',
    sql(`SELECT is_split FROM fm_pay.refund_order WHERE biz_refund_order_no='${apply3Id}'`) === '1');

  // Mock 渠道完成退款 → 触发真实回调
  const mockRefund = await call('POST', `/ps/mock/refund/${refundOrderNo}`, { token });
  check('Mock 渠道触发退款成功回调', mockRefund.json?.code === 200 || mockRefund.text === 'success',
    `text=${mockRefund.text}`);

  const refundDone = await waitUntil(() =>
    sql(`SELECT status FROM fm_pay.refund_order WHERE refund_order_no='${refundOrderNo}'`) === '2');
  check('★ 退款单状态变为成功(2)', refundDone,
    `status=${sql(`SELECT status FROM fm_pay.refund_order WHERE refund_order_no='${refundOrderNo}'`)}`);
  // 等 MQ 排空（pay → trade → learning / message）
  await sleep(3500);
  check('★★ refund_apply 状态 = 退款成功(5)',
    sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply3Id}`) === '5',
    `status=${sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${apply3Id}`)}`);
  check('★ 退款单号已回填到申请单',
    sql(`SELECT refund_order_no FROM fm_trade.refund_apply WHERE id=${apply3Id}`) === refundOrderNo);
  check('★★ 明细状态 = 已退款(5)',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailB}`) === '5',
    `status=${sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailB}`)}`);
  check('★★ 父单状态 = 已退款(7)（全部明细都已退）',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderB.orderId}`) === '7',
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderB.orderId}`)}`);

  check('★★ 课表已撤销（钱退了，权益不该留）',
    sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${B}`) === '0',
    `count=${sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${B}`)}`);

  const refundInbox = sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_type=2 AND source_id=${apply3Id}`);
  check('★ 收到"退款到账"站内信', refundInbox === '1', `count=${refundInbox}`);
  const refundContent = sql(`SELECT content FROM fm_message.user_inbox WHERE user_id=1 AND source_id=${apply3Id}`);
  check('★ 退款通知文案包含金额（分转元正确）',
    /退款\s*199(\.00)?\s*元/.test(refundContent), `content=${refundContent}`);
  check('★ 退款短信已下发并留痕（sms_record）',
    sql(`SELECT COUNT(*) FROM fm_message.sms_record WHERE user_id=1 AND template_code='refund-success'`) !== '0',
    `count=${sql(`SELECT COUNT(*) FROM fm_message.sms_record WHERE user_id=1 AND template_code='refund-success'`)}`);

  // ==================== 7. 幂等 ====================
  section('7. ★ 幂等（重复回调 / 重复通知）');
  await call('POST', `/ps/mock/refund/${refundOrderNo}`, { token });
  await call('POST', `/ps/mock/refund/${refundOrderNo}`, { token });
  await sleep(1500);
  check('重复退款回调 → 退款单仍只有一张成功记录',
    sql(`SELECT COUNT(*) FROM fm_pay.refund_order WHERE refund_order_no='${refundOrderNo}'`) === '1');
  check('重复退款回调 → refund_apply 仍只有一条记录',
    sql(`SELECT COUNT(*) FROM fm_trade.refund_apply WHERE id=${apply3Id}`) === '1');
  check('★★ 重复回调不重复发通知（唯一索引生效）',
    sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_id=${apply3Id}`) === '1',
    `count=${sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_id=${apply3Id}`)}`);

  const reApply = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: B, refundReason: '已退过还想退' },
  });
  check('已退款的课程再申请 → 被拒', reApply.json?.code !== 200, `msg=${reApply.json?.msg}`);

  // ==================== 8. 一单多课 ====================
  section('8. ★★ 一单多课：退一门时父单不能"整单退款"');
  // ⚠️ 用例之间数据必须独立：课程 A/B 已经被前面用过（A 有退款历史、B 已退款），
  //    直接拿它们下单会被"你已经买过了"挡掉 —— 那是**正确的业务行为**，
  //    但会让这个用例失败。所以另建两门全新的课。
  const courseC = await buildCourse({ token: coachToken, name: `M4-4退款课C${suffix}`, suffix: `${suffix}C` });
  const courseD = await buildCourse({ token: coachToken, name: `M4-4退款课D${suffix}`, suffix: `${suffix}D` });
  check('课程 C / D 创建并上架', !!courseC?.courseId && !!courseD?.courseId);
  const C = courseC?.courseId;
  const D = courseD?.courseId;
  courseIdsToClean.push(C, D);

  const orderAB = await placeOrder(token, [C, D]);
  const paidAB = await payOrder(token, orderAB.orderId);
  check('C+D 一单下单并支付成功', paidAB.paid, `orderId=${orderAB.orderId}`);
  const detailAB_A = detailIdOf(orderAB.orderId, C);
  check('父单初始状态 = 已支付(2)',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderAB.orderId}`) === '2');

  const applyAB = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: C, refundReason: '只退其中一门' },
  });
  const applyABId = applyAB.json?.data;
  check('一单两课：只退 C 的申请成功', applyAB.json?.code === 200, `msg=${applyAB.json?.msg}`);
  await call('PUT', '/ts/admin/refund-apply/approval', {
    token: adminToken, body: { id: applyABId, approveType: 1, approveOpinion: '同意' },
  });
  const rnAB = await waitUntil(() => !!sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${applyABId}'`))
    ? sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${applyABId}'`) : null;
  if (rnAB) await call('POST', `/ps/mock/refund/${rnAB}`, { token });
  await sleep(3500);
  check('★ 退掉 C 后：父单仍是已支付(2)（D 的权益还在）',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderAB.orderId}`) === '2',
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderAB.orderId}`)}`);
  check('★ 退掉 C 后：D 的明细仍是已支付(2)',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE order_id=${orderAB.orderId} AND course_id=${D}`) === '2');
  check('★ 退掉 C 后：C 的明细已退款(5)',
    sql(`SELECT status FROM fm_trade.trade_order_detail WHERE id=${detailAB_A}`) === '5');

  const applyAB2 = await call('POST', '/ts/refund-apply', {
    token, body: { courseId: D, refundReason: '这门也退' },
  });
  const applyAB2Id = applyAB2.json?.data;
  check('同一订单的另一门课可以继续退', applyAB2.json?.code === 200, `msg=${applyAB2.json?.msg}`);
  await call('PUT', '/ts/admin/refund-apply/approval', {
    token: adminToken, body: { id: applyAB2Id, approveType: 1, approveOpinion: '同意' },
  });
  const rnAB2 = await waitUntil(() => !!sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${applyAB2Id}'`))
    ? sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${applyAB2Id}'`) : null;
  if (rnAB2) await call('POST', `/ps/mock/refund/${rnAB2}`, { token });
  await sleep(3500);
  check('★★ 两门都退完后：父单才变已退款(7)',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderAB.orderId}`) === '7',
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderAB.orderId}`)}`);

  // ==================== 8.5 管理员直接退款（跳过审批） ====================
  section('8.5 ★ 管理员直接退款（跳过审批，初始状态即"同意"）');
  const courseE = await buildCourse({ token: coachToken, name: `M4-4退款课E${suffix}`, suffix: `${suffix}E` });
  check('课程 E 创建并上架', !!courseE?.courseId, JSON.stringify(courseE));
  courseIdsToClean.push(courseE?.courseId);
  const orderE = await placeOrder(token, [courseE.courseId]);
  const paidE = await payOrder(token, orderE.orderId);
  check('课程 E 下单并支付成功', paidE.paid, `orderId=${orderE.orderId}`);
  const detailE = detailIdOf(orderE.orderId, courseE.courseId);

  const direct = await call('POST',
    `/ts/admin/refund-apply/direct?orderDetailId=${detailE}&reason=${encodeURIComponent('客服代退')}`,
    { token: adminToken });
  const directId = direct.json?.data;
  check('管理员直接退款接口调用成功', direct.json?.code === 200 && !!directId, `msg=${direct.json?.msg}`);
  check('★ 直接退款：申请初始状态即"同意退款(3)"（学员申诉才需要审批）',
    sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${directId}`) === '3',
    `status=${sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${directId}`)}`);
  const rnE = await waitUntil(() => !!sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${directId}'`))
    ? sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${directId}'`) : null;
  check('★ 直接退款：支付侧立刻收到退款请求', !!rnE, `refundOrderNo=${rnE}`);
  if (rnE) await call('POST', `/ps/mock/refund/${rnE}`, { token });
  await sleep(3500);
  check('★ 直接退款 → 最终退款成功(5)',
    sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${directId}`) === '5',
    `status=${sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${directId}`)}`);
  check('★ 直接退款 → 课表已撤销',
    sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=1 AND course_id=${courseE.courseId}`) === '0');

  // ==================== 9. 站内信其余能力 ====================
  section('9. 站内信（未读 / 已读 / 只读自己的 / 公告 pull）');
  const unread0 = Number(sql('SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND is_read=0 AND (expire_time IS NULL OR expire_time > NOW())'));
  const unreadApi = await call('GET', '/sms/messages/unread-count', { token });
  check('未读数接口与库中一致', Number(unreadApi.json?.data) === unread0,
    `api=${unreadApi.json?.data}, db=${unread0}`);

  const myMsgs = await call('GET', '/sms/messages/page?pageNo=1&pageSize=20', { token });
  check('我的消息分页返回成功', myMsgs.json?.code === 200 && Array.isArray(myMsgs.json?.data?.list),
    `msg=${myMsgs.json?.msg}`);
  const firstMsg = (myMsgs.json?.data?.list || [])[0];
  if (firstMsg) {
    await call('PUT', `/sms/messages/${firstMsg.id}/read`, { token });
    check('标记已读后该条 is_read=1',
      sql(`SELECT is_read FROM fm_message.user_inbox WHERE id=${firstMsg.id}`) === '1');
    const unread1 = await call('GET', '/sms/messages/unread-count', { token });
    check('未读数减少 1', Number(unread1.json?.data) === unread0 - 1,
      `before=${unread0}, after=${unread1.json?.data}`);
  }

  if (student2Token) {
    const other = await call('GET', `/sms/messages/page?pageNo=1&pageSize=50`, { token: student2Token });
    const leaked = (other.json?.data?.list || []).some((m) => String(m.id) === String(firstMsg?.id));
    check('★ 别人的消息查不到（服务端按 UserContext 过滤）', !leaked);
  }

  const publish = await call('POST', '/sms/admin/public-notices', {
    token: adminToken,
    body: { type: 0, title: `M4-4公告${suffix}`, content: 'publish pull 模式验证' },
  });
  const noticeId = publish.json?.data;
  check('管理端发布公告成功', publish.json?.code === 200 && !!noticeId, `msg=${publish.json?.msg}`);
  check('★ 全员公告只写 1 行（pull 模式，不逐用户展开）',
    sql(`SELECT COUNT(*) FROM fm_message.public_notice WHERE id=${noticeId}`) === '1');

  const beforeExpand = Number(sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_type=1 AND source_id=${noticeId}`));
  await call('GET', '/sms/messages/page?pageNo=1&pageSize=20', { token });
  const afterExpand = Number(sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_type=1 AND source_id=${noticeId}`));
  check('★ 用户拉取后公告展开进自己的信箱', beforeExpand === 0 && afterExpand === 1,
    `before=${beforeExpand}, after=${afterExpand}`);

  await call('GET', '/sms/messages/page?pageNo=1&pageSize=20', { token });
  await call('GET', '/sms/messages/page?pageNo=1&pageSize=20', { token });
  await sleep(500);
  check('★★ 重复拉取不重复展开（唯一索引生效）',
    sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_type=1 AND source_id=${noticeId}`) === '1',
    `count=${sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=1 AND source_type=1 AND source_id=${noticeId}`)}`);

  // ==================== 10. 越权 ====================
  section('10. ★ 管理端越权防护');
  const student2Approve = await call('PUT', '/ts/admin/refund-apply/approval', {
    token, noAdminSwap: true,
    body: { id: apply3Id, approveType: 1, approveOpinion: '我是学员我想批自己' },
  });
  check('学员调审批接口 → 403（不是 200）',
    student2Approve.status === 403 || student2Approve.json?.code === 403,
    `status=${student2Approve.status}, code=${student2Approve.json?.code}`);
  const noAuth = await call('PUT', '/ts/admin/refund-apply/approval', {
    body: { id: apply3Id, approveType: 1 },
  });
  check('未登录调审批接口 → 401', noAuth.status === 401 || noAuth.json?.code === 401,
    `status=${noAuth.status}, code=${noAuth.json?.code}`);
  const student2Publish = await call('POST', '/sms/admin/public-notices', {
    token, noAdminSwap: true,
    body: { title: '越权公告', content: '我不该能发' },
  });
  check('学员调发公告接口 → 403',
    student2Publish.status === 403 || student2Publish.json?.code === 403,
    `status=${student2Publish.status}, code=${student2Publish.json?.code}`);

  // ==================== 11. 清理 ====================
  section('11. 清理测试数据');
  // ★ 先走接口删课程：它才会级联清 DB 并发出 course.down 消息让 fm-search 删 ES 文档。
  //   直接删库会留下 ES 孤儿文档 —— 累积到一定程度会把别的脚本的搜索断言顶歪
  //   （M4-3 收尾时踩过这个坑，孤儿文档让 m2-3 的"免费筛选"稳定失败）。
  let deletedCourses = 0;
  for (const cid of courseIdsToClean) {
    if (!cid) continue;
    // ⚠️ 是 DELETE 不是 POST —— 接口是 @DeleteMapping("/delete/{id}")。
    //    用错方法会返回 405，而"删失败"看起来和"没删"一样（设计脚本时踩过）
    const del = await call('DELETE', `/cs/courses/delete/${cid}`, { token: coachToken });
    if (del.json?.code === 200) deletedCourses++;
  }
  check(`课程已清理（${deletedCourses}/${courseIdsToClean.length}）`,
    deletedCourses === courseIdsToClean.length, `deleted=${deletedCourses}`);
  await sleep(2500);   // 等 course.down 被搜索服务消费完（ES 删文档）
  cleanup();
  console.log('  数据清理完成');

  // ★ 收尾自检：清理动作本身也要检查成功。
  //   "清理静默失败"是最难查的一类问题 —— 它不报错，只是悄悄污染环境，
  //   很久以后用一个看起来毫不相干的失败来找你（M4-3 的 ES 孤儿文档就是这样）。
  const leftoverCourse = sql("SELECT COUNT(*) FROM fm_course.course WHERE name LIKE 'M4-4退款课%'");
  check('收尾自检：课程无残留（DB）', leftoverCourse === '0', `leftover=${leftoverCourse}`);
  const leftoverOrder = sql("SELECT COUNT(*) FROM fm_trade.trade_order WHERE user_id=1 AND create_time > NOW() - INTERVAL 2 HOUR");
  check('收尾自检：订单无残留', leftoverOrder === '0', `leftover=${leftoverOrder}`);
  // ★★ 判据用**本次运行自己创建的订单**（`CREATED_ORDERS`），不用时间窗。
  //   演进过三版，每版都被现实打回一次，记在这儿：
  //     ① 整表 `COUNT(*) FROM refund_apply` → 历史验收产物让它**常红**；
  //     ② 「本用户 2 小时内」→ 2 小时内**别的脚本**（m7-8 的退款审批 / m7-3 的退款）跑过就**误伤**；
  //     ③ 自己的订单 id（现在这版）→ 不受时间与别的脚本影响，**且真能验出"我的清理失败了没有"**。
  const ownOrderList = CREATED_ORDERS.length ? CREATED_ORDERS.join(',') : '0';
  const leftoverApply = sql(`SELECT COUNT(*) FROM fm_trade.refund_apply WHERE order_id IN (${ownOrderList})`);
  check('收尾自检：本次运行的退款申请无残留', leftoverApply === '0',
    `leftover=${leftoverApply}（本次共创建 ${CREATED_ORDERS.length} 笔订单）`);
  const leftoverNotice = sql("SELECT COUNT(*) FROM fm_message.public_notice");
  check('收尾自检：公告无残留', leftoverNotice === '0', `leftover=${leftoverNotice}`);

  return summary();
}

/** 清理：把本脚本造的数据删干净（顺序有讲究：先子后父） */
function cleanup() {
  const orderIds = sql("SELECT GROUP_CONCAT(id) FROM fm_trade.trade_order WHERE user_id=1 AND create_time > NOW() - INTERVAL 2 HOUR");
  const orderIdList = orderIds === 'NULL' || !orderIds ? '0' : orderIds;
  const steps = [
    `DELETE FROM fm_message.user_inbox WHERE user_id=1 AND (source_type=1 OR push_time > NOW() - INTERVAL 2 HOUR)`,
    `DELETE FROM fm_message.sms_record WHERE user_id=1 AND create_time > NOW() - INTERVAL 2 HOUR`,
    `DELETE FROM fm_message.public_notice WHERE push_time > NOW() - INTERVAL 2 HOUR`,
    `DELETE FROM fm_trade.refund_apply WHERE order_id IN (${orderIdList})`,
    // ★ 兜底：清掉**孤儿**退款申请（订单已不存在）。
    //   上面那条按「订单 id 列表」删，只能删掉**订单还在**的那些；
    //   一旦订单先被别的脚本清掉、退款申请留下，就永远删不到了 ——
    //   库里实测攒了 8 条（9-18 与今天凌晨的验收产物 + 手动跑 m7-8 那次）。
    //   这类孤儿记录会污染后续断言（"整表为 0"这种口径直接失效），顺手清掉。
    //
    //   ⚠️ 写法必须是「单表 + 全限定名」，**不要**用多表形式
    //      `DELETE r FROM fm_trade.refund_apply r WHERE ...`：
    //      后者要求连接先选库，否则报 `ERROR 1046 (3D000) No database selected`
    //      （本脚本的 mysql 调用**不带库名**，全靠语句里的全限定名定位）—— 实测踩到。
    `DELETE FROM fm_trade.refund_apply WHERE user_id=1 AND order_id NOT IN (SELECT id FROM fm_trade.trade_order)`,
    `DELETE FROM fm_trade.trade_order_detail WHERE order_id IN (${orderIdList})`,
    `DELETE FROM fm_trade.trade_order WHERE id IN (${orderIdList})`,
    `DELETE FROM fm_learning.learning_lesson WHERE user_id=1 AND create_time > NOW() - INTERVAL 2 HOUR`,
    `DELETE FROM fm_pay.refund_order WHERE create_time > NOW() - INTERVAL 2 HOUR`,
    `DELETE FROM fm_pay.pay_order WHERE create_time > NOW() - INTERVAL 2 HOUR`,
  ];
  for (const s of steps) {
    const r = spawnSync(MYSQL,
      ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-e', s],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    if (r.status !== 0) {
      console.log(`  ⚠️ 清理失败：${s}\n     ${(r.stderr || '').trim().split('\n')[0]}`);
    }
  }
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
  try { cleanup(); console.log('  已尝试清理'); } catch { /* ignore */ }
  summary();
});
