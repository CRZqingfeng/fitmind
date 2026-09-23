#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M3-3b 学习笔记 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：笔记 CRUD、隐私、采集/取消采集（含幂等）、笔记列表的
 *       "我是否采集过"判准、被采集次数、笔记点赞断链接通、
 *       管理端（角色校验 / 名称补齐 / 采集者名单 / 折叠）、积分联动、鉴权
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、
 *   fm-search(8085)、fm-learning(8086)、fm-remark(8087)、
 *   fm-gateway(10010)、xxl-job-admin(9090)
 *
 * 用法：node scripts/accept-m3-3b.mjs
 *
 * 本脚本最值钱的四项：
 *   1) ★ 修掉常见实现的采集 JOIN bug：别人采集过 ≠ 我采集过；
 *         同一条笔记被 2 人采集，公共列表仍只有 1 行（常见实现会出现重复行）
 *   2) ★ 接通常见实现的「笔记点赞」断链：note.liked_times 真的会被同步
 *   3) ★ 采集副本强制私有 + 采集幂等（常见实现重复点会插多份副本）
 *   4) ★ 管理端角色校验：学员 token 调 /ls/admin/notes/** 必须 403
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
  console.log(' FitMind M3-3b 学习笔记 —— 端到端验收');
  console.log('========================================');

  // ==================== 0. XXL-JOB ====================
  section('0. XXL-JOB 调度中心');
  const cookie = await xxlLogin();
  check('admin 登录成功', !!cookie);
  const jobs = cookie ? await xxlPost(cookie, '/jobinfo/pageList', { jobGroup: 0, triggerStatus: -1, start: 0, length: 50 }) : null;
  const syncJob = (jobs?.data || []).find((j) => j.executorHandler === 'likedTimesSyncJob');
  check('★ 点赞同步任务已预置', !!syncJob);
  // 验收期间暂停周期调度，避免它和"脏标还在吗"的断言抢时序
  const stopped = syncJob ? await xxlPost(cookie, '/jobinfo/stop', { id: syncJob.id }) : null;
  check('验收期间暂停周期调度（控制变量）', stopped?.code === 200, `code=${stopped?.code}`);

  // ==================== 1. 登录 ====================
  section('1. 登录获取三种角色 token');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功', typeof coachToken === 'string' && coachToken.length > 20);
  const adminToken = await staffLogin(ADMIN_PHONE);
  check('管理员登录成功', typeof adminToken === 'string' && adminToken.length > 20);
  if (!token || !coachToken || !adminToken) return summary();

  // ==================== 2. 准备课程 ====================
  section('2. 准备验收课程');
  const suffix = String(Date.now() % 1000000);
  const course = await buildCourse({
    token, name: `M3-3b笔记课${suffix}`, free: 1, price: 0, validDuration: 90,
  });
  check('验收课程已上架', !!(course?.courseId && course?.chapterId && course?.sectionIds?.length),
    JSON.stringify(course));
  if (!course?.courseId) return summary();
  const { courseId, chapterId } = course;
  const [s1, s2] = course.sectionIds;

  // ★ 隔离：清掉本课程的历史笔记 + 本用户笔记类积分 + 相关 Redis 点赞数据
  //   （多次运行会残留；积分还有每日上限，不清会顶到上限让断言失败）
  const oldNoteIds = sql(`SELECT id FROM fm_learning.note WHERE course_id=${courseId}`);
  if (oldNoteIds) {
    sql(`DELETE FROM fm_learning.note WHERE course_id=${courseId}`);
  }
  sql(`DELETE FROM fm_learning.points_record WHERE user_id IN (1,2) AND type=4`);
  redis('DEL', 'like:dirty:NOTE');

  // ==================== 3. 笔记 CRUD ====================
  section('3. 笔记：保存 / 修改 / 删除 / 列表');
  const save1 = await call('POST', '/ls/notes', {
    token,
    body: { courseId, chapterId, sectionId: s1, noteMoment: 75, content: '肩胛骨收紧再推，发力点立刻不一样', isPrivate: false },
  });
  check('学员保存公开笔记成功', save1.json?.code === 200, `msg=${save1.json?.msg}`);
  const n1 = sql(`SELECT id FROM fm_learning.note WHERE course_id=${courseId} AND user_id=1 AND is_gathered=0 ORDER BY id DESC LIMIT 1`);
  const n1Row = sql(`SELECT user_id, author_id, is_private, is_gathered, hidden, liked_times, note_moment FROM fm_learning.note WHERE id=${n1}`);
  check('★ 直查 DB：公开笔记 is_private=0 / is_gathered=0 / hidden=0 / liked_times=0 / moment=75',
    n1Row.replace(/\t/g, ' ') === '1 1 0 0 0 0 75', `row=${JSON.stringify(n1Row)}`);

  const save2 = await call('POST', '/ls/notes', {
    token,
    body: { courseId, chapterId, sectionId: s1, noteMoment: 30, content: '隐私笔记：今天状态不好，减量', isPrivate: true },
  });
  check('保存隐私笔记成功', save2.json?.code === 200);
  const n2 = sql(`SELECT id FROM fm_learning.note WHERE course_id=${courseId} AND user_id=1 AND is_private=1 ORDER BY id DESC LIMIT 1`);
  check('隐私笔记落库 is_private=1', sql(`SELECT is_private FROM fm_learning.note WHERE id=${n2}`) === '1');

  const upd = await call('PUT', `/ls/notes/${n1}`, {
    token, body: { courseId, chapterId, sectionId: s1, noteMoment: 75, content: '改一下：先沉肩再推', isPrivate: false },
  });
  check('修改自己的笔记成功', upd.json?.code === 200, `msg=${upd.json?.msg}`);
  check('内容已更新', sql(`SELECT content FROM fm_learning.note WHERE id=${n1}`) === '改一下：先沉肩再推');
  const updOther = await call('PUT', `/ls/notes/${n1}`, {
    token: coachToken, body: { courseId, chapterId, sectionId: s1, noteMoment: 75, content: '改别人的', isPrivate: false },
  });
  check('★ 修改他人笔记被拒', updOther.json?.code !== 200, `code=${updOther.json?.code}`);
  const delOther = await call('DELETE', `/ls/notes/${n1}`, { token: coachToken });
  check('★ 删除他人笔记被拒', delOther.json?.code !== 200, `code=${delOther.json?.code}`);

  const mineList = await call('GET', `/ls/notes/page?courseId=${courseId}&onlyMine=true&pageNo=1&pageSize=10`, { token });
  check('我的笔记列表命中 2 条（公开 + 隐私）', (mineList.json?.data?.list || []).length === 2,
    `size=${(mineList.json?.data?.list || []).length}`);
  const pubList = await call('GET', `/ls/notes/page?sectionId=${s1}&pageNo=1&pageSize=10`, { token: coachToken });
  check('★ 公共列表只看到 1 条公开笔记（隐私笔记不出现在他人列表）',
    pubList.json?.code === 200 && (pubList.json?.data?.list || []).length === 1,
    `status=${pubList.status}, size=${(pubList.json?.data?.list || []).length}`);

  // ==================== 4. ★ 采集 ====================
  section('4. ★ 采集（含隐私、幂等、"我是否采集过"判准）');
  const gather = await call('POST', `/ls/notes/gathers/${n1}`, { token: coachToken });
  check('教练采集学员的公开笔记成功', gather.json?.code === 200, `msg=${gather.json?.msg}`);
  const g1 = sql(`SELECT id FROM fm_learning.note WHERE gathered_note_id=${n1} AND user_id=2 ORDER BY id DESC LIMIT 1`);
  const g1Row = sql(`SELECT user_id, author_id, is_private, is_gathered, gathered_note_id FROM fm_learning.note WHERE id=${g1}`);
  check('★ 副本三要素：user_id=采集者 / author_id=原作者 / is_gathered=1 / gathered_note_id 指向原笔记',
    g1Row.replace(/\t/g, ' ') === `2 1 1 1 ${n1}`, `row=${JSON.stringify(g1Row)}`);
  check('★ 采集副本强制私有（is_private=1，不能绕过原作者隐私设置）',
    sql(`SELECT is_private FROM fm_learning.note WHERE id=${g1}`) === '1');

  const gatherAgain = await call('POST', `/ls/notes/gathers/${n1}`, { token: coachToken });
  check('★ 重复采集被拒（幂等；常见实现没做这层，重复点会插多份副本）',
    gatherAgain.json?.code !== 200, `code=${gatherAgain.json?.code}`);
  check('副本仍只有 1 份', sql(`SELECT COUNT(*) FROM fm_learning.note WHERE gathered_note_id=${n1}`) === '1');

  const gatherPrivate = await call('POST', `/ls/notes/gathers/${n2}`, { token: coachToken });
  check('★ 采集隐私笔记被拒（否则就是绕过隐私的入口）',
    gatherPrivate.json?.code !== 200, `code=${gatherPrivate.json?.code}`);

  // ★ 采集判断：教练视角 true / 学员视角 false
  const coachView = await call('GET', `/ls/notes/page?sectionId=${s1}&pageNo=1&pageSize=10`, { token: coachToken });
  const coachSee = (coachView.json?.data?.list || []).find((v) => String(v.id) === String(n1));
  check('★ 教练视角 gatheredByMe = true（我采集过这条）', coachSee?.gatheredByMe === true,
    `gatheredByMe=${coachSee?.gatheredByMe}`);
  const ownerView = await call('GET', `/ls/notes/page?sectionId=${s1}&pageNo=1&pageSize=10`, { token });
  const ownerSee = (ownerView.json?.data?.list || []).find((v) => String(v.id) === String(n1));
  check('★ 原作者视角 gatheredByMe = false（别人采集过 ≠ 我采集过）',
    ownerSee?.gatheredByMe === false, `gatheredByMe=${ownerSee?.gatheredByMe}`);

  // 第二个人也采集 → 公共列表仍应只有 1 行
  const gather2 = await call('POST', `/ls/notes/gathers/${n1}`, { token: adminToken });
  check('管理员（第三方）也采集成功', gather2.json?.code === 200);
  const after2 = await call('GET', `/ls/notes/page?sectionId=${s1}&pageNo=1&pageSize=10`, { token: coachToken });
  check('★ 同一笔记被 2 人采集，公共列表仍只有 1 行（修常见实现重复行 bug）',
    (after2.json?.data?.list || []).length === 1, `size=${(after2.json?.data?.list || []).length}`);
  check('★ 该笔记被采集次数 = 2（子查询统计）',
    sql(`SELECT COUNT(*) FROM fm_learning.note WHERE gathered_note_id=${n1}`) === '2');

  // 采集副本不能改成公开
  const unprivate = await call('PUT', `/ls/notes/${g1}`, {
    token: coachToken, body: { courseId, chapterId, sectionId: s1, noteMoment: 75, content: '想改成公开', isPrivate: false },
  });
  check('★ 采集副本不能改成公开（is_private 被强制为 true）',
    unprivate.json?.code === 200 && sql(`SELECT is_private FROM fm_learning.note WHERE id=${g1}`) === '1',
    `is_private=${sql(`SELECT is_private FROM fm_learning.note WHERE id=${g1}`)}`);

  // 取消采集
  await call('DELETE', `/ls/notes/gathers/${n1}`, { token: coachToken });
  await sleep(600);
  check('★ 取消采集 → 我的副本被删（按 gathered_note_id 匹配）',
    sql(`SELECT COUNT(*) FROM fm_learning.note WHERE gathered_note_id=${n1} AND user_id=2`) === '0');
  check('别人的副本不受影响（管理员那份还在）',
    sql(`SELECT COUNT(*) FROM fm_learning.note WHERE gathered_note_id=${n1} AND user_id=3`) === '1');

  // ==================== 5. 排序 ====================
  section('5. 单动作内按视频时间点排序');
  const sorted = await call('GET', `/ls/notes/page?sectionId=${s1}&onlyMine=true&pageNo=1&pageSize=10`, { token });
  const moments = (sorted.json?.data?.list || []).map((v) => v.noteMoment);
  check('★ 单动作内按 note_moment 升序（30 在前、75 在后，方便跟着视频复习）',
    moments.length === 2 && moments[0] === 30 && moments[1] === 75, `moments=${JSON.stringify(moments)}`);

  // ==================== 6. ★ 笔记点赞（接通常见实现断链） ====================
  section('6. ★ 笔记点赞：接通常见实现的断链');
  const noteSetKey = `like:set:NOTE:${n1}`;
  sql(`DELETE FROM fm_remark.liked_record WHERE biz_type='NOTE' AND biz_id=${n1}`);
  redis('DEL', noteSetKey, 'like:dirty:NOTE');
  sql(`UPDATE fm_learning.note SET liked_times = 0 WHERE id = ${n1}`);

  const likeNote = await call('POST', '/rs/remarks/likes', { token: coachToken, body: { bizId: n1, bizType: 'NOTE', liked: true } });
  check('教练给笔记点赞成功', likeNote.json?.code === 200, `msg=${likeNote.json?.msg}`);
  check('★ Redis：like:set:NOTE:{id} 含该用户（key 带 bizType，与 QA 隔离）',
    redis('SISMEMBER', noteSetKey, '2') === '1');
  check('★ 点赞明细落库（biz_type=NOTE）',
    sql(`SELECT COUNT(*) FROM fm_remark.liked_record WHERE biz_type='NOTE' AND biz_id=${n1}`) === '1');

  const mqBefore = exchangePublishCount('like.record.topic');
  await xxlPost(cookie, '/jobinfo/trigger', { id: syncJob?.id, executorParam: '' });
  let mqAfter = mqBefore;
  for (let i = 0; i < 8 && mqAfter <= mqBefore; i++) {
    await sleep(1500);
    mqAfter = exchangePublishCount('like.record.topic');
  }
  check('★ 触发同步任务后交换机计数增加（NOTE.times.changed 真的发出去了）',
    mqBefore !== null && mqAfter !== null && mqAfter > mqBefore, `${mqBefore} → ${mqAfter}`);

  let noteLiked = '';
  for (let i = 0; i < 10; i++) {
    noteLiked = sql(`SELECT liked_times FROM fm_learning.note WHERE id=${n1}`);
    if (noteLiked === '1') break;
    await sleep(1200);
  }
  check('★ ★ 直查 DB：note.liked_times 被同步为 1（常见实现这条链路是断的）',
    noteLiked === '1', `liked_times=${noteLiked}`);

  const likedFlag = await call('GET', `/ls/notes/page?sectionId=${s1}&pageNo=1&pageSize=10`, { token: coachToken });
  const likedVo = (likedFlag.json?.data?.list || []).find((v) => String(v.id) === String(n1));
  check('★ 列表返回"我点过赞"状态（跨服务实时查 fm-remark）', likedVo?.liked === true, `liked=${likedVo?.liked}`);
  const notLiked = await call('GET', `/ls/notes/page?sectionId=${s1}&pageNo=1&pageSize=10`, { token });
  const notLikedVo = (notLiked.json?.data?.list || []).find((v) => String(v.id) === String(n1));
  check('未点赞的用户 liked = false', notLikedVo?.liked === false, `liked=${notLikedVo?.liked}`);

  // 取消点赞 → 覆盖回 0
  await call('POST', '/rs/remarks/likes', { token: coachToken, body: { bizId: n1, bizType: 'NOTE', liked: false } });
  await xxlPost(cookie, '/jobinfo/trigger', { id: syncJob?.id, executorParam: '' });
  let noteAfterUnlike = '';
  for (let i = 0; i < 10; i++) {
    await sleep(1200);
    noteAfterUnlike = sql(`SELECT liked_times FROM fm_learning.note WHERE id=${n1}`);
    if (noteAfterUnlike === '0') break;
  }
  check('★ 取消点赞后同步为 0（覆盖语义，不是减一）', noteAfterUnlike === '0', `liked_times=${noteAfterUnlike}`);

  // ==================== 7. 管理端 ====================
  section('7. 管理端：角色校验与数据补齐');
  const studentAdmin = await call('GET', '/ls/admin/notes/page?pageNo=1&pageSize=5', { token, noAdminSwap: true });
  check('★ 学员 token 调 /ls/admin/notes/** → 403', studentAdmin.status === 403, `status=${studentAdmin.status}`);
  const adminPage = await call('GET', `/ls/admin/notes/page?courseId=${courseId}&pageNo=1&pageSize=10`, { token: adminToken });
  check('管理员正常访问', adminPage.status === 200 && adminPage.json?.code === 200, `status=${adminPage.status}`);
  const adminVo = (adminPage.json?.data?.list || []).find((v) => String(v.id) === String(n1));
  check('★ 管理端补齐课程名 / 训练日名 / 动作名 / 作者名',
    adminVo?.courseName === `M3-3b笔记课${suffix}` && adminVo?.chapterName === 'Day 1 胸部'
      && adminVo?.sectionName === '哑铃卧推' && adminVo?.authorName === '测试学员',
    `course=${adminVo?.courseName}, chapter=${adminVo?.chapterName}, section=${adminVo?.sectionName}, author=${adminVo?.authorName}`);
  check('★ 管理端分页带"被采集次数"',
    adminVo?.usedTimes === 1, `usedTimes=${adminVo?.usedTimes}`);

  const detail = await call('GET', `/ls/admin/notes/${n1}`, { token: adminToken });
  check('★ 管理端详情含作者手机号',
    detail.json?.data?.authorPhone === STUDENT_PHONE, `phone=${detail.json?.data?.authorPhone}`);
  check('★ 管理端详情含采集者名单（管理员那份）',
    Array.isArray(detail.json?.data?.gathers) && detail.json.data.gathers.length === 1,
    `gathers=${JSON.stringify(detail.json?.data?.gathers)}`);

  // 折叠
  await call('PUT', `/ls/admin/notes/${n1}/hidden/true`, { token: adminToken });
  const afterHidden = await call('GET', `/ls/notes/page?sectionId=${s1}&pageNo=1&pageSize=10`, { token: coachToken });
  // ⚠️ "看不到"类断言必须同时校验 code：请求 401/500 时列表也是空的，会假通过
  check('★ 折叠后公共列表看不到',
    afterHidden.json?.code === 200 && !(afterHidden.json?.data?.list || []).some((v) => String(v.id) === String(n1)),
    `status=${afterHidden.status}, code=${afterHidden.json?.code}`);
  const adminStill = await call('GET', `/ls/admin/notes/page?courseId=${courseId}&pageNo=1&pageSize=10`, { token: adminToken });
  check('★ 折叠后管理端仍可见（含被折叠的）',
    (adminStill.json?.data?.list || []).some((v) => String(v.id) === String(n1)));
  const gatherHidden = await call('POST', `/ls/notes/gathers/${n1}`, { token: coachToken });
  check('★ 被折叠的笔记不能再被采集', gatherHidden.json?.code !== 200, `code=${gatherHidden.json?.code}`);
  await call('PUT', `/ls/admin/notes/${n1}/hidden/false`, { token: adminToken });

  // ==================== 8. 积分联动 ====================
  section('8. 积分联动');
  let writeNotePoints = '';
  for (let i = 0; i < 8; i++) {
    await sleep(1200);
    writeNotePoints = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=1 AND type=4 AND biz_id='${n1}'`);
    if (writeNotePoints.replace(/\t/g, ' ') === '1 3') break;
  }
  check('★ 写笔记送 3 分（type=NOTE，bizId=笔记id）',
    writeNotePoints.replace(/\t/g, ' ') === '1 3', `count/sum=${JSON.stringify(writeNotePoints)}`);
  const gatherPoints = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=3 AND type=4 AND biz_id=(SELECT id FROM fm_learning.note WHERE gathered_note_id=${n1} AND user_id=3 LIMIT 1)`);
  check('★ 被采集送 2 分（bizId=采集副本id）',
    gatherPoints.replace(/\t/g, ' ') === '1 2', `count/sum=${JSON.stringify(gatherPoints)}`);

  // ==================== 9. 鉴权 ====================
  section('9. 鉴权');
  const noToken = await call('GET', `/ls/notes/page?courseId=${courseId}`);
  check('未登录访问笔记列表被拦截 401', noToken.status === 401, `status=${noToken.status}`);

  // ==================== 10. 清理 ====================
  section('10. 清理测试数据');
  // ⚠️ 清理**必须检查返回码**（同 m3-3a：不检查就会静默留垃圾，污染后续脚本的检索类断言）
  const delRes = await call('DELETE', `/cs/courses/delete/${courseId}`, { token: coachToken });
  check('★ 清理：课程已真正删除', delRes.json?.code === 200
    && sql(`SELECT COUNT(*) FROM fm_course.course WHERE id=${courseId}`) === '0',
    `msg=${delRes.json?.msg}`);
  if (course.cateId) await call('DELETE', `/cs/categorys/${course.cateId}`, { token: coachToken });
  const noteIds = sql(`SELECT GROUP_CONCAT(id) FROM fm_learning.note WHERE course_id=${courseId}`);
  if (noteIds) {
    sql(`DELETE FROM fm_remark.liked_record WHERE biz_type='NOTE' AND biz_id IN (${noteIds})`);
    for (const id of noteIds.split(',')) redis('DEL', `like:set:NOTE:${id}`);
  }
  sql(`DELETE FROM fm_learning.note WHERE course_id=${courseId}`);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id IN (1,2,3) AND type=4`);
  redis('DEL', 'like:dirty:NOTE');
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
