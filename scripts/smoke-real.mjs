#!/usr/bin/env node
/**
 * ============================================================
 * FitMind 真实模型冒烟（DeepSeek + 百炼 embedding）
 * ------------------------------------------------------------
 * 前置条件：
 *   ① 全部服务已启动，且 fm-aigc **跑在真实模型通道**上：
 *        bash scripts/start-all.sh fm-aigc deepseek
 *      （脚本会先查 /ais/chat/provider 确认，不是 deepseek 就直接退出）
 *   ② docker/.env 里有 DEEPSEEK_API_KEY 与 DASHSCOPE_API_KEY
 *
 * 用法：node scripts/smoke-real.mjs
 *
 * ★★ 与 accept-m6.mjs 的关系：两者**不是替代关系**，是互补的。
 *   · accept-m6.mjs 用内置 Mock 通道跑 200+ 项 —— 可重复、零成本、能断言到细节
 *     （prompt 快照、断言语义、卡片结构……）；
 *   · 本脚本只跑十余项，但用的是**真实模型**，它唯一要回答的问题是：
 *     "这条链路在真实模型下能不能跑通，以及真实模型会不会照提示词干活。"
 *
 * ★★ 断言原则：**只断言"结构性事实"，不断言文案。**
 *   真实模型的措辞每次都不一样（同一句话可能这次说"建议"、下次说"推荐"），
 *   任何依赖具体措辞的断言都会变成随机失败，而随机失败最后一定被"重跑一次"掩盖掉。
 *   所以这里断言的是：降级与否 / token 数 / 引用条数 / 工具名 / 记忆条数 ——
 *   这些是链路的形状，不是模型的心情。
 *
 * ⚠️ 费用与耗时：一次运行约 7~9 轮对话，真实 token 消耗会打印出来。
 *    单次成本很低（几厘到几分钱），但它**不该被放进高频回归**——
 *    默认回归仍然只用 Mock 通道。
 * ============================================================
 */

import { spawnSync } from 'node:child_process';

const GATEWAY = 'http://localhost:10010';
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';

const ADMIN_PHONE = '13800010003';
const STUDENT_PHONE = '13800010001';
const STUDENT_USER_ID = 1;

/** 真实模型下允许被调用的工具（防止模型幻觉出不存在的方法名） */
const ALLOWED_TOOLS = ['queryMyBodyProfile', 'searchCourses', 'queryCourseDetail', 'prePlaceOrder'];

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

function info(msg) {
  console.log(`  · ${msg}`);
}

