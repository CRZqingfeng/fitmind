#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M2-2c 动作小测 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：题目 CRUD 与题型校验、节点挂题（全量覆盖 / 类型校验）、
 *       目录树题目数、上架下架同步、★ 学员拉题不下发答案、
 *       ★ 服务端判分（多选顺序/去重等价）、★ 答对得积分的幂等、级联删除
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、
 *   fm-search(8085)、fm-learning(8086)、fm-remark(8087)、
 *   fm-gateway(10010)、xxl-job-admin(9090)
 *
 * 用法：node scripts/accept-m2-2c.mjs
 *
 * 本脚本最值钱的四项：
 *   1) ★ 拉题接口的返回字段集合**恰好**是 6 个无答案字段——
 *         「把答案藏起来」和「结构上就没有答案」是两回事
 *   2) ★ 多选题按集合判分：答案 "1,3"，学员填 "3,1" 和 "3,1,3" 都算对
 *   3) ★ 积分幂等：同一道题重复答对，points_record 明细仍只有 1 行
 *   4) ★ 目录树的题目数必须跟目录走同一张表（上架前查草稿、上架后查正式），
 *         取错表就会「明明配了题，列表显示 0」
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
 * 建课（**不上架**）——题目要在上架前配置好，才能验证「上架时随课程一起同步」。
 *
 * 目录：1 个训练日 + 2 个训练动作。
 */
async function buildCourseDraft({ token, name, suffix }) {
  const cateName = `M2-2c分类${suffix}`;
  await call('POST', '/cs/categorys/add', { token, body: { name: cateName, parentId: 0 } });
  const cates = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cates.json?.data || []).find((c) => c.name === cateName);
  if (!cate) return null;

  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name, courseType: 1, firstCateId: cate.id, free: 1, price: 0,
      goalType: 1, trainingLevel: 2, totalDays: 21, validDuration: 90,
      equipment: '哑铃', courseIntroduce: 'M2-2c 动作小测验收用课程',
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

  // 上架要求每个训练动作都绑视频
  const buf = Buffer.from(`m2-2c-video-${Date.now()}`);
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
  // mediaId 要带出去：验收中途会「重存目录」来测孤儿关联清理，
  // 而重存时前端不传目录 id → 动作被当成新增 → mediaId 丢掉，必须重新绑一次
  return { courseId, chapterId, sectionIds, cateId: cate.id, mediaId, videoName: `${name}.mp4` };
}

