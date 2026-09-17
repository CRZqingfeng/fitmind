#!/usr/bin/env node
/**
 * ============================================================================
 * M6 验收：AI 教练（fm-aigc）
 * ============================================================================
 *
 * 用法：
 *   node scripts/accept-m6.mjs            # 默认：走 Mock 模型通道（全自动、零成本）
 *   node scripts/accept-m6.mjs --real     # 额外跑一组真实模型冒烟（需要 DEEPSEEK_API_KEY）
 *
 * ★ 为什么默认走 Mock 通道：
 *   AI 链路的正确性和"模型说了什么"是两件事。让验收脚本依赖真实模型，
 *   等于让"改一行检索参数"变成"必须真调 API 才能知道有没有断" ——
 *   会慢、会花钱、会因为限流而随机失败。
 *   Mock 通道实现的是 Spring AI 的 ChatModel 接口本身，所以
 *   Prompt 组装 / Advisor 链 / SSE 分片 / 工具调用 / 落库这些**真实执行**，
 *   只有"生成"这一步是确定的。
 *   （与 M4-2 的 Mock 支付渠道、M4-4 的 Mock 退款同一个方法论。）
 *
 * ⚠️ 前置：fm-aigc 必须以 mock 通道启动：
 *   bash scripts/start-all.sh fm-aigc mock
 *   脚本会先检查 /ais/chat/provider，不是 mock 就直接报错退出。
 *
 * ⚠️ 验证码限流：同一手机号 60 秒内只能取一次验证码。
 *   本脚本只在开头登录一次（不像别的脚本会反复登录），所以不受影响；
 *   但如果和别的脚本连着跑，中间要等 65 秒。
 * ============================================================================
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';

// ★ M6-3：固定问答评估集（检索命中率的唯一可比较依据）
import { RAG_EVAL_SET, RAG_REFUSAL_SET } from './rag-eval-set.mjs';

/**
 * 检索命中率下限。
 *
 * ★ 为什么不设成 100%：评估集是"自建语料 + 自拟问题"，
 *   总有几个问题在语义上和另一篇语料更近（比如"减脂期蛋白质"同时像 05 和 07）。
 *   把阈值设成 100% 的结果是"每次都在调那几个边缘用例"，
 *   而真正的目标（改一个参数看整体涨跌）反而看不出来。
 *   75% 是一个"链路正常就一定过、坏了就一定不过"的位置。
 */
const RAG_EVAL_HIT_FLOOR = 0.75;

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

async function call(method, path, { body, token, retryOn401 = true } = {}) {
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
// ==================== M6 常量与专用工具 ====================

const STUDENT_USER_ID = 1;
const PROVIDER_API = '/ais/chat/provider';
const CHAT_API = '/ais/chat';
const STREAM_API = '/ais/chat/stream';
const SESSIONS_API = '/ais/chat/sessions';
const MOCK_SNAPSHOT_KEY = 'aigc:mock:prompts';

/** 解析 SSE 响应体（Spring SseEmitter 输出的 event:/data: 块） */
function parseSse(text) {
  const events = [];
  for (const block of text.split(/\n\n/)) {
    if (!block.trim()) continue;
    let name = null;
    let data = '';
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim();
      // ★ data 可能有多行（内容里带换行会被 SSE 规范拆成多个 data: 行），
      //   所以要累加而不是覆盖 —— 漏了这个会让"含换行的回答"比对失败
      else if (line.startsWith('data:')) data += line.slice(5);
    }
    if (name) events.push({ name, data });
  }
  return events;
}

/** 走一次真正的 SSE 流式对话 */
async function sseChat(question, token, sessionId) {
  const body = sessionId ? { content: question, sessionId } : { content: question };
  const res = await fetch(GATEWAY + STREAM_API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token },
    body: JSON.stringify(body),
  });
  const contentType = res.headers.get('content-type') || '';
  const text = await res.text();
  return { status: res.status, contentType, raw: text, events: parseSse(text) };
}

/** 从事件流里拼出回答正文 */
function joinData(events) {
  return events.filter((e) => e.name === 'DATA').map((e) => e.data).join('');
}

/** 取 STOP 事件的指标载荷 */
function stopMeta(events) {
  const stop = events.find((e) => e.name === 'STOP');
  if (!stop) return null;
  try {
    return JSON.parse(stop.data);
  } catch {
    return null;
  }
}

/** 清理本脚本产生的数据（会话 / 消息 / Redis 快照） */
function aiCleanup() {
  const steps = [
    `DELETE FROM fm_aigc.chat_message WHERE user_id=${STUDENT_USER_ID}`,
    `DELETE FROM fm_aigc.chat_session WHERE user_id=${STUDENT_USER_ID}`,
  ];
  let ok = 0;
  for (const s of steps) {
    if (sql(s) !== null || true) ok++;
  }
  redis('DEL', MOCK_SNAPSHOT_KEY);
  return ok;
}

// ==================== 主流程 ====================

