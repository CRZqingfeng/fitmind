#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M2-2b 训练目录与教练 端到端验收脚本
 * ------------------------------------------------------------
 * 覆盖：训练目录（训练日/训练动作 + 动作参数）、动作绑定视频、
 *       课程教练、上架完整性校验、已上架结构保护
 *
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-course(8084)、fm-gateway(10010) 已启动
 *   MySQL、Redis、Nacos、RabbitMQ、MinIO 已运行
 *
 * 用法：node scripts/accept-m2-2b.mjs
 *
 * 本脚本最关键的一项是「目录重存后 mediaId 是否还在」——
 * 保存目录是全量覆盖（先删后插），如果没有从内存 map 复用老对象，
 * 已绑定的视频和动作参数会被静默清空。光看接口返回 200 发现不了。
 * ============================================================
 */

const GATEWAY = 'http://localhost:10010';
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

async function main() {
  console.log('========================================');
  console.log(' FitMind M2-2b 训练目录与教练 —— 端到端验收');
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

  const suffix = String(Date.now()).slice(-6);

  // ==================== 1. 准备：分类 + 课程 ====================
  section('1. 准备分类与课程');
  await call('POST', '/cs/categorys/add', { token, body: { name: `增肌${suffix}`, parentId: 0 } });
  const cateList = await call('GET', '/cs/categorys/list?level=1&tree=false', { token });
  const cate = (cateList.json?.data || []).find((c) => c.name === `增肌${suffix}`);
  check('分类已创建', !!cate);

  const courseName = `M2-2b目录测试课${suffix}`;
  const courseRes = await call('POST', '/cs/courses/baseInfo/save', {
    token,
    body: {
      name: courseName, courseType: 1, firstCateId: cate?.id,
      free: 1, price: 0, goalType: 1, trainingLevel: 2, totalDays: 21,
      courseIntroduce: 'M2-2b 验收用课程',
    },
  });
  const courseId = courseRes.json?.data?.id;
  check('课程已创建（step=1）', !!courseId && courseRes.json?.data?.step === 1,
    `data=${JSON.stringify(courseRes.json?.data)}`);
  if (!courseId) return summary();

  // ==================== 2. 保存训练目录 ====================
  section('2. 保存训练目录（训练日 + 训练动作）');
  const cataPayload = [
    {
      type: 1, name: 'Day 1 胸部+三头', index: 1,
      sections: [
        { type: 2, name: '哑铃卧推' },
        { type: 2, name: '哑铃飞鸟' },
      ],
    },
    {
      type: 1, name: 'Day 2 背部+二头', index: 2,
      sections: [{ type: 2, name: '单臂哑铃划船' }],
    },
  ];
  const saveCata = await call('POST', `/cs/courses/catas/save/${courseId}/2`,
    { token, body: cataPayload });
  check('保存训练目录成功', saveCata.json?.code === 200, `msg=${saveCata.json?.msg}`);

  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const days = tree.json?.data || [];
  check('目录树返回 2 个训练日', days.length === 2, `实际 ${days.length}`);
  check('Day1 下面有 2 个训练动作', days[0]?.sections?.length === 2,
    `实际 ${days[0]?.sections?.length}`);
  check('Day2 下面有 1 个训练动作', days[1]?.sections?.length === 1,
    `实际 ${days[1]?.sections?.length}`);
  check('训练动作序号自动生成', days[0]?.sections?.[0]?.index === 1,
    `index=${days[0]?.sections?.[0]?.index}`);
  console.log(`     目录：${days.map((d) => `${d.name}(${d.sections?.length}个动作)`).join(' / ')}`);

  const day1Id = days[0]?.id;
  const day2Id = days[1]?.id;
  const action1Id = days[0]?.sections?.[0]?.id;
  const action2Id = days[0]?.sections?.[1]?.id;
  const action3Id = days[1]?.sections?.[0]?.id;

  const afterCata = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('step 推进到 2（目录）', afterCata.json?.data?.step === 2,
    `step=${afterCata.json?.data?.step}`);

  // 序号校验
  const dupIndex = await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [
      { type: 1, name: 'A', index: 1, sections: [{ type: 2, name: 'a1' }] },
      { type: 1, name: 'B', index: 1, sections: [{ type: 2, name: 'b1' }] },
    ],
  });
  check('训练日序号重复被拒绝', dupIndex.json?.code !== 200, `msg=${dupIndex.json?.msg}`);

  const jumpIndex = await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [
      { type: 1, name: 'A', index: 1, sections: [{ type: 2, name: 'a1' }] },
      { type: 1, name: 'B', index: 3, sections: [{ type: 2, name: 'b1' }] },
    ],
  });
  check('训练日序号跳号被拒绝', jumpIndex.json?.code !== 200, `msg=${jumpIndex.json?.msg}`);

  const emptyDay = await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [{ type: 1, name: '空训练日', index: 1, sections: [] }],
  });
  check('空训练日（没有动作）被拒绝', emptyDay.json?.code !== 200, `msg=${emptyDay.json?.msg}`);

  // ==================== 3. 上传训练视频（走 M2-1 流程） ====================
  section('3. 上传训练视频（复用 M2-1 媒资服务）');
  const videoBytes = Buffer.from('FakeTrainingVideo-' + Date.now());
  const sig = await call('POST',
    `/ms/medias/upload-signature?filename=哑铃卧推.mp4&size=${videoBytes.length}`, { token });
  const mediaId = sig.json?.data?.mediaId;
  const uploadUrl = sig.json?.data?.uploadUrl;
  check('申请媒资上传签名成功', !!mediaId && !!uploadUrl, `msg=${sig.json?.msg}`);

  if (uploadUrl) {
    const put = await fetch(uploadUrl, { method: 'PUT', body: videoBytes }).catch(() => null);
    check('视频直传 MinIO 成功', put?.status === 200, `status=${put?.status}`);
    const confirm = await call('POST', `/ms/medias/${mediaId}/confirm`,
      { token, body: { duration: 180 } });
    check('媒资确认成功（时长 180 秒）', confirm.json?.code === 200, `msg=${confirm.json?.msg}`);
  }

  // ==================== 4. 保存训练动作信息 ====================
  section('4. 保存训练动作信息（绑定视频 + 动作参数）');
  const mediaPayload = [
    {
      cataId: action1Id, mediaId, videoName: '哑铃卧推.mp4', mediaDuration: 180, trailer: true,
      sets: 4, reps: 10, restSeconds: 90, weight: 20, actionType: 1,
      equipment: '哑铃', targetMuscles: '胸大肌,肱三头肌',
    },
    {
      cataId: action2Id, mediaId, videoName: '哑铃飞鸟.mp4', mediaDuration: 120, trailer: false,
      sets: 3, reps: 12, restSeconds: 60, weight: 12.5, actionType: 1,
      equipment: '哑铃', targetMuscles: '胸大肌',
    },
    {
      cataId: action3Id, mediaId, videoName: '单臂划船.mp4', mediaDuration: 150, trailer: false,
      sets: 4, reps: 10, restSeconds: 90, weight: 24, actionType: 1,
      equipment: '哑铃', targetMuscles: '背阔肌',
    },
  ];
  const saveMedia = await call('POST', `/cs/courses/media/save/${courseId}`,
    { token, body: mediaPayload });
  check('保存训练动作信息成功', saveMedia.json?.code === 200, `msg=${saveMedia.json?.msg}`);

  const tree2 = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const day1 = tree2.json?.data?.[0];
  const act1 = day1?.sections?.[0];
  check('★ 动作已绑定媒资 id', String(act1?.mediaId) === String(mediaId), `mediaId=${act1?.mediaId}`);
  check('★ 动作参数已保存（组数=4）', act1?.sets === 4, `sets=${act1?.sets}`);
  check('★ 动作参数已保存（次数=10）', act1?.reps === 10, `reps=${act1?.reps}`);
  check('★ 动作参数已保存（负重=20）', Number(act1?.weight) === 20, `weight=${act1?.weight}`);
  check('目标肌群已保存', act1?.targetMuscles === '胸大肌,肱三头肌',
    `muscles=${act1?.targetMuscles}`);
  check('试看标记已保存', act1?.trailer === true, `trailer=${act1?.trailer}`);
  check('★ 训练日时长自动累加（180+120=300）', day1?.mediaDuration === 300,
    `mediaDuration=${day1?.mediaDuration}`);

  const stepAfterMedia = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('step 推进到 3（视频）', stepAfterMedia.json?.data?.step === 3,
    `step=${stepAfterMedia.json?.data?.step}`);

  const badMedia = await call('POST', `/cs/courses/media/save/${courseId}`, {
    token,
    body: [{ cataId: action1Id, mediaId: 99999999, videoName: '不存在.mp4' }],
  });
  check('绑定不存在的媒资被拒绝', badMedia.json?.code !== 200, `msg=${badMedia.json?.msg}`);

  // ==================== 5. ★ 目录重存后数据是否保留 ====================
  section('5. ★ 重存目录（验证全量覆盖不丢数据）');
  const resavePayload = [
    {
      id: day1Id, type: 1, name: 'Day 1 胸部+三头（改）', index: 1,
      sections: [
        { id: action1Id, type: 2, name: '哑铃卧推（标准版）' },
        { type: 2, name: '新加的俯卧撑' },
      ],
    },
    {
      id: day2Id, type: 1, name: 'Day 2 背部+二头', index: 2,
      sections: [{ id: action3Id, type: 2, name: '单臂哑铃划船' }],
    },
  ];
  const resave = await call('POST', `/cs/courses/catas/save/${courseId}/2`,
    { token, body: resavePayload });
  check('重存目录成功', resave.json?.code === 200, `msg=${resave.json?.msg}`);

  const tree3 = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const day1After = tree3.json?.data?.find((d) => d.id === day1Id);
  const act1After = day1After?.sections?.find((s) => s.id === action1Id);
  check('训练日名称已更新', day1After?.name?.includes('（改）'), `name=${day1After?.name}`);
  check('动作名称已更新', act1After?.name?.includes('标准版'), `name=${act1After?.name}`);
  check('★ mediaId 仍然保留（未被清空）', String(act1After?.mediaId) === String(mediaId),
    `mediaId=${act1After?.mediaId}`);
  check('★ 组数仍然保留', act1After?.sets === 4, `sets=${act1After?.sets}`);
  check('★ 负重仍然保留', Number(act1After?.weight) === 20, `weight=${act1After?.weight}`);
  check('★ 试看标记仍然保留', act1After?.trailer === true, `trailer=${act1After?.trailer}`);
  check('新增的动作被写入', day1After?.sections?.some((s) => s.name.includes('俯卧撑')));

  // 为后续上架准备：给新动作也绑上视频
  const newAction = day1After?.sections?.find((s) => s.name.includes('俯卧撑'));
  if (newAction) {
    await call('POST', `/cs/courses/media/save/${courseId}`, {
      token,
      body: [{ cataId: newAction.id, mediaId, videoName: '俯卧撑.mp4', mediaDuration: 60, sets: 3, reps: 15 }],
    });
  }

  // ==================== 6. 课程教练 ====================
  section('6. 课程教练');
  const saveTeacher = await call('POST', '/cs/courses/teachers/save', {
    token,
    body: { id: courseId, teachers: [{ id: COACH_USER_ID, isShow: true }] },
  });
  check('保存课程教练成功', saveTeacher.json?.code === 200, `msg=${saveTeacher.json?.msg}`);

  const teacherList = await call('GET', `/cs/courses/teachers/${courseId}`, { token });
  const coach = teacherList.json?.data?.[0];
  // ⚠️ id 现在是字符串（Long→String）
  check('查询课程教练成功', String(coach?.id) === String(COACH_USER_ID), `data=${JSON.stringify(teacherList.json?.data)}`);
  check('★ 教练姓名已从 fm-user 取到', !!coach?.name, `name=${coach?.name}`);
  console.log(`     教练：${JSON.stringify(coach)}`);

  const stepAfterTeacher = await call('GET', `/cs/courses/baseInfo/${courseId}`, { token });
  check('step 推进到 5（教练，可上架）', stepAfterTeacher.json?.data?.step === 5,
    `step=${stepAfterTeacher.json?.data?.step}`);

  // ==================== 7. 上架 ====================
  section('7. 课程上架');
  const upRes = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } });
  check('上架成功', upRes.json?.code === 200, `msg=${upRes.json?.msg}`);

  const pageShelf = await call('GET', '/cs/courses/page?pageNo=1&pageSize=10&status=2', { token });
  const shelfCourse = (pageShelf.json?.data?.list || []).find((c) => c.id === courseId);
  check('★ 动作数量为真实计算值 3', shelfCourse?.sectionNum === 3,
    `sectionNum=${shelfCourse?.sectionNum}`);
  // 注意：第 5 节重存目录时「哑铃飞鸟」被移除了，所以只剩 3 个动作：
  // 卧推 180 + 俯卧撑 60 + 划船 150 = 390
  check('★ 视频总时长为真实计算值 390（180+60+150）',
    shelfCourse?.mediaDuration === 390, `mediaDuration=${shelfCourse?.mediaDuration}`);
  console.log(`     上架后：动作数=${shelfCourse?.sectionNum}, 总时长=${shelfCourse?.mediaDuration}秒`);

  const userTree = await call('GET', `/cs/courses/${courseId}/catalogs`, { token });
  check('★ 用户端能查到已上架的训练目录', (userTree.json?.data || []).length === 2,
    `训练日数=${userTree.json?.data?.length}`);
  const userDay1 = userTree.json?.data?.[0];
  check('用户端目录含动作参数', userDay1?.sections?.[0]?.sets === 4,
    `sets=${userDay1?.sections?.[0]?.sets}`);

  // ==================== 8. 已上架结构保护 ====================
  section('8. 已上架结构保护');
  const deleteDay = await call('POST', `/cs/courses/catas/save/${courseId}/2`, {
    token,
    body: [
      { id: day2Id, type: 1, name: 'Day 2 背部+二头', index: 1, sections: [{ id: action3Id, type: 2, name: '单臂哑铃划船' }] },
    ],
  });
  check('★ 删除已上架的训练日被拒绝', deleteDay.json?.code !== 200, `msg=${deleteDay.json?.msg}`);

  const treeNow = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const allDays = treeNow.json?.data || [];
  const changeIndexBody = allDays.map((d, i) => ({
    id: d.id, type: 1,
    name: d.name,
    index: d.id === day1Id ? 2 : 1, // 把 Day1 的序号从 1 改成 2
    sections: d.sections.map((s) => ({ id: s.id, type: 2, name: s.name })),
  }));
  const changeIndex = await call('POST', `/cs/courses/catas/save/${courseId}/2`,
    { token, body: changeIndexBody });
  check('★ 修改已上架训练日的序号被拒绝', changeIndex.json?.code !== 200,
    `msg=${changeIndex.json?.msg}`);

  // ==================== 9. 下架 ====================
  section('9. 课程下架');
  const downRes = await call('POST', '/cs/courses/downShelf', { token, body: { id: courseId } });
  check('下架成功', downRes.json?.code === 200, `msg=${downRes.json?.msg}`);

  const treeAfterDown = await call('GET', `/cs/courses/catas/${courseId}`, { token });
  const downDays = treeAfterDown.json?.data || [];
  check('★ 训练目录随下架回到草稿', downDays.length === 2, `训练日数=${downDays.length}`);
  const downAct = downDays[0]?.sections?.find((s) => s.id === action1Id);
  check('★ 下架后动作参数完整保留（组数）', downAct?.sets === 4, `sets=${downAct?.sets}`);
  check('★ 下架后动作仍绑着视频', String(downAct?.mediaId) === String(mediaId), `mediaId=${downAct?.mediaId}`);

  const teacherAfterDown = await call('GET', `/cs/courses/teachers/${courseId}`, { token });
  check('课程教练随下架回到草稿', String(teacherAfterDown.json?.data?.[0]?.id) === String(COACH_USER_ID),
    `data=${JSON.stringify(teacherAfterDown.json?.data)}`);

  // ==================== 10. 未绑视频不能上架 ====================
  section('10. 上架完整性校验（有动作没绑视频）');
  const course2Name = `M2-2b未绑视频课${suffix}`;
  const course2 = await call('POST', '/cs/courses/baseInfo/save',
    { token, body: { name: course2Name, courseType: 1, goalType: 1 } });
  const course2Id = course2.json?.data?.id;
  check('新课程已创建', !!course2Id);

  if (course2Id) {
    await call('POST', `/cs/courses/catas/save/${course2Id}/2`, {
      token,
      body: [{ type: 1, name: 'Day 1', index: 1, sections: [{ type: 2, name: '忘记传视频的动作' }] }],
    });
    await call('POST', '/cs/courses/teachers/save',
      { token, body: { id: course2Id, teachers: [{ id: COACH_USER_ID, isShow: true }] } });

    const upBad = await call('POST', '/cs/courses/upShelf', { token, body: { id: course2Id } });
    check('★ 有动作未绑视频时上架被拒绝', upBad.json?.code !== 200, `msg=${upBad.json?.msg}`);
    check('★ 报错信息里指出了是哪个动作',
      String(upBad.json?.msg || '').includes('忘记传视频的动作'), `msg=${upBad.json?.msg}`);

    await call('DELETE', `/cs/courses/delete/${course2Id}`, { token });
  }

  const stepNotEnough = await call('POST', '/cs/courses/baseInfo/save',
    { token, body: { name: `M2-2b未填完课${suffix}`, courseType: 1 } });
  const course3Id = stepNotEnough.json?.data?.id;
  if (course3Id) {
    const up3 = await call('POST', '/cs/courses/upShelf', { token, body: { id: course3Id } });
    check('★ step 未走完（只有基本信息）不能上架', up3.json?.code !== 200, `msg=${up3.json?.msg}`);
    await call('DELETE', `/cs/courses/delete/${course3Id}`, { token });
  }

  // ==================== 11. 鉴权与清理 ====================
  section('11. 鉴权与清理');
  const noToken = await call('GET', `/cs/courses/catas/${courseId}`);
  check('未登录访问训练目录被拦截 401', noToken.status === 401, `status=${noToken.status}`);

  const del = await call('DELETE', `/cs/courses/delete/${courseId}`, { token });
  check('删除测试课程成功', del.json?.code === 200, `msg=${del.json?.msg}`);

  if (cate?.id) {
    await call('DELETE', `/cs/categorys/${cate.id}`, { token });
  }
  if (mediaId) {
    await call('DELETE', `/ms/medias/${mediaId}`, { token });
  }

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