/** 造一道题，返回题目 id */
async function addSubject(token, courseId, body) {
  const res = await call('POST', '/cs/subjects', { token, body: { courseId, ...body } });
  return { id: res.json?.data, res };
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M2-2c 动作小测 —— 端到端验收');
  console.log('========================================');

  // ==================== 1. 登录 ====================
  section('1. 登录获取三种角色 token');
  const token = await studentLogin(STUDENT_PHONE);
  check('学员登录成功', typeof token === 'string' && token.length > 20);
  const coachToken = await staffLogin(COACH_PHONE);
  check('教练登录成功', typeof coachToken === 'string' && coachToken.length > 20);
  const adminToken = await staffLogin(ADMIN_PHONE);
  check('管理员登录成功', typeof adminToken === 'string' && adminToken.length > 20);
  if (!token || !coachToken || !adminToken) return summary();

  // ==================== 2. 准备草稿课程 ====================
  section('2. 准备草稿课程（未上架）');
  const suffix = String(Date.now() % 1000000);
  const course = await buildCourseDraft({ token, name: `M2-2c小测课${suffix}`, suffix });
  check('草稿课程已建好（1 训练日 + 2 动作 + 动作已绑视频）',
    !!(course?.courseId && course?.chapterId && course?.sectionIds?.length === 2),
    JSON.stringify(course));
  if (!course?.courseId) return summary();
  const { courseId, chapterId } = course;
  const [s1, s2] = course.sectionIds;

  // 隔离：清掉本课程历史残留 + 本用户的答题积分（积分有每日上限，不清会顶到上限）
  sql(`DELETE FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`);
  sql(`DELETE FROM fm_course.course_cata_subject WHERE course_id=${courseId}`);
  sql(`DELETE FROM fm_course.subject WHERE course_id=${courseId}`);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=1 AND type=5`);

  // ==================== 3. 题目 CRUD 与题型校验 ====================
  section('3. 题目 CRUD 与题型校验（服务端）');

  const r1 = await addSubject(coachToken, courseId, {
    name: '哑铃卧推时，肩胛骨应该怎么做？',
    subjectType: 1, difficulty: 1,
    options: ['后缩并下沉', '放松前伸', '耸起'],
    answer: '1', analysis: '肩胛后缩下沉能给卧推提供稳定发力平台，避免肩关节代偿。', score: 5,
  });
  // ⚠️ id 现在是字符串（Long 全局序列化为 String，防雪花 id 精度丢失）
  check('★ 新增单选题成功', r1.id != null && `${r1.id}`.length > 0 && r1.res.json?.code === 200, `msg=${r1.res.json?.msg}`);
  const sub1 = r1.id;

  const r2 = await addSubject(coachToken, courseId, {
    name: '以下哪些是卧推时的常见错误？',
    subjectType: 2, difficulty: 2,
    options: ['耸肩', '肩胛后缩', '肘部过度外展'],
    answer: '1,3', analysis: '耸肩和肘部过度外展都会让肩关节承担额外剪切力。', score: 10,
  });
  check('新增多选题成功', r2.res.json?.code === 200, `msg=${r2.res.json?.msg}`);
  const sub2 = r2.id;

  const r3 = await addSubject(coachToken, courseId, {
    name: '训练前不需要热身，直接上重量即可。',
    subjectType: 4, difficulty: 1,
    answer: '2', analysis: '热身能提升肌肉温度与关节润滑，显著降低受伤风险。', score: 5,
  });
  check('新增判断题成功', r3.res.json?.code === 200, `msg=${r3.res.json?.msg}`);
  const sub3 = r3.id;

  const row1 = sql(`SELECT subject_type, answer, score, JSON_LENGTH(options) FROM fm_course.subject WHERE id=${sub1}`);
  check('★ 直查 DB：单选题与选项数组落库正确（JSON 列，不是 option1..option10 十列）',
    row1.replace(/\t/g, ' ') === '1 1 5 3', `row=${JSON.stringify(row1)}`);
  const row3 = sql(`SELECT subject_type, answer, options IS NULL FROM fm_course.subject WHERE id=${sub3}`);
  check('★ 判断题的 options 被强制清空（is null = 1）',
    row3.replace(/\t/g, ' ') === '4 2 1', `row=${JSON.stringify(row3)}`);

  // ---- 负向校验 ----
  const bad1 = await addSubject(coachToken, courseId, {
    name: '答案越界', subjectType: 1, difficulty: 1,
    options: ['A', 'B'], answer: '5',
  });
  check('★ 答案超出选项范围被拒', bad1.res.json?.code !== 200, `code=${bad1.res.json?.code}, msg=${bad1.res.json?.msg}`);

  const bad2 = await addSubject(coachToken, courseId, {
    name: '单选题给了两个答案', subjectType: 1, difficulty: 1,
    options: ['A', 'B', 'C'], answer: '1,2',
  });
  check('★ 单选题出现多个正确答案被拒', bad2.res.json?.code !== 200, `msg=${bad2.res.json?.msg}`);

  const bad3 = await addSubject(coachToken, courseId, {
    name: '判断题答案非法', subjectType: 4, difficulty: 1, answer: '3',
  });
  check('★ 判断题答案只能是 1/2，填 3 被拒', bad3.res.json?.code !== 200, `msg=${bad3.res.json?.msg}`);

  const bad4 = await addSubject(coachToken, courseId, {
    name: '只有一个选项', subjectType: 1, difficulty: 1, options: ['A'], answer: '1',
  });
  check('★ 选择题只有 1 个选项被拒', bad4.res.json?.code !== 200, `msg=${bad4.res.json?.msg}`);

  const bad5 = await addSubject(coachToken, courseId, {
    name: '选项含空白', subjectType: 1, difficulty: 1, options: ['A', '  '], answer: '1',
  });
  check('★ 选项内容为空白被拒', bad5.res.json?.code !== 200, `msg=${bad5.res.json?.msg}`);

  const bad6 = await addSubject(coachToken, courseId, {
    name: '难度非法', subjectType: 1, difficulty: 9, options: ['A', 'B'], answer: '1',
  });
  check('★ 难度值超出 1~3 被拒', bad6.res.json?.code !== 200, `msg=${bad6.res.json?.msg}`);

  // ---- 修改 / 查询 ----
  const upd = await call('PUT', `/cs/subjects/${sub1}`, {
    token: coachToken,
    body: {
      courseId, name: '哑铃卧推时，肩胛骨应该怎么做？（已修订）',
      subjectType: 1, difficulty: 1, options: ['后缩并下沉', '放松前伸', '耸起'],
      answer: '1', analysis: '修订后的解析', score: 5,
    },
  });
  check('修改题目成功', upd.json?.code === 200, `msg=${upd.json?.msg}`);
  check('题干已更新', sql(`SELECT name FROM fm_course.subject WHERE id=${sub1}`).includes('已修订'));

  const page = await call('GET', `/cs/subjects/page?courseId=${courseId}&pageNo=1&pageSize=10`, { token: coachToken });
  check('题目分页能查到 3 道题（负向用例都没落库）',
    page.json?.code === 200 && (page.json?.data?.list || []).length === 3,
    `size=${(page.json?.data?.list || []).length}`);

  const byType = await call('GET', `/cs/subjects/page?courseId=${courseId}&subjectType=2&pageNo=1&pageSize=10`, { token: coachToken });
  check('按题型筛选生效（多选题命中 1 道）', (byType.json?.data?.list || []).length === 1);

  const detail = await call('GET', `/cs/subjects/${sub2}`, { token: coachToken });
  check('★ 出题侧详情返回 answers 解析后的列表（[1,3]）',
    JSON.stringify(detail.json?.data?.answers) === '[1,3]', `answers=${JSON.stringify(detail.json?.data?.answers)}`);

  // 未被引用的题目可以删
  const tmp = await addSubject(coachToken, courseId, {
    name: '这道题待会会被删掉', subjectType: 1, difficulty: 1, options: ['A', 'B'], answer: '1',
  });
  const delOk = await call('DELETE', `/cs/subjects/${tmp.id}`, { token: coachToken });
  check('未被引用的题目可以删除', delOk.json?.code === 200, `msg=${delOk.json?.msg}`);

  // ==================== 4. 节点挂题 ====================
  section('4. 节点挂题（全量覆盖 + 类型校验）');

  const saveRel = await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken,
    body: [
      { cataId: s1, subjectIds: [sub1, sub2] },
      { cataId: s2, subjectIds: [sub3] },
    ],
  });
  check('保存节点-题目关联成功', saveRel.json?.code === 200, `msg=${saveRel.json?.msg}`);
  check('★ 直查 DB：草稿关联 3 条，c_index 从 1 开始',
    sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`) === '3'
      && sql(`SELECT c_index FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId} AND cata_id=${s1} ORDER BY c_index`).replace(/\r?\n/g, ',') === '1,2',
    `index=${sql(`SELECT c_index FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId} AND cata_id=${s1} ORDER BY c_index`)}`);

  const getRel = await call('GET', `/cs/courses/subjects/get/${courseId}`, { token: coachToken });
  check('★ 关联查询按节点分组，且带出节点名（哑铃卧推 / 哑铃飞鸟）',
    (getRel.json?.data || []).length === 2
      && (getRel.json?.data || []).every((g) => g.cataName && g.subjects?.length),
    JSON.stringify((getRel.json?.data || []).map((g) => `${g.cataName}:${g.subjects.length}`)));

  // ---- 负向 ----
  const badRel1 = await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: chapterId, subjectIds: [sub1] }],
  });
  check('★ 题目挂在「训练日」上被拒（只允许训练动作 / 测评节点）',
    badRel1.json?.code !== 200, `msg=${badRel1.json?.msg}`);

  const badRel2 = await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: 999999999, subjectIds: [sub1] }],
  });
  check('★ 挂到不存在的节点被拒', badRel2.json?.code !== 200, `msg=${badRel2.json?.msg}`);

  const badRel3 = await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: s1, subjectIds: [999999999] }],
  });
  check('★ 挂不属于本课程的题目被拒', badRel3.json?.code !== 200, `msg=${badRel3.json?.msg}`);

  const badRel4 = await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: s1, subjectIds: [sub1] }, { cataId: s1, subjectIds: [sub2] }],
  });
  check('★ 同一个节点重复提交被拒', badRel4.json?.code !== 200, `msg=${badRel4.json?.msg}`);

  // ---- 全量覆盖 ----
  await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: s1, subjectIds: [sub1, sub2] }, { cataId: s2, subjectIds: [sub3] }],
  });
  await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: s1, subjectIds: [sub1] }],
  });
  check('★ 全量覆盖：未提交的节点(s2)关联被清掉，总关联数变 1',
    sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`) === '1',
    `count=${sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`)}`);
  // 恢复成 3 条
  await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: s1, subjectIds: [sub1, sub2] }, { cataId: s2, subjectIds: [sub3] }],
  });

  // ---- 目录里删掉动作 → 孤儿关联被清理 ----
  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token: coachToken,
    body: [{ type: 1, name: 'Day 1 胸部', index: 1, sections: [{ type: 2, name: '哑铃卧推' }] }],
  });
  check('★ 目录里删掉动作后，它的题目关联被自动清理（不留孤儿数据）',
    sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId} AND cata_id=${s2}`) === '0',
    `s2 关联数=${sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId} AND cata_id=${s2}`)}`);
  // 把 s2 加回来并重挂题（先拿新的动作 id）
  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token: coachToken,
    body: [{ type: 1, name: 'Day 1 胸部', index: 1, sections: [{ type: 2, name: '哑铃卧推' }, { type: 2, name: '哑铃飞鸟' }] }],
  });
  const tree2 = await call('GET', `/cs/courses/catas/${courseId}`, { token: coachToken });
  const newSectionIds = (tree2.json?.data?.[0]?.sections || []).map((s) => s.id);
  const ns1 = newSectionIds[0];
  const ns2 = newSectionIds[1];
  // 重存目录会把已绑的动作参数保留（M2-2b 的机制），所以这里不用重新绑视频
  await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken, body: [{ cataId: ns1, subjectIds: [sub1, sub2] }, { cataId: ns2, subjectIds: [sub3] }],
  });
  check('重新挂题成功（动作 id 已换成新生成的）',
    sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`) === '3');

  // ⚠️ 重存目录不传目录 id → 动作被当成新增，mediaId 丢掉 → 必须重新绑视频，
  //    否则上架会卡在「训练动作【哑铃卧推】尚未上传训练视频」
  await call('POST', `/cs/courses/media/save/${courseId}`, {
    token: coachToken,
    body: newSectionIds.map((cataId) => ({
      cataId, mediaId: course.mediaId, videoName: course.videoName, mediaDuration: 180,
      sets: 4, reps: 10, restSeconds: 90, actionType: 1,
    })),
  });
  const mediaBack = sql(`SELECT COUNT(*) FROM fm_course.course_catalogue_draft WHERE course_id=${courseId} AND media_id IS NOT NULL`);
  check('重存目录后重新绑定视频（否则上架会因「动作还没传视频」被拒）', mediaBack === '2', `已绑视频动作数=${mediaBack}`);

  // ==================== 5. 目录树带题目数 ====================
  section('5. 目录树带题目数');
  const treeDraft = await call('GET', `/cs/courses/catas/${courseId}`, { token: coachToken });
  const draftSections = treeDraft.json?.data?.[0]?.sections || [];
  check('★ 编辑端目录树：两个动作的 subjectNum 分别是 2 和 1',
    draftSections.length === 2 && Number(draftSections[0].subjectNum) === 2 && Number(draftSections[1].subjectNum) === 1,
    `subjectNum=${draftSections.map((s) => s.subjectNum).join(',')}`);

  // ==================== 6. 上架同步 ====================
  section('6. 上架：题目关联随课程一起进正式表');

  await call('POST', '/cs/courses/teachers/save', {
    token: coachToken, body: { id: courseId, teachers: [{ id: COACH_USER_ID, isShow: true }] },
  });
  const beforeUp = await call('GET', `/cs/subjects/page?courseId=${courseId}&pageNo=1&pageSize=1`, { token: coachToken });
  const up = await call('POST', '/cs/courses/upShelf', { token: coachToken, body: { id: courseId } });
  check('课程上架成功', up.json?.code === 200, `msg=${up.json?.msg}`);
  check('★ 上架后草稿关联被清空（同一时刻数据只在一处）',
    sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`) === '0');
  check('★ 上架后正式表拿到 3 条关联',
    sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject WHERE course_id=${courseId}`) === '3');
  check('上架不影响题目本身',
    sql(`SELECT COUNT(*) FROM fm_course.subject WHERE course_id=${courseId}`) === '3',
    `before=${(beforeUp.json?.data?.list || []).length}`);

  const treeShelf = await call('GET', `/cs/courses/catas/${courseId}?see=true`, { token: coachToken });
  const shelfSections = treeShelf.json?.data?.[0]?.sections || [];
  check('★ 用户端目录树（读正式表）subjectNum 仍是 2 和 1 —— 题目数跟对了表',
    shelfSections.length === 2 && Number(shelfSections[0].subjectNum) === 2 && Number(shelfSections[1].subjectNum) === 1,
    `subjectNum=${shelfSections.map((s) => s.subjectNum).join(',')}`);

  // ==================== 7. ★ 学员拉题（不含答案） ====================
  section('7. ★ 学员拉题：结构上就没有答案字段');

  const noToken = await call('GET', `/cs/practices/${ns1}`);
  check('未登录拉题被拦截 401', noToken.status === 401, `status=${noToken.status}`);

  const practice = await call('GET', `/cs/practices/${ns1}`, { token });
  check('学员拉题成功，返回 2 道题', (practice.json?.data || []).length === 2,
    `size=${(practice.json?.data || []).length}`);
  const EXPECTED_KEYS = ['difficulty', 'id', 'name', 'options', 'score', 'subjectType'];
  check('★ 返回字段集合恰好是 6 个「无答案」字段',
    JSON.stringify(Object.keys(practice.json?.data?.[0] || {}).sort()) === JSON.stringify(EXPECTED_KEYS),
    `keys=${Object.keys(practice.json?.data?.[0] || {}).join(',')}`);
  check('★ 整个响应体里不含答案与解析字段（不是靠 @JsonIgnore 藏起来的）',
    !JSON.stringify(practice.text).includes('"answer"') && !JSON.stringify(practice.text).includes('"analysis"'));
  check('★ 也不含判断题的解析原文（"热身能提升肌肉温度" 不该出现在拉题响应里）',
    !practice.text.includes('热身能提升肌肉温度'));
  const emptyNode = await call('GET', `/cs/practices/${chapterId}`, { token });
  check('★ 拉取「没挂题」的节点会明确报错，而不是返回空数组',
    emptyNode.json?.code !== 200 && (emptyNode.json?.msg || '').includes('小测'),
    `msg=${emptyNode.json?.msg}`);

  // ==================== 8. ★ 判分 ====================
  section('8. ★ 服务端判分（多选按集合比较）');

  const fullRight = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub1, answer: '1' }, { subjectId: sub2, answer: '1,3' }] },
  });
  check('★ 全部答对：得分 = 满分 15', fullRight.json?.data?.score === 15 && fullRight.json?.data?.correctCount === 2,
    `score=${fullRight.json?.data?.score}, total=${fullRight.json?.data?.totalScore}`);
  check('判分结果带回正确答案与解析（提交后才下发）',
    fullRight.json?.data?.details?.every((d) => d.rightAnswer && d.analysis));

  const shuffled = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub2, answer: '3,1' }] },
  });
  const shuffledDetail = (shuffled.json?.data?.details || []).find((d) => String(d.subjectId) === String(sub2));
  check('★ 多选题答案顺序无关："3,1" 与正确答案 "1,3" 判为对', shuffledDetail?.correct === true,
    `yourAnswer=${shuffledDetail?.yourAnswer}, correct=${shuffledDetail?.correct}`);

  const dupAnswer = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub2, answer: '3,1,3' }] },
  });
  const dupDetail = (dupAnswer.json?.data?.details || []).find((d) => String(d.subjectId) === String(sub2));
  check('★ 重复填同一个选项去重后仍判对（"3,1,3" → "1,3"）', dupDetail?.correct === true,
    `yourAnswer=${dupDetail?.yourAnswer}`);

  const partial = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub1, answer: '1' }, { subjectId: sub2, answer: '1' }] },
  });
  check('★ 部分答对：只算答对那道题的分（5 分，多选那题 10 分不算）',
    partial.json?.data?.score === 5 && partial.json?.data?.correctCount === 1,
    `score=${partial.json?.data?.score}, correct=${partial.json?.data?.correctCount}`);

  const allWrong = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub1, answer: '3' }, { subjectId: sub2, answer: '2' }] },
  });
  check('全错得 0 分', allWrong.json?.data?.score === 0 && allWrong.json?.data?.correctCount === 0);

  const garbage = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub1, answer: 'abc' }] },
  });
  check('★ 乱填非数字答案不会 500（当作答错处理）',
    garbage.json?.code === 200 && garbage.json?.data?.score === 0, `code=${garbage.json?.code}`);

  const foreign = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: 999999999, answer: '1' }] },
  });
  check('提交不属于本节点的题目被忽略（不报错、不得分）',
    foreign.json?.code === 200 && foreign.json?.data?.score === 0);

  // 判断题（挂在第二个动作上）
  const judge = await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns2, answers: [{ subjectId: sub3, answer: '2' }] },
  });
  check('判断题答对得 5 分', judge.json?.data?.score === 5, `score=${judge.json?.data?.score}`);

  // 统计累加
  const times = sql(`SELECT answer_times, correct_times FROM fm_course.subject WHERE id=${sub1}`).replace(/\t/g, ' ');
  check('★ 作答统计是「累加」语义（每题每次提交都记，与幂等的积分不同）',
    Number(times.split(' ')[0]) >= 4, `answer_times/correct_times=${times}`);

  // ==================== 9. ★ 积分的幂等 ====================
  section('9. ★ 答对得积分的幂等');

  // ★ 先等前面的 MQ 全部消费完，再清空积分明细。
  //   否则上一节（判断题）的积分消息可能在清空之后才落地，
  //   凭空多出一条明细，让下面的计数断言随机失败——这正是 M2-3 踩过的
  //   "消息在事务提交前发出"同类问题的影子。
  await sleep(3000);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=1 AND type=5`);
  await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub1, answer: '1' }, { subjectId: sub2, answer: '1,3' }] },
  });
  const biz1 = `${courseId}:${ns1}:${sub1}`;
  let points1 = '';
  for (let i = 0; i < 10; i++) {
    await sleep(1200);
    points1 = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=1 AND type=5 AND biz_id='${biz1}'`);
    if (points1.replace(/\t/g, ' ') === '1 2') break;
  }
  check('★ 答对一题得 2 分（type=5 动作小测，bizId=courseId:cataId:subjectId）',
    points1.replace(/\t/g, ' ') === '1 2', `count/sum=${JSON.stringify(points1)}`);

  const total2 = sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE user_id=1 AND type=5`);
  check('一次全对提交得 2 题 × 2 分 = 2 条明细', total2 === '2', `count=${total2}`);

  // 重复提交同一份答案
  await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns1, answers: [{ subjectId: sub1, answer: '1' }, { subjectId: sub2, answer: '1,3' }] },
  });
  await sleep(2500);
  const after = sql(`SELECT COUNT(*), COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=1 AND type=5`);
  check('★ ★ 重复提交同一批题：明细仍 2 行、总分仍 4 分（唯一索引幂等）',
    after.replace(/\t/g, ' ') === '2 4', `count/sum=${JSON.stringify(after)}`);

  // 答错不得分（同样先等消息排空再清）
  await sleep(2500);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=1 AND type=5`);
  await call('POST', '/cs/practices/submit', {
    token, body: { cataId: ns2, answers: [{ subjectId: sub3, answer: '1' }] },
  });
  await sleep(2000);
  check('★ 答错不发积分事件（明细 0 行）',
    sql(`SELECT COUNT(*) FROM fm_learning.points_record WHERE user_id=1 AND type=5`) === '0');

  // ==================== 10. 下架 ====================
  section('10. 下架：题目关联回到草稿');
  await call('POST', '/cs/courses/downShelf', { token: coachToken, body: { id: courseId } });
  check('★ 下架后正式表关联被复制回草稿（3 条，题目 id 完整保留）',
    sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`) === '3'
      && sql(`SELECT GROUP_CONCAT(subject_id ORDER BY subject_id) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`).replace(/\t/g, ',') === [sub1, sub2, sub3].sort((a, b) => a - b).join(','),
    `draft=${sql(`SELECT GROUP_CONCAT(subject_id ORDER BY subject_id) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`)}`);
  const treeAfterDown = await call('GET', `/cs/courses/subjects/get/${courseId}`, { token: coachToken });
  check('下架后管理端仍能查到题目关联（编辑不丢数据）',
    (treeAfterDown.json?.data || []).length === 2);

  // ==================== 11. 删除已被引用的题目 ====================
  section('11. 删除保护');
  const delUsed = await call('DELETE', `/cs/subjects/${sub1}`, { token: coachToken });
  check('★ 已被课程引用的题目不能删（报错里带题干，便于定位）',
    delUsed.json?.code !== 200 && (delUsed.json?.msg || '').includes('已被课程引用'),
    `msg=${delUsed.json?.msg}`);

  // ==================== 12. 鉴权 ====================
  section('12. 鉴权');
  const noTokenPage = await call('GET', `/cs/subjects/page?courseId=${courseId}`);
  check('未登录访问题目分页 401', noTokenPage.status === 401, `status=${noTokenPage.status}`);

  // ==================== 13. 清理 ====================
  section('13. 清理测试数据');
  await call('DELETE', `/cs/courses/delete/${courseId}`, { token: coachToken });
  if (course.cateId) await call('DELETE', `/cs/categorys/${course.cateId}`, { token: coachToken });
  check('★ 删除课程后：题目与关联全部清掉，不留孤儿数据',
    sql(`SELECT COUNT(*) FROM fm_course.subject WHERE course_id=${courseId}`) === '0'
      && sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject WHERE course_id=${courseId}`) === '0'
      && sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject_draft WHERE course_id=${courseId}`) === '0',
    `subject=${sql(`SELECT COUNT(*) FROM fm_course.subject WHERE course_id=${courseId}`)}, cata=${sql(`SELECT COUNT(*) FROM fm_course.course_cata_subject WHERE course_id=${courseId}`)}`);
  sql(`DELETE FROM fm_learning.points_record WHERE user_id=1 AND type=5`);
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
