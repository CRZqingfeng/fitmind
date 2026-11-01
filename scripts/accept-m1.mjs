#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M1 端到端验收脚本
 * ------------------------------------------------------------
 * 前置条件：fm-auth(8081)、fm-user(8082)、fm-gateway(10010) 已启动，
 *          中间件（MySQL / Redis / Nacos）已运行。
 *
 * 用法：node scripts/accept-m1.mjs
 * ============================================================
 */

import { spawnSync } from 'node:child_process';

const GATEWAY = 'http://localhost:10010';
/** ⚠️ 本机 mysql 是完整路径（不在 PATH 里）—— 与其它脚本保持一致 */
const MYSQL = 'C:////Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';

/** 查询：失败即抛（★ 静默返回空串会让"期望为空"的断言假通过） */
function sql(query) {
  const r = spawnSync(
    MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  );
  if (r.error) throw new Error(`SQL 执行失败（进程无法启动）：${query}\n${r.error.message}`);
  if (r.status !== 0) throw new Error(`SQL 执行失败（退出码 ${r.status}）：${query}\n${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

/** 容忍失败的查询：只用于**清理**动作（失败不中断），清理完必须用 sql() 自检 */
function sqlSoft(query) {
  try {
    spawnSync(
      MYSQL,
      ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
  } catch {
    /* 清理失败不中断 —— 后面会用 sql() 自检 */
  }
}

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

async function call(method, path, { body, token, raw } = {}) {
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
    /* 非 JSON 响应 */
  }
  return { status: res.status, json, text };
}

function section(title) {
  console.log(`\n【${title}】`);
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M1 认证与用户 —— 端到端验收');
  console.log('========================================');
  console.log(`网关地址：${GATEWAY}`);

  const STUDENT_PHONE = '13800010001';

  // ---------- 1. 发送验证码 ----------
  section('1. 发送验证码');
  const codeRes = await call('POST', `/us/codes?phone=${STUDENT_PHONE}`);
  check('接口返回 HTTP 200', codeRes.status === 200, `实际 ${codeRes.status}`);
  check('返回结构为统一响应 R', codeRes.json && typeof codeRes.json.code !== 'undefined');
  const verifyCode = codeRes.json?.data;
  check('Mock 模式下返回验证码', !!verifyCode, `data=${JSON.stringify(codeRes.json?.data)}`);
  console.log(`     验证码：${verifyCode}`);

  // 频率限制
  const codeRes2 = await call('POST', `/us/codes?phone=${STUDENT_PHONE}`);
  check('60 秒内重复发送被限流', codeRes2.json?.code !== 200, `msg=${codeRes2.json?.msg}`);

  // ---------- 2. 验证码登录 ----------
  section('2. 验证码登录');
  const loginRes = await call('POST', '/as/accounts/login', {
    body: { type: 2, cellPhone: STUDENT_PHONE, password: verifyCode, rememberMe: false },
  });
  check('登录成功', loginRes.json?.code === 200, `msg=${loginRes.json?.msg}`);
  const token = loginRes.json?.data;
  check('返回 token', typeof token === 'string' && token.length > 20);
  if (!token) {
    console.log('\n⚠️  登录失败，后续用例无法继续');
    return summary();
  }

  // ---------- 3. 未登录被拦截 ----------
  section('3. 未登录访问受保护接口');
  const noTokenRes = await call('GET', '/us/users/me');
  check('网关返回 401', noTokenRes.status === 401, `实际 ${noTokenRes.status}`);
  check('返回明确的错误码', noTokenRes.json?.code >= 4000, `code=${noTokenRes.json?.code}`);

  // 伪造 token
  const fakeRes = await call('GET', '/us/users/me', { token: 'Bearer fake.token.value' });
  check('伪造 token 被拒绝', fakeRes.status === 401, `实际 ${fakeRes.status}`);

  // ---------- 4. 带 token 访问 ----------
  section('4. 登录后访问当前用户信息');
  const meRes = await call('GET', '/us/users/me', { token });
  check('接口成功', meRes.json?.code === 200, `msg=${meRes.json?.msg}`);
  // ⚠️ id 现在是字符串（全局 Long→String，防雪花 id 在 JS 精度丢失）
  check('返回正确用户', String(meRes.json?.data?.id) === '1', `id=${meRes.json?.data?.id}`);
  check('手机号一致', meRes.json?.data?.cellPhone === STUDENT_PHONE);
  check('用户类型为学员(2)', meRes.json?.data?.type === 2, `type=${meRes.json?.data?.type}`);
  check('请求链路 id 已透传', !!meRes.json?.requestId, `requestId=${meRes.json?.requestId}`);
  console.log(`     用户：${JSON.stringify(meRes.json?.data)}`);

  // ---------- 5. 读取身体档案 ----------
  section('5. 读取身体档案（种子数据）');
  const profileRes = await call('GET', '/us/users/me/profile', { token });
  check('接口成功', profileRes.json?.code === 200, `msg=${profileRes.json?.msg}`);
  check('返回身高', profileRes.json?.data?.height > 0, `height=${profileRes.json?.data?.height}`);
  check('返回 BMI', profileRes.json?.data?.bmi > 0, `bmi=${profileRes.json?.data?.bmi}`);
  check('目标名称已翻译', !!profileRes.json?.data?.goalTypeName, `goalTypeName=${profileRes.json?.data?.goalTypeName}`);
  console.log(`     档案：${JSON.stringify(profileRes.json?.data)}`);

  // ---------- 6. 写入身体档案（验证 BMI 自动计算） ----------
  section('6. 保存身体档案（验证 BMI 后端计算）');
  const saveRes = await call('PUT', '/us/users/me/profile', {
    token,
    body: {
      height: 182.0,
      weight: 70.0,
      targetWeight: 75.0,
      bodyFatRate: 15.5,
      waist: 76.0,
      chest: 95.0,
      arm: 32.0,
      hip: 94.0,
      goalType: 1,
      trainingLevel: 2,
      weeklyFrequency: 4,
      injuryHistory: '无',
    },
  });
  check('保存成功', saveRes.json?.code === 200, `msg=${saveRes.json?.msg}`);
  // BMI = 70 / 1.82^2 ≈ 21.1
  const expectedBmi = 21.1;
  const actualBmi = Number(saveRes.json?.data?.bmi);
  check(`BMI 自动计算正确（期望 ≈${expectedBmi}）`, Math.abs(actualBmi - expectedBmi) < 0.15,
    `实际 ${actualBmi}`);
  check('训练目标名称已翻译', saveRes.json?.data?.goalTypeName === '增肌',
    `实际 ${saveRes.json?.data?.goalTypeName}`);

  // ---------- 7. 学员注册 ----------
  section('7. 学员注册');
  const NEW_PHONE = '139' + String(Date.now()).slice(-8);
  const newCodeRes = await call('POST', `/us/codes?phone=${NEW_PHONE}`);
  const newCode = newCodeRes.json?.data;
  check('新手机号可发送验证码', !!newCode, `msg=${newCodeRes.json?.msg}`);

  const regRes = await call('POST', '/us/students/register', {
    body: { cellPhone: NEW_PHONE, code: newCode, password: 'abc123456', name: '验收新学员' },
  });
  check('注册成功', regRes.json?.code === 200, `msg=${regRes.json?.msg}`);

  if (regRes.json?.code === 200) {
    // 新用户登录并验证免鉴权注册接口确实放行
    const newLogin = await call('POST', '/as/accounts/login', {
      body: { type: 2, cellPhone: NEW_PHONE, password: newCode, rememberMe: false },
    });
    // 验证码已被消费，重新发一次
    let loginOk = newLogin.json?.code === 200;
    if (!loginOk) {
      const c2 = await call('POST', `/us/codes?phone=${NEW_PHONE}`);
      const l2 = await call('POST', '/as/accounts/login', {
        body: { type: 2, cellPhone: NEW_PHONE, password: c2.json?.data, rememberMe: false },
      });
      loginOk = l2.json?.code === 200;
    }
    check('新注册学员可登录', loginOk);

    // ---------- 7.1 清理：把本次注册的账号删掉 ----------
    // ★ 这一段是**后来补的**：这个用例原本"注册完就结束"，于是
    //   **全量回归每跑一轮就多留一个账号**（实测 M8-2a 的两轮回归留下了 id=31/32，
    //   见《待修复问题清单》#B7）。验收自己造的数据要自己收干净 ——
    //   否则跑得越多库越脏，而且很难看出是谁留下的。
    sqlSoft(`DELETE FROM fm_auth.login_record WHERE cell_phone='${NEW_PHONE}'`);
    const newUserId = sql(`SELECT id FROM fm_user.user WHERE cell_phone='${NEW_PHONE}'`);
    if (newUserId) {
      sqlSoft(`DELETE FROM fm_user.user_detail WHERE id=${newUserId}`);
      sqlSoft(`DELETE FROM fm_user.user WHERE id=${newUserId}`);
    }
    // ★ 清理必须自检：sqlSoft 吞失败，不自检就等于把失败藏起来了
    check('★ 清理：本次注册的账号已删除（不给后续回归留垃圾）',
      sql(`SELECT COUNT(*) FROM fm_user.user WHERE cell_phone='${NEW_PHONE}'`) === '0',
      `残留=${sql(`SELECT COUNT(*) FROM fm_user.user WHERE cell_phone='${NEW_PHONE}'`)}`);
    check('★ 清理自检：登录流水也清了',
      sql(`SELECT COUNT(*) FROM fm_auth.login_record WHERE cell_phone='${NEW_PHONE}'`) === '0',
      `残留=${sql(`SELECT COUNT(*) FROM fm_auth.login_record WHERE cell_phone='${NEW_PHONE}'`)}`);
  }

  // ---------- 8. 密码登录通道 ----------
  section('8. 密码登录通道（保留能力）');
  const pwCode = await call('POST', `/us/codes?phone=${STUDENT_PHONE}`);
  const pwLogin = await call('POST', '/as/accounts/login', {
    body: { type: 1, cellPhone: STUDENT_PHONE, password: 'wrong-password' },
  });
  check('错误密码被拒绝', pwLogin.json?.code !== 200, `msg=${pwLogin.json?.msg}`);

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
