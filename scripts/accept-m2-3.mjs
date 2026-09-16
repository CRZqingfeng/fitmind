#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M2-3 搜索服务（fm-search）端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：IK 中文分词（含健身自定义词典）、索引自动创建、MQ 索引同步、
 *       关键词检索/筛选/分页/高亮、三个榜单、用户兴趣、鉴权
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、
 *   fm-search(8085)、fm-gateway(10010) 已启动
 *   MySQL、Redis、Nacos、RabbitMQ、MinIO、Elasticsearch(9201) 已运行
 *     —— ES 必须已装 IK 插件：bash scripts/setup-ik.sh
 *
 * 用法：node scripts/accept-m2-3.mjs
 *
 * 本脚本最关键的两项：
 *   1) 上架后 ES 里【真的有】该课程文档、下架后【真的没了】——直连 ES 查，
 *      不看接口返回码
 *   2) 搜「增肌」能召回「21天增肌训练营」，但【不会】召回「肌肉增长原理」——
 *      这一项证明健身自定义词典真的起作用了
 * ============================================================
 */

const GATEWAY = 'http://localhost:10010';
const ES = 'http://localhost:9201';
const ES_INDEX = 'fitmind_course';
const COACH_USER_ID = 2; // M1 种子数据里的「测试教练」

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
}

/** 直连 ES，绕过应用——用来证明"索引里到底有没有这条数据" */
async function es(path, options = {}) {
  const res = await fetch(`${ES}${path}`, options);
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 强制刷新索引。
 *
 * <p>为什么验收脚本要做这个动作：ES 默认 1 秒 refresh 一次，
 * 而<b>按 id 的 GET 是实时的，搜索走的倒排索引视图不是</b>——
 * 所以会出现"文档查得到但搜不到"的假象。生产环境里晚 1 秒无所谓，
 * 但脚本要稳定可复现，所以显式刷一次。</p>
 */
async function esRefresh() {
  await es(`/${ES_INDEX}/_refresh`, { method: 'POST' });
  await sleep(300);
}

/** 轮询直到条件成立（MQ 是异步的，索引同步需要一点时间） */
async function waitUntil(fn, { timeout = 20000, interval = 1000 } = {}) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await fn()) return true;
    await sleep(interval);
  }
  return false;
}

/**
 * 走完整流程建一门课并上架。
 *
 * 上架要求填写进度走完（M2-2b 起 REQUIRED_FOR_SHELF = TEACHER），
 * 所以「基本信息 → 目录 → 绑视频 → 教练」一步都不能少。
 */