function section(title) {
  console.log(`\n【${title}】`);
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
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

function sql(query) {
  const r = spawnSync(MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  return (r.stdout || '').trim();
}

/** 真实模型的成本累计（脚本结束时打印） */
let totalPromptTokens = 0;
let totalCompletionTokens = 0;
let totalMs = 0;

async function chat(token, body) {
  const r = await call('POST', '/ais/chat', { body, token });
  const d = r.json?.data || {};
  totalPromptTokens += Number(d.promptTokens || 0);
  totalCompletionTokens += Number(d.completionTokens || 0);
  totalMs += Number(d.totalMs || 0);
  return d;
}

async function main() {
  console.log('============================================================');
  console.log(' FitMind 真实模型冒烟（DeepSeek + 百炼）');
  console.log('============================================================');
  console.log('★ 项数少但锋利：每一项都在回答"真实模型下这条链路还成立吗"');

  // ---------- 0. 前置：必须是真实模型通道 ----------
  section('0. 前置检查');
  const anon = await call('GET', '/ais/chat/provider');
  // provider 接口需要登录（/chat/** 都在 includeLoginPaths 里），先登录再查
  const adminToken = await login(ADMIN_PHONE, { staff: true });
  const studentToken = await login(STUDENT_PHONE);
  if (!adminToken || !studentToken) {
    console.log('  ❌ 登录失败（验证码可能被限流，等 60 秒后重试）');
    process.exit(2);
  }
  const providerRes = await call('GET', '/ais/chat/provider', { token: studentToken });
  // ⚠️ 这个接口虽然返回 String，但仍被全局响应包装成 R 结构（实测：
  //    {"code":200,"msg":"OK","data":"deepseek"}）—— 取 data 才是真实值。
  //    直接读 text 会拿到一整个 JSON 串，`!== 'deepseek'` 永远成立，
  //    表现为"明明跑在真实模型上却说不是"。
  const provider = providerRes.json?.data;
  if (provider !== 'deepseek') {
    console.log(`  ❌ 当前模型通道是 "${provider}"，本脚本需要真实模型。`);
    console.log('     请先重启 fm-aigc：');
    console.log('       bash scripts/start-all.sh fm-aigc deepseek');
    process.exit(2);
  }
  check('模型通道是真实模型（deepseek）', true, provider);

  // 把额度临时调大：冒烟要跑好几轮，不希望它被日常配额挡住
  // （结束时恢复 —— 全局状态不留残迹，否则下次运行一上来就被限住）
  await call('POST', `/ais/admin/limit?dailyChats=200&dailyTokens=2000000`, { token: adminToken });

  // ---------- 1. 基线对话 ----------
  section('1. 基线对话（SIMPLE：不检索、不用工具）');
  const simple = await chat(studentToken, { content: '你好，简单介绍一下你自己', chatType: 'SIMPLE' });
  check('未降级（真实模型可用）', simple.degraded !== true, `degraded=${simple.degraded}`);
  check('拿到了有实质内容的中文回答',
    typeof simple.content === 'string' && simple.content.length >= 20,
    `长度=${(simple.content || '').length} 内容前 40 字=${(simple.content || '').slice(0, 40)}`);
  check('真实模型返回了 token 用量（不是 0）',
    Number(simple.promptTokens) > 0 && Number(simple.completionTokens) > 0,
    `prompt=${simple.promptTokens} completion=${simple.completionTokens}`);
  info(`首字 ${simple.firstTokenMs}ms / 总耗时 ${simple.totalMs}ms`);

  // ---------- 2. RAG（真实 embedding） ----------
  section('2. RAG 检索（真实百炼 embedding + ES）');
  const rag = await chat(studentToken, { content: '增肌期每天应该吃多少蛋白质？', chatType: 'ENHANCE' });
  const sources = rag.rag?.sources || [];
  check('未降级', rag.degraded !== true);
  check('★ 检索到了知识库资料（真实 embedding 链路通）',
    sources.length > 0, `来源 ${sources.length} 条：${sources.map((s) => s.docId).join(',')}`);
  check('★ 检索成功（RagTrace.mode = rag）',
    rag.rag?.mode === 'rag', `mode=${rag.rag?.mode}`);
  check('回答有实质内容',
    typeof rag.content === 'string' && rag.content.length >= 20,
    `长度=${(rag.content || '').length}`);
  // ⚠️ Source 的字段是 docId / title / section / score / preview ——
  //    没有 chunkIndex（切片位置由 section 表达）。写断言/打印前**从 record 定义里抄字段名**，
  //    凭印象写会得到 `#undefined` 这种"看起来像数据坏了"的假象。
  info(`引用来源：${sources.map((s) => `${s.docId}《${s.section}》${s.score == null ? '' : s.score.toFixed(3)}`).join('，') || '（无）'}`);
  info(`回答前 80 字：${(rag.content || '').replace(/\n/g, ' ').slice(0, 80)}`);

  // ---------- 3. 多轮记忆 ----------
  section('3. 多轮记忆（同一会话第二轮）');
  const mem2 = await chat(studentToken, {
    content: '那我 64 公斤，按你说的算下来大概多少克？',
    sessionId: rag.sessionId,
  });
  check('★ 第二轮带上了历史（记忆进了 prompt）',
    Number(mem2.memoryHistory) > 0, `memoryHistory=${mem2.memoryHistory}`);
  check('第二轮未降级', mem2.degraded !== true);
  info(`第二轮回答前 80 字：${(mem2.content || '').replace(/\n/g, ' ').slice(0, 80)}`);

  // ---------- 4. 工具调用（真实模型会不会照提示词调工具） ----------
  section('4. ★★ 工具调用（真实模型照提示词干活吗）');
  const rec = await chat(studentToken, {
    content: '帮我推荐一门适合我的课程，先看看我的体测档案',
    chatType: 'RECOMMEND',
  });
  const toolNames = rec.toolNames || [];
  check('未降级', rec.degraded !== true);
  check('★★ 真实模型主动调用了工具（不是"说要调，然后没有然后了"）',
    toolNames.length > 0,
    `toolCallCount=${rec.toolCallCount} toolNames=${JSON.stringify(toolNames)}`);
  check('★ 调用的工具都在允许集合内（没有幻觉出不存在的工具）',
    toolNames.every((n) => ALLOWED_TOOLS.includes(n)),
    `实际=${JSON.stringify(toolNames)}，允许=${JSON.stringify(ALLOWED_TOOLS)}`);
  check('★ 模型先读了用户档案（提示词里要求的调用顺序）',
    toolNames.includes('queryMyBodyProfile'),
    `toolNames=${JSON.stringify(toolNames)}`);
  info(`工具链：${toolNames.join(' → ') || '（未调用）'}`);
  info(`回答前 100 字：${(rec.content || '').replace(/\n/g, ' ').slice(0, 100)}`);

  // ---------- 5. 库外问题（拒答） ----------
  section('5. 库外问题（知识库没有 → 应当拒答而不是编）');
  const out = await chat(studentToken, { content: '请帮我算一下 2025 年个人所得税怎么报', chatType: 'ENHANCE' });
  check('未降级（检索不到 ≠ 服务坏了）', out.degraded !== true);
  check('★ 检索结果为空（没有硬塞一段不相关的资料给它）',
    (out.rag?.sources || []).length === 0,
    `来源 ${(out.rag?.sources || []).length} 条`);
  info(`回答前 80 字：${(out.content || '').replace(/\n/g, ' ').slice(0, 80)}`);
  console.log('     （"有没有真的拒答"由模型文案决定，脚本不做文案断言 —— 见文件头说明）');

  // ---------- 6. 成本与实际值 ----------
  section('6. 本次冒烟的真实消耗');
  info(`prompt token 合计：${totalPromptTokens}`);
  info(`completion token 合计：${totalCompletionTokens}`);
  info(`模型耗时合计：${(totalMs / 1000).toFixed(1)} 秒`);
  check('真实消耗已产生（说明前面每一轮都真的走到了模型）',
    totalPromptTokens > 0 && totalCompletionTokens > 0);

  // ---------- 7. 清理 ----------
  section('7. 清理（不留测试数据）');
  sql(`DELETE FROM fm_aigc.chat_message WHERE user_id=${STUDENT_USER_ID}`);
  sql(`DELETE FROM fm_aigc.chat_session WHERE user_id=${STUDENT_USER_ID}`);
  // 恢复配置额度（DELETE /admin/limit）+ 清空该用户今日计数
  await call('DELETE', '/ais/admin/limit', { token: adminToken });
  await call('DELETE', `/ais/admin/limit/counter?userId=${STUDENT_USER_ID}`, { token: adminToken });
  const leftSessions = sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE user_id=${STUDENT_USER_ID}`);
  check('会话与消息已清理', leftSessions === '0', `剩余 ${leftSessions} 条会话`);

  console.log('\n============================================================');
  console.log(` 结果：${passed} 通过 / ${failed} 失败`);
  if (failures.length) {
    console.log(' 失败项：');
    failures.forEach((f) => console.log(`   · ${f}`));
  }
  console.log('============================================================');
  await sleep(10);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('脚本异常：', e);
  process.exit(2);
});
