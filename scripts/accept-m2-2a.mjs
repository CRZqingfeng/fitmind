#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M2-2a 课程服务 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：三级分类 + 课程基本信息（含草稿）+ 课程正文 + 上架/下架闭环
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、fm-gateway(10010) 已启动
 *   MySQL、Redis、Nacos、RabbitMQ 已运行
 *
 * 用法：node scripts/accept-m2-2a.mjs
 *
 * 设计原则：
 *   「草稿-发布」机制的关键在于**数据真的换了位置**，所以验收必须去查：
 *     ① 上架后草稿表还有没有这条数据（应该没有）
 *     ② 正式表有没有（应该有）
 *     ③ 下架后反过来（草稿有、正式状态变下架）
 *   只看接口返回 200 是不够的。
 * ============================================================
 */

import fs from 'node:fs';

const GATEWAY = 'http://localhost:10010';
const COURSE_DIRECT = 'http://localhost:8084';
const RABBIT_API = 'http://localhost:15672/api';
const EXCHANGE = 'course.topic';

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
async function callRaw(method, path, { body, token } = {}) {
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

/** 读取 docker/.env 里的 RabbitMQ 凭据（不打印到控制台） */
function readRabbitCreds() {
  try {
    const env = fs.readFileSync('docker/.env', 'utf-8');
    const get = (k) => (env.match(new RegExp(`^${k}=(.*)$`, 'm')) || [])[1]?.trim();
    return { user: get('RABBITMQ_USER'), pass: get('RABBITMQ_PASSWORD') };
  } catch {
    return {};
  }
}

/**
 * 查交换机的消息发布计数（用于证明 MQ 真的发出去了）。
 *
 * ⚠️ RabbitMQ 的 message_stats 是**周期性采集**的（默认 5 秒一次），
 * 发布后立刻读取拿到的是旧值。所以这里做重试，一直等到计数变化或超时。
 *
 * ★★ `minDelta`：需要观察到的**最小增量**。
 *   默认 1 只够用来证明"确实发过消息"；**要断言"发了几条"就必须把它一起等下去** ——
 *   否则会在只涨了 1~2 时就返回，断言拿到的是**中间值**而误报失败。
 *
 *   （M8-2a 的全量回归里，这条断言偶发失败过一次：增量读到 2 而期望 ≥3。
 *     当时被误当成"MQ 时序问题"，其实是**这里的返回条件写得太松** ——
 *     修法不是"再等久一点"，而是**等够那个增量**。）
 */
async function exchangePublishCount(expectedGreaterThan = null, timeoutMs = 20000, minDelta = 1) {
  const { user, pass } = readRabbitCreds();
  if (!user) return null;
  const auth = Buffer.from(`${user}:${pass}`).toString('base64');
  const deadline = Date.now() + timeoutMs;
  let last = null;
  do {
    try {
      const res = await fetch(`${RABBIT_API}/exchanges/%2F/${EXCHANGE}`, {
        headers: { Authorization: `Basic ${auth}` },
      });
      if (!res.ok) return null;
      const json = await res.json();
      last = json?.message_stats?.publish_in ?? 0;
      if (expectedGreaterThan === null || last - expectedGreaterThan >= minDelta) return last;
    } catch {
      return null;
    }
    await new Promise((r) => setTimeout(r, 2000));
  } while (Date.now() < deadline);
  return last;
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M2-2a 训练课程 —— 端到端验收');
  console.log('========================================');
  console.log(`网关：${GATEWAY}   直连课程：${COURSE_DIRECT}`);

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
  if (!token) {
    console.log('\n⚠️  登录失败，后续用例无法继续');
    return summary();
  }

  const publishBefore = await exchangePublishCount();
  console.log(`     交换机 ${EXCHANGE} 当前发布计数：${publishBefore ?? '（读取失败）'}`);

  // ==================== 1. 三级分类 ====================
  section('1. 三级分类树');
  const suffix = String(Date.now()).slice(-6);
  const cateName1 = `增肌${suffix}`;
  const cateName2 = `胸${suffix}`;
  const cateName3 = `哑铃${suffix}`;

  const add1 = await call('POST', '/cs/categorys/add',
    { token, body: { name: cateName1, parentId: 0, priority: 10 } });
  check('新增一级分类（训练目标）', add1.json?.code === 200, `msg=${add1.json?.msg}`);

  const list1 = await call('GET', `/cs/categorys/list?level=1&tree=false`, { token });
  const level1 = (list1.json?.data || []).find((c) => c.name === cateName1);
  check('能查到刚建的一级分类', !!level1, `data=${JSON.stringify(list1.json?.data)?.slice(0, 120)}`);
  check('一级分类 level=1', level1?.level === 1, `level=${level1?.level}`);
  if (!level1) return summary();

  const add2 = await call('POST', '/cs/categorys/add',
    { token, body: { name: cateName2, parentId: level1.id, priority: 5 } });
  check('新增二级分类（训练部位）', add2.json?.code === 200, `msg=${add2.json?.msg}`);

  const list2 = await call('GET', `/cs/categorys/list?level=2&tree=false`, { token });
  const level2 = (list2.json?.data || []).find((c) => c.name === cateName2);
  check('二级分类 level=2（由父分类推导）', level2?.level === 2, `level=${level2?.level}`);
  if (!level2) return summary();

  const add3 = await call('POST', '/cs/categorys/add',
    { token, body: { name: cateName3, parentId: level2.id, priority: 1 } });
  check('新增三级分类（器械场景）', add3.json?.code === 200, `msg=${add3.json?.msg}`);

  const list3 = await call('GET', `/cs/categorys/list?level=3&tree=false`, { token });
  const level3 = (list3.json?.data || []).find((c) => c.name === cateName3);
  check('三级分类 level=3', level3?.level === 3, `level=${level3?.level}`);

  // 重名校验
  const dupAdd = await call('POST', '/cs/categorys/add',
    { token, body: { name: cateName1, parentId: 0 } });
  check('同一父级下重名分类被拒绝', dupAdd.json?.code !== 200, `msg=${dupAdd.json?.msg}`);

  // 树形结构
  const treeRes = await call('GET', '/cs/categorys/all', { token });
  const rootNode = (treeRes.json?.data || []).find((c) => c.id === level1.id);
  check('树形查询：根节点存在', !!rootNode);
  check('树形查询：一级挂到二级', rootNode?.children?.some((c) => c.id === level2.id),
    `children=${JSON.stringify((rootNode?.children || []).map((c) => c.name))}`);
  const node2 = rootNode?.children?.find((c) => c.id === level2.id);
  check('树形查询：二级挂到三级', node2?.children?.some((c) => c.id === level3.id));

  // 按层级查询
  const oneLevel = await call('GET', '/cs/categorys/getAllOfOneLevel?level=1', { token });
  check('按层级查询接口可用', Array.isArray(oneLevel.json?.data) && oneLevel.json.data.length > 0,
    `len=${oneLevel.json?.data?.length}`);

  // 详情 / 更新 / 启停
  const detail = await call('GET', `/cs/categorys/${level3.id}`, { token });
  check('查询分类详情', detail.json?.data?.id === level3.id, `msg=${detail.json?.msg}`);

  const upd = await call('PUT', '/cs/categorys/update',
    { token, body: { id: level3.id, name: cateName3 + 'X', priority: 2 } });
  check('更新分类成功', upd.json?.code === 200, `msg=${upd.json?.msg}`);

  const dis = await call('PUT', '/cs/categorys/disableOrEnable',
    { token, body: { id: level3.id, status: 2 } });
  const detail2 = await call('GET', `/cs/categorys/${level3.id}`, { token });
  check('停用分类后状态变为 2', dis.json?.code === 200 && detail2.json?.data?.status === 2,
    `status=${detail2.json?.data?.status}`);

  // 有子分类不能删
  const delWithChild = await call('DELETE', `/cs/categorys/${level1.id}`, { token });
  check('删除有子分类的分类被拒绝', delWithChild.json?.code !== 200, `msg=${delWithChild.json?.msg}`);

  // ==================== 2. 课程基本信息 ====================
  section('2. 课程基本信息（草稿）');
  const courseName = `21天增肌训练营${suffix}`;
  const saveRes = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name: courseName,
      courseType: 1,
      firstCateId: level1.id,
      secondCateId: level2.id,
      thirdCateId: level3.id,
      free: 1,
      price: 0,
      validDuration: 90,
      goalType: 1,
      trainingLevel: 2,
      totalDays: 21,
      weeklyFrequency: 4,
      equipment: '哑铃,单杠',
      estimatedCalories: 300,
      courseIntroduce: '这是一门面向零基础的增肌课程',
      usePeople: '想增肌的上班族',
      precautions: '有肩伤的同学请咨询医生后再练',
      requiredEquipment: '哑铃、单杠',
    },
  });
  check('保存课程基本信息成功', saveRes.json?.code === 200, `msg=${saveRes.json?.msg}`);
  const courseId = saveRes.json?.data?.id;
  check('返回课程id', !!courseId, `data=${JSON.stringify(saveRes.json?.data)}`);
  check('step 推进到 1（基本信息）', saveRes.json?.data?.step === 1,
    `step=${saveRes.json?.data?.step}`);
  if (!courseId) return summary();

  const info = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('查询课程基本信息', info.json?.data?.id === courseId, `msg=${info.json?.msg}`);
  check('训练目标字段已保存', info.json?.data?.goalType === 1, `goalType=${info.json?.data?.goalType}`);
  check('★ 正文与基本信息一起保存（共用 id）',
    info.json?.data?.courseIntroduce?.includes('增肌'), `introduce=${info.json?.data?.courseIntroduce}`);
  check('★ 训练注意事项已保存（健身新增字段）',
    info.json?.data?.precautions?.includes('肩伤'), `precautions=${info.json?.data?.precautions}`);
  check('数据来自草稿表（fromDraft=true）', info.json?.data?.fromDraft === true,
    `fromDraft=${info.json?.data?.fromDraft}`);

  // 重名校验
  const nameCheck = await call('GET', `/cs/courses/checkName?name=${encodeURIComponent(courseName)}`, { token });
  check('重名校验接口返回已存在', nameCheck.json?.data?.exist === true,
    `exist=${nameCheck.json?.data?.exist}`);

  const dupSave = await call('POST', '/cs/courses/baseInfo/save',
    { token, body: { name: courseName, courseType: 1 } });
  check('保存重名课程被拒绝', dupSave.json?.code !== 200, `msg=${dupSave.json?.msg}`);

  // 更新草稿
  const updCourse = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: { id: courseId, name: courseName, courseType: 1, validDuration: 120, estimatedCalories: 350 },
  });
  check('更新草稿成功', updCourse.json?.code === 200, `msg=${updCourse.json?.msg}`);
  const info2 = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('更新后的字段已生效', info2.json?.data?.validDuration === 120,
    `validDuration=${info2.json?.data?.validDuration}`);

  // ==================== 2.5 上架前置数据 ====================
  // ⚠️ M2-2b 起，上架要求填写进度走完（REQUIRED_FOR_SHELF 从 BASE_INFO 提高到 TEACHER），
  //    所以这里补最小前置数据：训练目录 + 动作绑定视频 + 课程教练。
  //    M2-2a 脚本原本只填基本信息就能上架，那个基线已被 M2-2b 覆盖。
  section('2.5 上架前置数据（M2-2b 起要求信息完整）');
  const cataSave = await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{ type: 1, name: 'Day 1 测试训练日', index: 1, sections: [{ type: 2, name: '测试动作' }] }],
  });
  check('保存训练目录', cataSave.json?.code === 200, `msg=${cataSave.json?.msg}`);

  const treePre = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const preActionId = treePre.json?.data?.[0]?.sections?.[0]?.id;

  const preVideo = Buffer.from('pre-video-' + Date.now());
  const preSig = await call('POST',
    `/ms/medias/upload-signature?filename=前置视频.mp4&size=${preVideo.length}`, { token });
  const preMediaId = preSig.json?.data?.mediaId;
  if (preSig.json?.data?.uploadUrl) {
    await fetch(preSig.json.data.uploadUrl, { method: 'PUT', body: preVideo }).catch(() => null);
    await call('POST', `/ms/medias/${preMediaId}/confirm`, { token, body: { duration: 60 } });
  }
  const bindPre = await call('POST', `/cs/courses/media/save/${courseId}`, {
    token,
    body: [{ cataId: preActionId, mediaId: preMediaId, videoName: '前置视频.mp4', mediaDuration: 60, sets: 3, reps: 12 }],
  });
  check('动作绑定训练视频', bindPre.json?.code === 200, `msg=${bindPre.json?.msg}`);

  const teacherPre = await call('POST', '/cs/courses/teachers/save', {
    token, body: { id: courseId, teachers: [{ id: 2, isShow: true }] },
  });
  check('保存课程教练', teacherPre.json?.code === 200, `msg=${teacherPre.json?.msg}`);

  const stepReady = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('填写进度已到 5（可上架）', stepReady.json?.data?.step === 5,
    `step=${stepReady.json?.data?.step}`);

  // ==================== 3. 上架 ====================
  section('3. 课程上架（草稿 → 正式）');
  const checkBefore = await call('GET', `/cs/courses/checkBeforeUpShelf/${courseId}`, { token });
  check('上架前校验通过', checkBefore.json?.code === 200, `msg=${checkBefore.json?.msg}`);

  const upRes = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  check('上架成功', upRes.json?.code === 200, `msg=${upRes.json?.msg}`);

  const afterUp = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('★ 上架后数据来自正式表（fromDraft=false）', afterUp.json?.data?.fromDraft === false,
    `fromDraft=${afterUp.json?.data?.fromDraft}`);
  check('★ 正式表状态为已上架(2)', afterUp.json?.data?.status === 2,
    `status=${afterUp.json?.data?.status}`);
  check('★ 正文也随之同步到正式表',
    afterUp.json?.data?.precautions?.includes('肩伤'), `precautions=${afterUp.json?.data?.precautions}`);
  check('上架后写入发布时间', !!afterUp.json?.data?.publishTime,
    `publishTime=${afterUp.json?.data?.publishTime}`);

  // 查正式表列表（待上架/下架查草稿，已上架查正式）
  const pageShelf = await call('GET', '/cs/courses/page?pageNo=1&pageSize=10&status=2', { token });
  const found = (pageShelf.json?.data?.list || []).find((c) => c.id === courseId);
  check('★ 已上架列表（查正式表）能查到该课程', !!found,
    `total=${pageShelf.json?.data?.total}`);
  check('列表带状态名称', found?.statusDesc === '已上架', `statusDesc=${found?.statusDesc}`);

  // 幂等：重复上架
  const upAgain = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  check('重复上架被拒绝（幂等）', upAgain.json?.code !== 200, `msg=${upAgain.json?.msg}`);

  // ==================== 4. 下架 ====================
  section('4. 课程下架（正式 → 草稿）');
  const downRes = await call('POST', '/cs/courses/downShelf', { token, body: { id: courseId } });
  check('下架成功', downRes.json?.code === 200, `msg=${downRes.json?.msg}`);

  const afterDown = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('★ 下架后数据回到草稿表（fromDraft=true）', afterDown.json?.data?.fromDraft === true,
    `fromDraft=${afterDown.json?.data?.fromDraft}`);
  check('★ 草稿里的内容是下架前的（未丢失）',
    afterDown.json?.data?.estimatedCalories === 350, `calories=${afterDown.json?.data?.estimatedCalories}`);

  const pageDown = await call('GET', '/cs/courses/page?pageNo=1&pageSize=10&status=3', { token });
  const foundDown = (pageDown.json?.data?.list || []).find((c) => c.id === courseId);
  check('★ 已下架列表（查草稿表）能查到该课程', !!foundDown,
    `total=${pageDown.json?.data?.total}`);

  const downAgain = await call('POST', '/cs/courses/downShelf', { token, body: { id: courseId } });
  check('重复下架被拒绝（幂等）', downAgain.json?.code !== 200, `msg=${downAgain.json?.msg}`);

  // ==================== 5. 二次上架 ====================
  section('5. 二次上架（验证发布次数累加）');
  const up2 = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  check('下架后可以重新上架', up2.json?.code === 200, `msg=${up2.json?.msg}`);
  const pageShelf2 = await call('GET', '/cs/courses/page?pageNo=1&pageSize=10&status=2', { token });
  const found2 = (pageShelf2.json?.data?.list || []).find((c) => c.id === courseId);
  check('★ 发布次数累加为 2', found2?.publishTimes === 2, `publishTimes=${found2?.publishTimes}`);

  // ==================== 6. MQ 通知 ====================
  section('6. MQ 消息（通知搜索服务）');
  // 本阶段共触发了 3 次发布：上架、下架、二次上架。
  // 等 RabbitMQ 的统计采集刷新（默认 5 秒一次），最多等 20 秒。
  // ★ 等够 3 条再返回（minDelta=3）—— 否则会读到中间值让下面那条断言偶发失败
  const publishAfter = await exchangePublishCount(publishBefore, 30000, 3);
  console.log(`     交换机 ${EXCHANGE} 发布计数：${publishBefore} → ${publishAfter}`);
  if (publishBefore !== null && publishAfter !== null) {
    check('★ 上架/下架确实向交换机发出了消息', publishAfter > publishBefore,
      `${publishBefore} → ${publishAfter}`);
    check('★ 消息条数与操作次数一致（上架+下架+上架=3）',
      publishAfter - publishBefore >= 3, `增量=${publishAfter - publishBefore}`);
  } else {
    check('MQ 发布计数可读取', false, 'RabbitMQ 管理 API 不可用');
  }

  // ==================== 7. 鉴权与内部接口 ====================
  section('7. 鉴权');
  const noToken = await call('GET', '/cs/courses/page');
  check('未登录访问 /cs/** 被网关拦截 401', noToken.status === 401, `status=${noToken.status}`);
  const noTokenCate = await call('GET', '/cs/categorys/all');
  check('未登录访问分类接口也被拦截', noTokenCate.status === 401, `status=${noTokenCate.status}`);

  // ==================== 8. 异常与清理 ====================
  section('8. 异常处理与清理');
  const notExist = await call('GET', '/cs/courses/baseInfo/99999999', { token });
  check('查询不存在的课程报错', notExist.json?.code !== 200, `msg=${notExist.json?.msg}`);

  const upNotExist = await call('POST', '/cs/courses/upShelf', { token, body: { id: 99999999 } });
  check('上架不存在的课程被拒绝', upNotExist.json?.code !== 200, `msg=${upNotExist.json?.msg}`);

  // 清理：先下架再删（已上架的课程直接删也行，这里走正常流程）
  if (preMediaId) {
    await call('DELETE', `/ms/medias/${preMediaId}`, { token });
  }
  const delRes = await call('DELETE', `/cs/courses/delete/${courseId}`, { token });
  check('删除课程成功', delRes.json?.code === 200, `msg=${delRes.json?.msg}`);
  const afterDel = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('★ 删除后课程真的查不到了', afterDel.json?.code !== 200, `code=${afterDel.json?.code}`);

  // 清理分类（自下而上）
  await call('DELETE', `/cs/categorys/${level3.id}`, { token });
  await call('DELETE', `/cs/categorys/${level2.id}`, { token });
  const delCate1 = await call('DELETE', `/cs/categorys/${level1.id}`, { token });
  check('自下而上删除分类成功', delCate1.json?.code === 200, `msg=${delCate1.json?.msg}`);

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