async function buildAndShelfCourse({ token, name, free, price, cateId }) {
  const base = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name,
      courseType: 1,
      firstCateId: cateId,
      free,
      price,
      goalType: 1,
      trainingLevel: 2,
      totalDays: 21,
      equipment: '哑铃,单杠',
      courseIntroduce: 'M2-3 验收用课程',
    },
  });
  const courseId = base.json?.data?.id;
  if (!courseId) return null;

  // 目录：1 个训练日 + 2 个训练动作
  await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [
      {
        type: 1,
        name: 'Day 1 胸部',
        index: 1,
        sections: [{ type: 2, name: '哑铃卧推' }, { type: 2, name: '哑铃飞鸟' }],
      },
    ],
  });
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  // ⚠️ M2-2b 起上架要求「每个训练动作都必须绑定视频」，所以这里要把所有动作都绑上，
  //    只绑第一个会被 checkBeforeUpShelf 拒绝。
  const actionIds = (tree.json?.data?.[0]?.sections || []).map((s) => s.id);

  // 上传视频（M2-1 的能力：申请签名 → 直传 → 确认）
  const buf = Buffer.from(`m2-3-video-${Date.now()}`);
  const sig = await call(
    'POST',
    `/ms/medias/upload-signature?filename=${encodeURIComponent(name)}.mp4&size=${buf.length}`,
    { token }
  );
  const mediaId = sig.json?.data?.mediaId;
  if (sig.json?.data?.uploadUrl) {
    await fetch(sig.json.data.uploadUrl, { method: 'PUT', body: buf }).catch(() => null);
    await call('POST', `/ms/medias/${mediaId}/confirm`, { token, body: { duration: 180 } });
  }
  await call('POST', `/cs/courses/media/save/${courseId}`, {
    token,
    body: actionIds.map((cataId) => ({
      cataId,
      mediaId,
      videoName: `${name}.mp4`,
      mediaDuration: 180,
      sets: 4,
      reps: 10,
      restSeconds: 90,
      actionType: 1,
    })),
  });

  // 教练
  await call('POST', '/cs/courses/teachers/save', {
    token,
    body: { id: courseId, teachers: [{ id: COACH_USER_ID, isShow: true }] },
  });

  // 上架
  const up = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  return up.json?.code === 200 ? courseId : null;
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M2-3 搜索服务 —— 端到端验收');
  console.log('========================================');

  // ==================== 0. 登录 ====================
  section('0. 登录获取 token');
  const PHONE = '13800010001';
  const codeRes = await call('POST', `/us/codes?phone=${PHONE}`);
  const verifyCode = codeRes.json?.data;
  let token = null;
  if (verifyCode) {
    const loginRes = await call('POST', '/as/accounts/login', {
      body: { type: 2, cellPhone: PHONE, password: verifyCode, rememberMe: false },
    });
    token = loginRes.json?.data;
  }
  check('登录成功并拿到 token', typeof token === 'string' && token.length > 20);
  if (!token) return summary();

  // ==================== 1. 环境：IK 分词与索引 ====================
  section('1. 环境：IK 中文分词与索引自动创建');

  const analyze = async (word) => {
    const r = await es('/_analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ analyzer: 'ik_max_word', text: word }),
    });
    return (r.json?.tokens || []).map((t) => t.token);
  };

  const zengji = await analyze('增肌');
  check('★ 「增肌」被切成一个完整词（词典生效）', zengji.length === 1 && zengji[0] === '增肌',
    `实际=${JSON.stringify(zengji)}`);
  const jianzhi = await analyze('减脂');
  check('★ 「减脂」被切成一个完整词', jianzhi.length === 1 && jianzhi[0] === '减脂',
    `实际=${JSON.stringify(jianzhi)}`);
  const yingla = await analyze('硬拉');
  check('★ 「硬拉」被切成一个完整词', yingla.length === 1 && yingla[0] === '硬拉',
    `实际=${JSON.stringify(yingla)}`);
  const shendun = await analyze('深蹲');
  check('★ 「深蹲」被切成一个完整词', shendun.length === 1 && shendun[0] === '深蹲',
    `实际=${JSON.stringify(shendun)}`);

  const idxRes = await es(`/${ES_INDEX}`);
  check('★ 索引已由应用启动时自动创建', idxRes.status === 200, `status=${idxRes.status}`);
  const nameAnalyzer = idxRes.json?.[ES_INDEX]?.mappings?.properties?.name?.analyzer;
  check('课程名使用 IK 分词器', nameAnalyzer === 'ik_max_word', `analyzer=${nameAnalyzer}`);
  const actionField = idxRes.json?.[ES_INDEX]?.mappings?.properties?.actionNames;
  check('★ 训练动作名字段已建（可按动作搜课程）', !!actionField);

  // ==================== 2. 准备两门课 ====================
  section('2. 准备测试课程（一门含「增肌」，一门是干扰项）');
  const suffix = String(Date.now()).slice(-6);

  // ⚠️ 两门课必须放在【不同的分类】下。
  //    课程名、动作名、分类路径都是可检索字段，如果两门课共用「增肌」分类，
  //    干扰课程会通过分类路径被召回，那就测不出分词词典的价值了。
  await call('POST', '/cs/categorys/add', { token, body: { name: `增肌${suffix}`, parentId: 0 } });
  await call('POST', '/cs/categorys/add', { token, body: { name: `营养${suffix}`, parentId: 0 } });
  const cateList = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cateList.json?.data || []).find((c) => c.name === `增肌${suffix}`);
  const cateB = (cateList.json?.data || []).find((c) => c.name === `营养${suffix}`);
  check('分类已创建（增肌 / 营养）', !!cate && !!cateB);

  // A：目标课程（免费）
  const nameA = `21天增肌训练营${suffix}`;
  const courseA = await buildAndShelfCourse({
    token, name: nameA, free: 1, price: 0, cateId: cate?.id,
  });
  check('课程A（增肌·免费）已上架', !!courseA);

  // B：干扰课程（付费）——名字里带「增」「肌」但不是「增肌」
  const nameB = `肌肉增长原理与营养${suffix}`;
  const courseB = await buildAndShelfCourse({
    token, name: nameB, free: 2, price: 9900, cateId: cateB?.id,
  });
  check('课程B（肌肉增长·付费）已上架', !!courseB);

  if (!courseA || !courseB) {
    console.log('  ⚠️ 课程准备失败，后续验收无法进行');
    return summary();
  }

  // ==================== 3. ★ 端到端：上架 → ES 有数据 ====================
  section('3. ★ 端到端链路：上架 → ES 里真的出现该课程');

  const aInEs = await waitUntil(async () => {
    const r = await es(`/${ES_INDEX}/_doc/${courseA}`);
    return r.status === 200 && r.json?.found === true;
  });
  check('★ 课程A 上架后 ES 里真的有这条文档', aInEs);

  // ⚠️ 两门课都要等：MQ 是异步的，B 的消息可能比 A 晚到，
  //    只等 A 就去检索会出现"B 搜不到"的假失败。
  const bInEs = await waitUntil(async () => {
    const r = await es(`/${ES_INDEX}/_doc/${courseB}`);
    return r.status === 200 && r.json?.found === true;
  });
  check('★ 课程B 上架后 ES 里也真的有这条文档', bInEs);

  // 上架后刷一次索引，让后面的检索能立刻看到（否则要等 ES 自动 refresh）
  await esRefresh();

  const docA = (await es(`/${ES_INDEX}/_doc/${courseA}`)).json?._source || {};
  check('索引文档的课程名正确', docA.name === nameA, `name=${docA.name}`);
  check('★ 索引带上了健身字段（训练目标）', docA.goalType === 1, `goalType=${docA.goalType}`);
  check('★ 索引带上了器械字段', docA.equipment === '哑铃,单杠', `equipment=${docA.equipment}`);
  check('★ 索引带上了训练动作名', Array.isArray(docA.actionNames) && docA.actionNames.includes('哑铃卧推'),
    `actionNames=${JSON.stringify(docA.actionNames)}`);
  check('索引里 free 是布尔值', docA.free === true, `free=${docA.free}`);
  check('索引里带上了教练', Array.isArray(docA.teacherNames) && docA.teacherNames.length === 1,
    `teacherNames=${JSON.stringify(docA.teacherNames)}`);
  console.log(`     文档概览：${JSON.stringify({
    name: docA.name, free: docA.free, sections: docA.sections,
    actions: docA.actionNames, teachers: docA.teacherNames,
  })}`);

  // ==================== 4. 搜索 ====================
  section('4. 搜索：关键词 / 高亮 / 筛选 / 分页');

  const searchA = await call('GET',
    `/ss/courses/portal?keyword=${encodeURIComponent('增肌')}&pageNo=1&pageSize=10`, { token });
  const listA = searchA.json?.data?.list || [];
  const hitIds = listA.map((x) => String(x.id));
  check('★ 搜「增肌」能召回课程A', hitIds.includes(String(courseA)),
    `命中=${JSON.stringify(hitIds)}`);
  check('★ 搜「增肌」不会召回无关课程B（自定义词典的价值）',
    !hitIds.includes(String(courseB)), `命中=${JSON.stringify(hitIds)}`);

  const hitA = listA.find((x) => String(x.id) === String(courseA));
  check('★ 命中词有高亮标记 <em>', !!hitA && typeof hitA.highlightName === 'string'
    && hitA.highlightName.includes('<em>'), `highlightName=${hitA?.highlightName}`);

  // 按动作名搜课程（健身场景的价值：用户往往直接搜动作）
  const searchAction = await call('GET',
    `/ss/courses/portal?keyword=${encodeURIComponent('哑铃卧推')}&pageNo=1&pageSize=10`, { token });
  const actionIds = (searchAction.json?.data?.list || []).map((x) => String(x.id));
  check('★ 按训练动作名「哑铃卧推」能搜到课程A', actionIds.includes(String(courseA)),
    `命中=${JSON.stringify(actionIds)}`);

  // 免费筛选
  // ⚠️ pageSize 要开大：`free` 是<b>全局</b>筛选，会命中环境里所有免费课
  //    （包括其他验收脚本没清干净的残留）。用 10 的话课程A 可能被挤到第二页 ——
  //    这个断言的语义是"A 在免费结果里"，不该依赖"环境里恰好只有几门免费课"。
  //    2026-09-14 就因为 ES 里累积了 9 条孤儿免费课而稳定失败过一次。
  const freeRes = await call('GET', `/ss/courses/portal?free=true&pageNo=1&pageSize=100`, { token });
  const freeList = (freeRes.json?.data?.list || []).map((x) => String(x.id));
  check('免费筛选：课程A 在结果里', freeList.includes(String(courseA)));
  check('免费筛选：课程B（付费）被排除', !freeList.includes(String(courseB)));

  // 分类筛选（两门课在不同分类下，所以各自应该只筛出 1 条）
  const cateRes = await call('GET',
    `/ss/courses/portal?categoryIdLv1=${cate?.id}&pageNo=1&pageSize=10`, { token });
  const cateIds = (cateRes.json?.data?.list || []).map((x) => String(x.id));
  check('分类筛选：只筛出「增肌」分类下的课程A',
    cateIds.length === 1 && cateIds[0] === String(courseA), `命中=${JSON.stringify(cateIds)}`);
  const cateBRes = await call('GET',
    `/ss/courses/portal?categoryIdLv1=${cateB?.id}&pageNo=1&pageSize=10`, { token });
  const cateBIds = (cateBRes.json?.data?.list || []).map((x) => String(x.id));
  check('分类筛选：只筛出「营养」分类下的课程B',
    cateBIds.length === 1 && cateBIds[0] === String(courseB), `命中=${JSON.stringify(cateBIds)}`);

  // 分页
  const p1 = await call('GET', `/ss/courses/portal?pageNo=1&pageSize=1`, { token });
  const p2 = await call('GET', `/ss/courses/portal?pageNo=2&pageSize=1`, { token });
  const id1 = p1.json?.data?.list?.[0]?.id;
  const id2 = p2.json?.data?.list?.[0]?.id;
  check('分页：第 1 页有 1 条', (p1.json?.data?.list || []).length === 1,
    `实际 ${(p1.json?.data?.list || []).length}`);
  check('★ 分页：第 1 页与第 2 页不是同一条', id1 != null && id1 !== id2,
    `p1=${id1}, p2=${id2}`);
  check('分页：总页数 >= 2', (p1.json?.data?.pages ?? 0) >= 2, `pages=${p1.json?.data?.pages}`);

  // 只查 id 的接口
  const nameRes = await call('GET', `/ss/courses/name?keyword=${encodeURIComponent('增肌')}`, { token });
  const idsOnly = nameRes.json?.data || [];
  check('按关键词只查 id 的接口可用', idsOnly.map(String).includes(String(courseA)),
    `ids=${JSON.stringify(idsOnly)}`);

  // ==================== 5. 推荐 ====================
  section('5. 推荐榜单');
  const best = await call('GET', '/ss/recommend/best?size=5', { token });
  check('热门推荐返回列表', Array.isArray(best.json?.data) && best.json?.data.length > 0,
    `len=${best.json?.data?.length}`);
  const newest = await call('GET', '/ss/recommend/new?size=5', { token });
  check('最新上架返回列表', Array.isArray(newest.json?.data) && newest.json?.data.length > 0,
    `len=${newest.json?.data?.length}`);
  const freeRec = await call('GET', '/ss/recommend/free?size=5', { token });
  const freeRecList = freeRec.json?.data || [];
  check('免费好课返回的都是免费课',
    freeRecList.length > 0 && freeRecList.every((x) => x.free === true),
    `free=${JSON.stringify(freeRecList.map((x) => x.free))}`);

  // ==================== 6. 兴趣 ====================
  section('6. 用户兴趣');
  const saveInterest = await call('POST', '/ss/interests', { token, body: [cate?.id] });
  check('保存兴趣成功', saveInterest.json?.code === 200, `msg=${saveInterest.json?.msg}`);
  const myInterests = await call('GET', '/ss/interests', { token });
  check('查询兴趣返回刚保存的分类',
    (myInterests.json?.data || []).map(String).includes(String(cate?.id)),
    `data=${JSON.stringify(myInterests.json?.data)}`);
  const emptyInterest = await call('POST', '/ss/interests', { token, body: [] });
  check('保存空兴趣被拒绝', emptyInterest.json?.code !== 200, `code=${emptyInterest.json?.code}`);

  const coursesOfCate = await call('GET', `/ss/interests/${cate?.id}/courses?size=5`, { token });
  const cateCourseIds = (coursesOfCate.json?.data || []).map((x) => String(x.id));
  check('按兴趣分类能查到课程A', cateCourseIds.includes(String(courseA)),
    `ids=${JSON.stringify(cateCourseIds)}`);

  // ==================== 7. ★ 端到端：下架 → ES 里没了 ====================
  section('7. ★ 端到端链路：下架 → ES 里真的没了');
  const down = await call('POST', '/cs/courses/downShelf', { token, body: { id: courseA } });
  check('课程A 下架成功', down.json?.code === 200, `msg=${down.json?.msg}`);

  const gone = await waitUntil(async () => {
    const r = await es(`/${ES_INDEX}/_doc/${courseA}`);
    return r.status === 404 || r.json?.found === false;
  });
  check('★ 下架后 ES 里真的查不到该文档了', gone);

  // ⚠️ 必须刷一次再搜：ES 默认 1 秒 refresh 一次。
  //    按 id 的 GET 是实时的（所以上面「文档没了」立刻就能看到），
  //    但搜索走的是倒排索引视图，删完不 refresh 就还能搜到旧数据。
  await esRefresh();
  const searchAfterDown = await call('GET',
    `/ss/courses/portal?keyword=${encodeURIComponent('增肌')}&pageNo=1&pageSize=10`, { token });
  const afterIds = (searchAfterDown.json?.data?.list || []).map((x) => String(x.id));
  check('★ 下架后搜索也搜不到了', !afterIds.includes(String(courseA)),
    `命中=${JSON.stringify(afterIds)}`);

  // ==================== 8. 鉴权 ====================
  section('8. 鉴权');
  const noTokenSearch = await call('GET', '/ss/courses/portal?pageNo=1&pageSize=5');
  check('未登录也能搜索（搜索是公开的）', noTokenSearch.status === 200,
    `status=${noTokenSearch.status}`);
  const noTokenInterest = await call('GET', '/ss/interests');
  check('未登录访问兴趣被拦截 401', noTokenInterest.status === 401,
    `status=${noTokenInterest.status}`);
  const noTokenRecommend = await call('GET', '/ss/recommend/best?size=3');
  check('未登录访问推荐被拦截 401', noTokenRecommend.status === 401,
    `status=${noTokenRecommend.status}`);

  // ==================== 9. 清理 ====================
  section('9. 清理测试数据');
  await call('POST', '/cs/courses/downShelf', { token, body: { id: courseB } });
  await call('DELETE', `/cs/courses/delete/${courseA}`, { token });
  await call('DELETE', `/cs/courses/delete/${courseB}`, { token });
  if (cate?.id) await call('DELETE', `/cs/categorys/${cate.id}`, { token });
  if (cateB?.id) await call('DELETE', `/cs/categorys/${cateB.id}`, { token });
  console.log('     测试数据已清理');

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
