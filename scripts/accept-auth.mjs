#!/usr/bin/env node
/**
 * ============================================================
 * FitMind 管理端鉴权（fm-auth-resource-sdk 统一提供）验收脚本
 * ------------------------------------------------------------
 * 前置条件：fm-trade(8088)、fm-learning(8086)、fm-message(8091)、
 *          fm-data(8093)、fm-aigc(8094)、fm-gateway(10010) 已启动。
 *
 * 用法：node scripts/accept-auth.mjs
 *
 * ★ 这个脚本的存在理由（M6-7）：
 *   「管理端鉴权」原本在 5 个服务里各有一份 AdminAuthInterceptor 副本，
 *   每份都要靠"记得写 .order(2)"才正确 —— 而它已经被踩过一次
 *   （order 不写时默认是 0，与 SDK 的 UserInfoInterceptor 撞号，
 *     症状是"管理员带着有效 token 访问管理端接口却报未登录"）。
 *   收敛进 SDK 之后，**"5 个服务的行为一致"本身就是一个需要被守住的契约** ——
 *   所以把它写成断言：任何一个服务将来自己另写一套，这个脚本会立刻发现。
 *
 *   ⚠️ 三条"期望"的语义要分清，它们对应三件不同的事：
 *     · 无 token  → 401（连"你是谁"都不知道）—— 由 LoginAuthInterceptor 或
 *                    AdminAuthInterceptor 的 userId==null 分支负责
 *     · 学员/教练 → 403（知道你是谁，但你不是管理员）—— 由 AdminAuthInterceptor 负责
 *     · 管理员    → 非 401/403（两道关卡都过了）
 *   ★ 特别注意"教练也被拒"：这说明判据是"必须是管理员"，
 *     而不是"不是学员就行" —— 后者是个很容易写错的偷懒实现。
 * ============================================================
 */

const GATEWAY = 'http://localhost:10010';

const ADMIN_PHONE = '13800010003';
const COACH_PHONE = '13800010002';
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