async function main() {
  console.log('='.repeat(74));
  console.log(' FitMind M6 验收 —— AI 教练（fm-aigc）');
  console.log('='.repeat(74));

  // ==================== 1. 前置与通道检查 ====================
  section('1. 登录与模型通道');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', !!token, token ? '' : '请确认 fm-auth 正常');

  if (!token) return summary();

  const providerRes = await call('GET', PROVIDER_API, { token });
  const provider = providerRes.json?.data;
  check('当前模型通道 = mock（验收默认；真实模型请加 --real）',
    provider === 'mock', `provider=${provider}`);
  if (provider !== 'mock') {
    console.log('\n❌ 需要 Mock 通道。请用：bash scripts/start-all.sh fm-aigc mock\n');
    return summary();
  }

  const anon = await call('POST', CHAT_API, { body: { content: '未登录试试' } });
  check('未登录访问对话接口被拦（401）', anon.status === 401, `status=${anon.status}`);

  // 隔离上一轮残留（本脚本可重复运行）
  aiCleanup();
  check('上一轮数据已隔离（会话/消息/快照清零）',
    sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE user_id=${STUDENT_USER_ID}`) === '0');

  // ==================== 2. Mock 通道特性 ====================
  section('2. ★ Mock 通道（让 AI 链路可自动化验收）');

  const q1 = '增肌期每天要吃多少蛋白质？';
  const r1 = await call('POST', CHAT_API, { body: { content: q1 }, token });
  const a1 = r1.json?.data || {};
  check('Mock 回答含可预期内容（断言才有依据）',
    String(a1.content || '').includes('蛋白质'), `content=${String(a1.content).slice(0, 40)}`);
  check('Mock 回答标注了通道与上下文长度（★ 这是验收 RAG 注入的抓手）',
    String(a1.content || '').includes('Mock 通道') && String(a1.content || '').includes('上下文'),
    `content=${String(a1.content).slice(-40)}`);

  const r1b = await call('POST', CHAT_API, { body: { content: q1 }, token });
  check('★ Mock 是确定性的：同一问题两次回答完全一致',
    a1.content === r1b.json?.data?.content);

  check('countToken 指标非 0（可观测列不能是摆设）',
    Number(a1.promptTokens) > 0 && Number(a1.completionTokens) > 0,
    `prompt=${a1.promptTokens}, completion=${a1.completionTokens}`);
  check('首字延迟与总耗时均已记录',
    Number(a1.firstTokenMs) >= 0 && Number(a1.totalMs) > 0,
    `首字=${a1.firstTokenMs}ms 总=${a1.totalMs}ms`);

  const snapshotLen = redis('LLEN', MOCK_SNAPSHOT_KEY);
  const snapshotTop = redis('LINDEX', MOCK_SNAPSHOT_KEY, '0') || '';
  check('★ Mock 把 prompt 全文写进了 Redis（供断言"检索到的文档确实进了 prompt"）',
    Number(snapshotLen) > 0, `快照条数=${snapshotLen}`);
  check('prompt 快照里含 system 人设（证明 system prompt 真的被送进模型）',
    snapshotTop.includes('FitMind'), `前 30 字=${snapshotTop.slice(0, 30)}`);

  // ==================== 3. SSE 事件协议 ====================
  section('3. SSE 流式与事件协议');

  const sq = '减脂应该怎么安排热量？';
  const stream = await sseChat(sq, token, null);
  check('SSE 响应头是 text/event-stream',
    stream.contentType.includes('text/event-stream'), `contentType=${stream.contentType}`);
  // ⚠️ M6-3 起协议里真的会多出 PARAM（引用来源），所以断言从"只有 DATA/STOP"
  //    改成"只允许这三种" —— 这条断言本身没变松，它防的一直是
  //    "随手往流里塞第四种事件类型"（前端会不认识）。
  check('事件类型只有 DATA / PARAM / STOP（协议不引入多余类型）',
    [...new Set(stream.events.map((e) => e.name))].every((n) => ['DATA', 'PARAM', 'STOP'].includes(n)),
    `类型=${[...new Set(stream.events.map((e) => e.name))].join(',')}`);
  check('首个事件是 DATA', stream.events[0]?.name === 'DATA');
  check('末个事件是 STOP（显式结束，前端才能区分"回答完了"和"掉线了"）',
    stream.events[stream.events.length - 1]?.name === 'STOP');
  check('★ 真的分了多个片（不是一次返回完整字符串）',
    stream.events.filter((e) => e.name === 'DATA').length > 1,
    `分片数=${stream.events.filter((e) => e.name === 'DATA').length}`);

  const streamedAnswer = joinData(stream.events);
  const plain = await call('POST', CHAT_API, { body: { content: sq }, token });
  const plainAnswer = plain.json?.data?.content || '';
  check('★ 流式分片拼接 == 非流式完整回答（两条路径一份实现）',
    streamedAnswer === plainAnswer,
    `流式 ${streamedAnswer.length} 字 / 非流式 ${plainAnswer.length} 字`);
  // ⚠️ 这里刻意**不**断言回答里含某个具体词：M6-3 起回答内容受检索结果影响
  //    （检索不到就拒答），把内容写进断言会让"中文编码"这条用例被检索效果绑架。
  //    只断言"有正常汉字、没有替换字符" —— 这才是它真正要防的问题。
  check('中文未乱码（UTF-8 端到端）',
    /[\u4e00-\u9fa5]/.test(streamedAnswer) && !streamedAnswer.includes('\uFFFD'),
    `回答前 30 字=${streamedAnswer.slice(0, 30)}`);

  const meta = stopMeta(stream.events);
  check('STOP 载荷带本轮指标（前端可显示耗时，非流式接口也靠它拿指标）',
    !!meta && !!meta.requestId && meta.totalMs > 0 && meta.sessionId,
    `meta=${JSON.stringify(meta)}`);
  check('STOP 载荷的 degraded = false（正常回答不误报降级）', meta?.degraded === false);

  // ==================== 4. 会话与消息落库 ====================
  section('4. 会话与消息（落库是可观测与历史重渲染的基础）');

  const newTurn = await call('POST', CHAT_API, { body: { content: '我想练胸' }, token });
  const sid = newTurn.json?.data?.sessionId;
  check('不传 sessionId 时服务端生成并回填', !!sid, `sessionId=${sid}`);

  const sameTurn = await call('POST', CHAT_API, { body: { content: '那肩膀呢', sessionId: sid }, token });
  check('★ 传 sessionId 时复用同一会话（不是每次都新建）',
    sameTurn.json?.data?.sessionId === sid,
    `返回=${sameTurn.json?.data?.sessionId}`);

  const page1 = await call('GET', `${SESSIONS_API}?pageNo=1&pageSize=2`, { token });
  const p1 = page1.json?.data || {};
  check('★ 分页 total 正确（分页插件生效 —— 缺了它 total 恒为 0）',
    Number(p1.total) > 0 && (p1.list || []).length <= 2,
    `total=${p1.total} list=${(p1.list || []).length}`);
  check('分页 pageSize 生效（列表条数不超过 pageSize）', (p1.list || []).length <= 2);

  const msgs = await call('GET', `${SESSIONS_API}/${sid}/messages`, { token });
  const list = msgs.json?.data || [];
  check('消息历史 = 两轮共 4 条（这个会话在本组用例里发了 2 轮）',
    list.length === 4, `条数=${list.length}`);
  check('★ 问答严格交替 user/assistant（顺序错乱会让多轮上下文彻底失效）',
    list.length > 0 && list.every((m, i) => (i % 2 === 0 ? m.role === 'user' : m.role === 'assistant')),
    `roles=${list.map((m) => m.role).join(',')}`);
  check('★ 助手消息带完整指标（token / 首字延迟 / 总耗时）',
    Number(list[1]?.firstTokenMs) >= 0 && Number(list[1]?.totalMs) > 0,
    `首字=${list[1]?.firstTokenMs} 总=${list[1]?.totalMs}`);
  check('助手消息记录了模型通道（复盘时第一眼要看的信息）',
    list[1]?.provider === 'mock', `provider=${list[1]?.provider}`);

  const titleRow = sql(`SELECT title FROM fm_aigc.chat_session WHERE session_id='${sid}' AND user_id=${STUDENT_USER_ID}`);
  check('会话标题取首轮提问（不额外花一次模型调用去生成摘要）',
    titleRow === '我想练胸', `title=${titleRow}`);
  const cnt = sql(`SELECT msg_count FROM fm_aigc.chat_session WHERE session_id='${sid}' AND user_id=${STUDENT_USER_ID}`);
  check('会话消息计数 = 4（两轮 × 一问一答）', cnt === '4', `msg_count=${cnt}`);

  const others = await call('GET', `${SESSIONS_API}/999999999999999999/messages`, { token });
  check('★ 越权：访问非本会话 → 拒绝（不区分"不存在"与"不是你的"）',
    others.status !== 200 || others.json?.success === false,
    `status=${others.status} msg=${others.json?.msg}`);

  // ==================== 5. 降级（模型不可用不是 500） ====================
  section('5. ★ 降级：模型超时/异常要给人话，不是 500');

  const to = await call('POST', CHAT_API, { body: { content: '[MOCK:TIMEOUT] 今天练什么' }, token });
  check('模型超时 → HTTP 200 + 可读文案（不是 500）',
    to.status === 200 && String(to.json?.data?.content || '').includes('稍等'),
    `status=${to.status} content=${to.json?.data?.content}`);
  check('降级时 degraded=true（前端据此显示"重试"，不靠猜文案）',
    to.json?.data?.degraded === true);

  const er = await call('POST', CHAT_API, { body: { content: '[MOCK:ERROR] 今天练什么' }, token });
  check('模型异常 → 同样降级为可读文案', er.status === 200 && er.json?.data?.degraded === true);

  const toMeta = await call('GET', `${SESSIONS_API}/${to.json?.data?.sessionId}/messages`, { token });
  const toList = toMeta.json?.data || [];
  check('降级回答也落库（复盘时能看到当时真的降级了）',
    toList.length === 2 && toList[1]?.content?.includes('稍等'),
    `条数=${toList.length}`);
  check('降级轮次没有首字延迟（本来就没吐出 token，不能编一个数出来）',
    toList[1]?.firstTokenMs === null || toList[1]?.firstTokenMs === undefined,
    `firstTokenMs=${toList[1]?.firstTokenMs}`);

  // ==================== 6. 参数校验 ====================
  section('6. 参数校验');

  const blank = await call('POST', CHAT_API, { body: { content: '' }, token });
  check('空提问被拒', blank.status === 400 || blank.json?.success === false,
    `status=${blank.status} msg=${blank.json?.msg}`);

  const tooLong = await call('POST', CHAT_API, { body: { content: 'x'.repeat(1001) }, token });
  check('超长提问被拒（有上限，防止把上下文一次打满）',
    tooLong.status === 400 || tooLong.json?.success === false,
    `status=${tooLong.status} msg=${tooLong.json?.msg}`);

  const noAuth = await call('GET', SESSIONS_API, {});
  check('未登录访问会话列表被拦', noAuth.status === 401, `status=${noAuth.status}`);


  // ==================== 8. 多 Agent 路由（M6-2） ====================
  section('8. ★ 多 Agent 意图路由');

  const routeCases = [
    ['帮我推荐一门适合我的课', 'recommend'],
    ['这门课多少钱', 'buy'],
    ['哑铃全身训练这门课有几节', 'consult'],
    ['增肌期要吃多少蛋白质', 'knowledge'],
  ];
  for (const [q, expect] of routeCases) {
    const r = await call('POST', CHAT_API, { body: { content: q }, token });
    const got = r.json?.data?.agentType;
    check(`路由正确：${q} → ${expect}`, got === expect, `实际=${got}`);
  }

  const routedSession = (await call('POST', CHAT_API,
    { body: { content: '帮我推荐一门课', chatType: 'ROUTE' }, token })).json?.data?.sessionId;
  const routedDb = sql(`SELECT agent_type FROM fm_aigc.chat_message WHERE session_id='${routedSession}' AND role='assistant'`);
  check('★ agent_type 落库（复盘时要知道这条回答是谁产出的）',
    routedDb === 'recommend', `落库值=${routedDb}`);

  // ★ 路由是"每轮独立判定"而不是"会话级定一次"：
  //   同一个会话里连着问两类不同的问题，两次应该落到不同的 Agent
  const mixed1 = (await call('POST', CHAT_API, { body: { content: '这门课多少钱' }, token })).json?.data;
  const mixed2 = (await call('POST', CHAT_API,
    { body: { content: '那增肌期该吃多少蛋白质', sessionId: mixed1?.sessionId }, token })).json?.data;
  check('★ 同一会话内路由逐轮独立（先 buy 后 knowledge）',
    mixed1?.agentType === 'buy' && mixed2?.agentType === 'knowledge',
    `第一轮=${mixed1?.agentType} 第二轮=${mixed2?.agentType}`);

  // ==================== 9. 三种编排策略（M6-2） ====================
  section('9. ★ 三种编排策略（配置切换 + 请求级覆盖）');

  const qSame = '增肌期要吃多少蛋白质';
  const byType = {};
  for (const ct of ['ROUTE', 'ENHANCE', 'SIMPLE']) {
    const r = await call('POST', CHAT_API, { body: { content: qSame, chatType: ct }, token });
    byType[ct] = r.json?.data;
    check(`${ct} 策略可用并返回结果`, !!byType[ct]?.content, `agent=${byType[ct]?.agentType}`);
  }
  check('ROUTE → 用路由判定出来的 Agent',
    byType.ROUTE?.agentType === 'knowledge', `agent=${byType.ROUTE?.agentType}`);
  check('ENHANCE → 固定用知识问答 Agent（不分派）',
    byType.ENHANCE?.agentType === 'knowledge', `agent=${byType.ENHANCE?.agentType}`);
  check('SIMPLE → 不经过 Agent（对比基线）',
    byType.SIMPLE?.agentType === 'simple', `agent=${byType.SIMPLE?.agentType}`);

  // ★ 这是"多 Agent 到底贵多少"的直接答案：ROUTE 多一次路由调用
  check('★★ ROUTE 的 prompt token 高于 ENHANCE（路由开销被计入本轮总量）',
    Number(byType.ROUTE?.promptTokens) > Number(byType.ENHANCE?.promptTokens),
    `ROUTE=${byType.ROUTE?.promptTokens}（其中路由 ${byType.ROUTE?.routePromptTokens}）vs ENHANCE=${byType.ENHANCE?.promptTokens}`);
  check('ENHANCE / SIMPLE 没有路由开销',
    Number(byType.ENHANCE?.routePromptTokens || 0) === 0
    && Number(byType.SIMPLE?.routePromptTokens || 0) === 0);

  const bogus = await call('POST', CHAT_API, { body: { content: qSame, chatType: 'NOT_A_TYPE' }, token });
  check('★ 非法 chatType 不报错、兜底到默认策略（配置错误不该让服务不可用）',
    bogus.status === 200 && bogus.json?.data?.chatType === 'ROUTE',
    `chatType=${bogus.json?.data?.chatType}`);

  const afterOverride = await call('POST', CHAT_API, { body: { content: qSame }, token });
  check('请求级覆盖是"一次性"的（不带参数时仍用配置默认策略）',
    afterOverride.json?.data?.chatType === 'ROUTE',
    `chatType=${afterOverride.json?.data?.chatType}`);

  // ==================== 10. Prompt 外置（M6-2） ====================
  section('10. ★ 提示词外置（Nacos 热更新 + 三级兜底）');

  const promptRes = await call('GET', '/ais/chat/prompts', { token });
  const promptMap = promptRes.json?.data || {};
  check('提示词来源接口返回全部 6 项（5 个 Agent + 基线）',
    Object.keys(promptMap).length === 6, `项数=${Object.keys(promptMap).length}`);
  check('★ 每项都有明确的来源（能查"当前到底用的哪一份"）',
    Object.values(promptMap).every((v) => v && v.length > 0),
    JSON.stringify(promptMap));
  check('提示词内容不直接返回（含业务规则，不该给任何登录用户看）',
    !JSON.stringify(promptMap).includes('你是 FitMind'),
    '接口只返回来源与长度');

  // 提示词真的进了 prompt —— 用 Mock 的 prompt 快照做直接证据
  await call('POST', CHAT_API, { body: { content: '提示词注入验证', chatType: 'SIMPLE' }, token });
  const snap0 = redis('LINDEX', MOCK_SNAPSHOT_KEY, '0') || '';
  check('★ 提示词（system 人设）确实被送进了模型 prompt',
    snap0.includes('FitMind'), `快照前 40 字=${snap0.slice(0, 40)}`);

  // ★ Nacos 热更新：发布 → 等 TTL → 生效 → 清理
  //   ⚠️ 等待时长取决于 fm.ai.prompt.nacos.refresh-interval-ms（默认 30s）。
  //      验收建议用 AIGC_PROMPT_REFRESH_MS=5000 启动服务，否则这里要等 35 秒。
  const NACOS_MARK = '【验收标记-' + Date.now() + '】';
  try {
    const tokenRes = await fetch('http://localhost:8848/nacos/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'username=nacos&password=nacos',
    });
    const nacosToken = (await tokenRes.json()).accessToken;
    const publish = await fetch('http://localhost:8848/nacos/v1/cs/configs', {
      method: 'POST',
      body: new URLSearchParams({
        dataId: 'fm-aigc-prompt-simple', group: 'DEFAULT_GROUP', tenant: 'public',
        type: 'text', accessToken: nacosToken,
        content: NACOS_MARK + '你是 FitMind 的 AI 健身教练，这条来自 Nacos 配置中心。',
      }),
    });
    check('能向 Nacos 发布提示词配置', (await publish.text()).trim() === 'true');

    // 等 TTL 过期（默认 30s → 等 35s）
    const waitMs = 35_000;
    console.log(`    （等 ${waitMs / 1000} 秒让提示词缓存过期…）`);
    await sleep(waitMs);

    const after = await call('GET', '/ais/chat/prompts', { token });
    const simpleSrc = after.json?.data?.simple || '';
    check('★★ 改 Nacos 后提示词在 TTL 内生效（不需要重启服务）',
      simpleSrc.includes('nacos'), `simple 来源=${simpleSrc}`);

    await call('POST', CHAT_API, { body: { content: '验证 Nacos 生效', chatType: 'SIMPLE' }, token });
    const snap1 = redis('LINDEX', MOCK_SNAPSHOT_KEY, '0') || '';
    check('★★ Nacos 上的提示词内容确实进了 prompt（快照含验收标记）',
      snap1.includes(NACOS_MARK), `快照前 40 字=${snap1.slice(0, 40)}`);

    // 清理：删掉验收用的配置，让环境回到"本地提示词"状态
    const del = await fetch('http://localhost:8848/nacos/v1/cs/configs?' + new URLSearchParams({
      dataId: 'fm-aigc-prompt-simple', group: 'DEFAULT_GROUP', tenant: 'public', accessToken: nacosToken,
    }), { method: 'DELETE' });
    check('验收配置已从 Nacos 清理', (await del.text()).trim() === 'true');
  } catch (e) {
    check('Nacos 提示词链路验证（连接 Nacos 失败）', false, String(e).slice(0, 120));
  }

  // ==================== 11. RAG 全链路（M6-3） ====================
  section('11. ★ RAG：语料 → 分块 → 向量化 → 检索 → 引用溯源');

  const adminToken = await staffLogin(ADMIN_PHONE);
  check('管理员登录成功（知识库管理端需要管理员身份）', !!adminToken);

  const kbRes = await call('GET', '/ais/admin/knowledge/status', { token: adminToken });
  const kb = kbRes.json?.data;
  check('知识库状态接口可用', !!kb, `status=${kbRes.status}`);
  check('★ 知识库索引已建立且有向量', Number(kb?.vectorCount) > 0, `条数=${kb?.vectorCount}`);
  check('★★ 索引维度与当前 embedding 配置一致（防"换了模型但没重建索引"这类静默降级）',
    Number(kb?.mappedDimensions) === Number(kb?.configuredDimensions),
    `索引=${kb?.mappedDimensions} 配置=${kb?.configuredDimensions}`);
  check('索引名自带模型与维度标识（维度是索引 schema 的一部分）',
    String(kb?.indexName || '').includes('embedding') && String(kb?.indexName || '').endsWith('1024'),
    `indexName=${kb?.indexName}`);
  check('★ 语料被真的分成了多块（不是"一篇一块"也不是"整库一块"）',
    Number(kb?.chunkCount) > Number(kb?.corpusDocs),
    `${kb?.corpusDocs} 篇 → ${kb?.chunkCount} 块`);

  // 非管理员访问管理端：必须被拦
  const forbidden = await call('GET', '/ais/admin/knowledge/status', { token });
  check('★ 学员访问知识库管理端被拒（重建索引是运维级操作，不能人人可点）',
    forbidden.status === 403 || forbidden.json?.code === 403,
    `status=${forbidden.status} code=${forbidden.json?.code}`);

  // ---- 一次真实的知识问答 ----
  const ragQ = '增肌期每天该吃多少蛋白质';
  const ragAns = (await call('POST', CHAT_API,
    { body: { content: ragQ, chatType: 'ENHANCE' }, token })).json?.data;
  const rag = ragAns?.rag || {};
  check('★ 知识问答触发了检索（ragMode=rag）', ragAns?.ragMode === 'rag', `ragMode=${ragAns?.ragMode}`);
  check('★ 检索命中并注入 prompt', Number(ragAns?.ragHits) > 0, `hits=${ragAns?.ragHits}`);
  check('引用来源结构化返回（title / section / docId / score 齐全）',
    Array.isArray(rag.sources) && rag.sources.length > 0
      && rag.sources.every((s) => s.title && s.docId && s.score !== undefined),
    JSON.stringify(rag.sources?.[0] || {}).slice(0, 140));
  check('★★ 溯源指向正确的语料（问蛋白质 → 命中 protein-intake）',
    (rag.sources || []).some((s) => s.docId === '05-protein-intake'),
    `来源=${(rag.sources || []).map((s) => s.docId).join(',')}`);
  check('溯源带章节定位（用户能自己核对到具体小节，而不是只知道某篇）',
    (rag.sources || []).some((s) => String(s.section || '').length > 0),
    `section=${rag.sources?.[0]?.section}`);
  check('检索耗时被记录（改检索参数后能看出代价）',
    Number(ragAns?.ragSearchMs) >= 0 && Number(ragAns?.ragSearchMs) < 10000,
    `searchMs=${ragAns?.ragSearchMs}`);

  // ★★ 直接证据：语料原文确实进了 prompt
  const ragSnap = redis('LINDEX', MOCK_SNAPSHOT_KEY, '0') || '';
  check('★★ 检索到的语料原文确实进了 prompt（快照含【参考资料】块）',
    ragSnap.includes('【参考资料】'), `快照长度=${ragSnap.length}`);
  check('★★ prompt 的资料块带上了语料标题与正文（模型看得到出处与依据）',
    ragSnap.includes('蛋白质摄取') || ragSnap.includes('蛋白质摄入') || ragSnap.includes('每公斤体重'),
    ragSnap.slice(Math.max(0, ragSnap.indexOf('【参考资料】')), Math.max(0, ragSnap.indexOf('【参考资料】')) + 100));
  check('★ 资料块与提问在同一段（【参考资料】…【用户问题】结构正确）',
    ragSnap.includes('【用户问题】') && ragSnap.includes(ragQ));
  check('★ 资料内容拼在用户消息里、不写进 system 提示词（外部输入不该有系统级权威）',
    ragSnap.indexOf('【参考资料】') > 0
      && ragSnap.indexOf('【参考资料】') > ragSnap.indexOf('FitMind'),
    'system 人设在前、资料块在后 → 说明资料没被塞进 system');

  // ---- topK 真正生效（对比常见实现的 topK=999） ----
  const top1 = (await call('POST', CHAT_API,
    { body: { content: ragQ, chatType: 'ENHANCE', ragTopK: 1 }, token })).json?.data;
  const top5 = (await call('POST', CHAT_API,
    { body: { content: ragQ, chatType: 'ENHANCE', ragTopK: 5 }, token })).json?.data;
  check('★★ topK 生效：topK=1 时最多只给 1 条（不是"把整库塞进 prompt"）',
    Number(top1?.ragHits) <= 1, `topK=1 → hits=${top1?.ragHits}`);
  check('topK=5 时给的更多且不超过 5 条',
    Number(top5?.ragHits) > Number(top1?.ragHits) && Number(top5?.ragHits) <= 5,
    `topK=1 → ${top1?.ragHits} 条；topK=5 → ${top5?.ragHits} 条`);
  check('★ 注入内容长度有硬上限（成本护栏：分块参数被改大也不会把 prompt 打爆）',
    Number(top5?.rag?.contextChars || 0) <= Number(kb?.maxContextChars || 4000),
    `contextChars=${top5?.rag?.contextChars}`);

  // ---- 相似度阈值真正生效 ----
  const looseTh = (await call('POST', CHAT_API,
    { body: { content: ragQ, chatType: 'ENHANCE', ragThreshold: 0.1 }, token })).json?.data;
  const strictTh = (await call('POST', CHAT_API,
    { body: { content: ragQ, chatType: 'ENHANCE', ragThreshold: 0.99 }, token })).json?.data;
  check('★★ 相似度阈值生效：阈值 0.99 时几乎全部落空（宁可拒答，也不用不相关内容硬编）',
    Number(strictTh?.ragHits) === 0, `阈值0.99 → hits=${strictTh?.ragHits}`);
  check('阈值放宽到 0.1 时能命中（阈值不是"配了没生效"）',
    Number(looseTh?.ragHits) > 0, `阈值0.1 → hits=${looseTh?.ragHits}`);

  // ---- metadata 过滤（★ 方案里点名的断言） ----
  const byGoal = async (goal) => (await call('POST', CHAT_API,
    { body: { content: ragQ, chatType: 'ENHANCE', goal }, token })).json?.data;
  const fatLossAns = await byGoal('fat_loss');
  const muscleAns = await byGoal('muscle_gain');
  const fatDocs = (fatLossAns?.rag?.sources || []).map((s) => s.docId);
  const muscleDocs = (muscleAns?.rag?.sources || []).map((s) => s.docId);
  check('★★ metadata 过滤：减脂用户检索不到"增肌专属"文档（这比调 topK 有效得多）',
    fatLossAns?.ragMode === 'rag'
      && !fatDocs.includes('05-protein-intake') && !fatDocs.includes('04-muscle-gain'),
    `减脂目标命中=${fatDocs.join(',') || '无'}`);
  check('同一问题在增肌目标下能命中增肌文档（过滤没有把该给的也一起挡掉）',
    muscleDocs.includes('05-protein-intake'), `增肌目标命中=${muscleDocs.join(',') || '无'}`);
  check('★ 两种目标下检索结果确实不同（过滤生效而非"看起来配了"）',
    JSON.stringify(fatDocs) !== JSON.stringify(muscleDocs),
    `减脂=${fatDocs.join(',')} / 增肌=${muscleDocs.join(',')}`);

  // ---- 拒答 ----
  const offTopic = '怎么修汽车发动机';
  const refusal = (await call('POST', CHAT_API,
    { body: { content: offTopic, chatType: 'ENHANCE' }, token })).json?.data;
  check('★ 库外问题：检索命中 0 条', Number(refusal?.ragHits) === 0, `hits=${refusal?.ragHits}`);
  check('★★ 拒答：明确说不知道，而不是硬编一段健身内容',
    /知识库|不能|没法/.test(String(refusal?.content || '')),
    `回答=${String(refusal?.content || '').slice(0, 60)}`);
  const refusalSnap = redis('LINDEX', MOCK_SNAPSHOT_KEY, '0') || '';
  check('★ 无资料时 prompt 里带"没有检索到"的说明（拒答是提示词约束出来的，不是碰运气）',
    refusalSnap.includes('没有检索到任何相关的知识库资料'), refusalSnap.slice(0, 60));

  // ---- 混合检索可关闭（用于量化"关键词召回带来多少提升"） ----
  const vectorOnly = (await call('POST', CHAT_API,
    { body: { content: '训练时留余次数 RIR 是什么意思', chatType: 'ENHANCE', ragHybrid: false }, token })).json?.data;
  check('混合检索开关可用（能跑"向量 vs 混合"的对比实验）',
    vectorOnly?.rag?.hybrid === false && Number(vectorOnly?.ragHits) > 0,
    `hybrid=${vectorOnly?.rag?.hybrid} hits=${vectorOnly?.ragHits}`);

  // ---- 流式：引用来源的 PARAM 事件 ----
  const ragStream = await sseChat(ragQ, token);
  const names = ragStream.events.map((e) => e.name).join('→');
  const paramEvent = ragStream.events.find((e) => e.name === 'PARAM');
  check('★★ 引用来源通过 PARAM 事件下发（结构化，不靠模型在正文里写"依据《XX》"）',
    !!paramEvent && (JSON.parse(paramEvent.data).sources || []).length > 0,
    paramEvent ? paramEvent.data.slice(0, 110) : `事件=${names}`);
  const paramIdx = ragStream.events.findIndex((e) => e.name === 'PARAM');
  const dataIdx = ragStream.events.findIndex((e) => e.name === 'DATA');
  const stopIdx = ragStream.events.findIndex((e) => e.name === 'STOP');
  check('★ 事件顺序：DATA（正文）→ PARAM（引用）→ STOP（结束）',
    dataIdx < paramIdx && paramIdx < stopIdx, `序列=${names}`);

  const refusalStream = await sseChat(offTopic, token);
  check('★ 拒答时不下发 PARAM（没有依据就不该有"参考资料"卡片）',
    !refusalStream.events.some((e) => e.name === 'PARAM'),
    `事件=${refusalStream.events.map((e) => e.name).join(',')}`);

  // ---- 引用来源落库（历史记录可重渲染） ----
  const citedSession = ragStream.events.find((e) => e.name === 'STOP')
    ? JSON.parse(ragStream.events.find((e) => e.name === 'STOP').data).sessionId : null;
  const storedParams = sql(`SELECT params FROM fm_aigc.chat_message
    WHERE session_id='${citedSession}' AND role='assistant' AND params IS NOT NULL LIMIT 1`);
  check('★★ 引用来源落库（翻历史记录时"参考资料"卡片能重渲染）',
    storedParams.includes('"rag"') && storedParams.includes('title'),
    storedParams.slice(0, 110) || '（没有落库）');

  // ---- 知识库重建（管理端手动触发） ----
  const rebuild = await call('POST', '/ais/admin/knowledge/rebuild', { token: adminToken });
  const rb = rebuild.json?.data;
  check('★ 管理端可重建知识库索引', rebuild.json?.code === 200 && Number(rb?.chunkCount) > 0,
    `chunks=${rb?.chunkCount} costMs=${rb?.costMs}`);
  check('重建是确定性的：重建后块数与语料规模一致（id 由语料派生，重复重建不会越建越多）',
    Number(rb?.chunkCount) === Number(kb?.chunkCount),
    `重建前 ${kb?.chunkCount} → 重建后 ${rb?.chunkCount}`);
  const afterRebuild = (await call('POST', CHAT_API,
    { body: { content: ragQ, chatType: 'ENHANCE' }, token })).json?.data;
  check('重建后立刻能检索到（写完有 refresh，不是"要等一会才可见"）',
    Number(afterRebuild?.ragHits) > 0, `hits=${afterRebuild?.ragHits}`);

  // ==================== 12. 检索评估集（M6-3） ====================
  section('12. ★★ 检索评估集（把"调 RAG"从玄学变成工程）');

  let evalHit = 0;
  const evalMiss = [];
  for (const item of RAG_EVAL_SET) {
    const r = await call('POST', CHAT_API,
      { body: { content: item.q, chatType: 'ENHANCE' }, token });
    const docIds = (r.json?.data?.rag?.sources || []).map((s) => s.docId);
    if (docIds.includes(item.expectDoc)) {
      evalHit++;
    } else {
      evalMiss.push(`${item.q}（期望 ${item.expectDoc}，实际 ${docIds.join(',') || '无'}）`);
    }
  }
  const evalRate = evalHit / RAG_EVAL_SET.length;
  check(`★★ 固定问答集检索命中率 ≥ ${(RAG_EVAL_HIT_FLOOR * 100).toFixed(0)}%（${evalHit}/${RAG_EVAL_SET.length}）`,
    evalRate >= RAG_EVAL_HIT_FLOOR,
    evalMiss.length ? `未命中：${evalMiss.slice(0, 3).join(' / ')}` : '');

  let refusalOk = 0;
  for (const item of RAG_REFUSAL_SET) {
    const r = await call('POST', CHAT_API,
      { body: { content: item.q, chatType: 'ENHANCE' }, token });
    if ((r.json?.data?.rag?.sources || []).length === 0) refusalOk++;
  }
  check(`★★ 领域外问题的拒答率 100%（${refusalOk}/${RAG_REFUSAL_SET.length}）`,
    refusalOk === RAG_REFUSAL_SET.length, `拒答成功 ${refusalOk} 条`);

  // ★ 同一套评估集、两种检索方式 → "混合检索带来多少提升"变成一个数字
  let vectorHit = 0;
  for (const item of RAG_EVAL_SET) {
    const r = await call('POST', CHAT_API,
      { body: { content: item.q, chatType: 'ENHANCE', ragHybrid: false }, token });
    const docIds = (r.json?.data?.rag?.sources || []).map((s) => s.docId);
    if (docIds.includes(item.expectDoc)) vectorHit++;
  }
  check('★★ 混合检索 vs 纯向量：可对比、可量化（同一进程内跑出来的数字，不是估计）',
    vectorHit <= evalHit,
    `纯向量 ${vectorHit}/${RAG_EVAL_SET.length} vs 混合 ${evalHit}/${RAG_EVAL_SET.length}`);

  // ==================== 13. 清理与自检 ====================
  section('13. 清理与自检');
  aiCleanup();
  const leftSession = sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE user_id=${STUDENT_USER_ID}`);
  const leftMsg = sql(`SELECT COUNT(*) FROM fm_aigc.chat_message WHERE user_id=${STUDENT_USER_ID}`);
  const leftSnap = redis('LLEN', MOCK_SNAPSHOT_KEY);
  check('会话无残留', leftSession === '0', `剩余=${leftSession}`);
  check('消息无残留', leftMsg === '0', `剩余=${leftMsg}`);
  check('Redis prompt 快照已清', Number(leftSnap || 0) === 0, `剩余=${leftSnap}`);

  return summary();
}

main()
  .catch((e) => {
    console.error('\n脚本异常终止：', e);
    try { aiCleanup(); } catch { /* ignore */ }
    summary();
  });