async function call(method, path, { body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = token;
  const res = await fetch(GATEWAY + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text };
}

async function login(phone, { staff = false } = {}) {
  const codeRes = await call('POST', `/us/codes?phone=${phone}`);
  const code = codeRes.json?.data;
  if (!code) return null;
  const path = staff ? '/as/accounts/admin/login' : '/as/accounts/login';
  const res = await call('POST', path,
    { body: { type: 2, cellPhone: phone, password: String(code), rememberMe: false } });
  return res.json?.data || null;
}

/**
 * 5 个服务的管理端接口。
 * ⚠️ 网关对每个服务都 StripPrefix=1，所以服务内部看到的是 /admin/**，
 *    而这里写的是"网关路径"（带服务前缀）。
 */
const ENDPOINTS = [
  ['fm-trade',    '/ts/admin/refund-apply/page?pageNo=1&pageSize=1',       '退款审批'],
  ['fm-learning', '/ls/admin/notes/page?pageNo=1&pageSize=1',             '笔记管理'],
  ['fm-message',  '/sms/admin/notice-templates/page?pageNo=1&pageSize=1', '公告模板'],
  ['fm-data',     '/ds/admin/data/overview',                              '数据概览'],
  ['fm-aigc',     '/ais/admin/knowledge/status',                          '知识库状态'],
];

/** 403 的统一文案（由 ForbiddenException 决定） */
const FORBIDDEN_MSG = '无权访问管理端接口';

async function main() {
  console.log('============================================================');
  console.log(' FitMind 管理端鉴权（SDK 统一提供）—— 端到端验收');
  console.log('============================================================');
  console.log('★ 覆盖 5 个服务 × 3 种身份；断言"行为一致"而不只是"某一个是通的"');

  // ---------- 1. 三种身份 ----------
  section('1. 三种身份登录');
  const adminToken = await login(ADMIN_PHONE, { staff: true });
  check('管理员登录成功', !!adminToken, ADMIN_PHONE);
  const coachToken = await login(COACH_PHONE, { staff: true });
  check('教练登录成功', !!coachToken, COACH_PHONE);
  const studentToken = await login(STUDENT_PHONE);
  check('学员登录成功', !!studentToken, STUDENT_PHONE);

  // ---------- 2. 管理员可访问 ----------
  section('2. 管理员可访问各服务管理端接口（鉴权通过）');
  const adminResults = [];
  for (const [svc, path, desc] of ENDPOINTS) {
    const r = await call('GET', path, { token: adminToken });
    adminResults.push([svc, r]);
    check(`${svc} 管理端可访问（${desc}）`,
      r.status !== 401 && r.status !== 403 && r.json?.code !== 401 && r.json?.code !== 403,
      `HTTP ${r.status} code=${r.json?.code} msg=${r.json?.msg}`);
  }

  // ---------- 3. 非管理员被拒（403） ----------
  section('3. 学员访问管理端 → 403（知道你是谁，但你不是管理员）');
  const studentMsgs = new Set();
  for (const [svc, path, desc] of ENDPOINTS) {
    const r = await call('GET', path, { token: studentToken });
    studentMsgs.add(r.json?.msg);
    check(`${svc} 学员被拒 403（${desc}）`,
      r.status === 403 && r.json?.code === 403,
      `HTTP ${r.status} code=${r.json?.code} msg=${r.json?.msg}`);
  }

  section('4. 教练访问管理端 → 403（★ 判据是"必须是管理员"，不是"不是学员就行"）');
  for (const [svc, path] of ENDPOINTS) {
    const r = await call('GET', path, { token: coachToken });
    check(`${svc} 教练被拒 403`, r.status === 403, `HTTP ${r.status} code=${r.json?.code}`);
  }

  // ---------- 5. 未登录被拒（401） ----------
  section('5. 无 token 访问管理端 → 401（连"你是谁"都不知道）');
  const anonCodes = new Set();
  for (const [svc, path, desc] of ENDPOINTS) {
    const r = await call('GET', path);
    anonCodes.add(String(r.json?.code));
    check(`${svc} 未登录被拒 401（${desc}）`,
      r.status === 401, `HTTP ${r.status} code=${r.json?.code} msg=${r.json?.msg}`);
  }

  // ---------- 6. 「一致性」才是这次收敛真正要守的契约 ----------
  section('6. ★★ 一致性：5 个服务的鉴权行为必须完全相同');
  check('★★ 5 个服务的 403 文案完全一致（同一个实现，而不是 5 份各自为政的副本）',
    studentMsgs.size === 1 && studentMsgs.has(FORBIDDEN_MSG),
    `实际文案集合=${JSON.stringify([...studentMsgs])}`);
  check('★ 5 个服务的未登录错误码完全一致',
    anonCodes.size === 1, `错误码集合=${JSON.stringify([...anonCodes])}`);
  check('★★ 管理员在 5 个服务上都是"鉴权通过"（order 约定在 SDK 里生效，没有某个服务漏挂）',
    adminResults.every(([, r]) => r.status !== 401 && r.status !== 403),
    adminResults.map(([s, r]) => `${s}:${r.status}`).join(' '));

  // ---------- 7. 没误伤用户端接口 ----------
  section('7. 用户端接口不受影响（收敛只加了一层，没有改原有行为）');
  const quota = await call('GET', '/ais/chat/quota', { token: studentToken });
  check('学员访问用户端接口正常（/ais/chat/quota）',
    quota.status === 200 && quota.json?.code === 200,
    `HTTP ${quota.status} code=${quota.json?.code}`);
  // ⚠️ 这里刻意选一个**没有必填参数**的用户端接口。
  //    最初用的是 /ls/notes/page，它要求"课程id和训练动作id至少传一个"
  //    （业务规则，不是鉴权问题）→ 不带参数得到 400，
  //    看起来像"权限坏了"，实际是我的调用参数不对。
  //    ★ 选"查询类断言"的接口时，优先挑无参数的那个：少一个变量。
  const inbox = await call('GET', '/sms/messages/unread-count', { token: studentToken });
  check('学员访问用户端接口正常（/sms/messages/unread-count）',
    inbox.status === 200 && inbox.json?.code === 200,
    `HTTP ${inbox.status} code=${inbox.json?.code} msg=${inbox.json?.msg}`);

  // ---------- 汇总 ----------
  console.log('\n============================================================');
  console.log(` 结果：${passed} 通过 / ${failed} 失败`);
  if (failures.length) {
    console.log(' 失败项：');
    failures.forEach((f) => console.log(`   · ${f}`));
  }
  console.log('============================================================');
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('脚本异常：', e);
  process.exit(2);
});
