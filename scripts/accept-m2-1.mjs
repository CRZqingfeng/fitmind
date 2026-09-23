#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M2-1 媒资服务 端到端验收脚本
 * ------------------------------------------------------------
 * 前置条件：
 *   fm-auth(8081)、fm-user(8082)、fm-media(8083)、fm-gateway(10010) 已启动
 *   MySQL、Redis、Nacos、MinIO 已运行
 *
 * 用法：node scripts/accept-m2-1.mjs
 *
 * 设计原则：
 *   凡是返回 URL 的接口，都必须发真实 HTTP 请求去访问它，
 *   不能只断言"接口返回了 URL 字符串"——
 *   预签名 URL 的 Host 绑定问题（用 A 地址签名去 B 地址访问会 403）
 *   就是这么藏住的：URL 生成得出来，但根本打不开。
 * ============================================================
 */

const GATEWAY = 'http://localhost:10010';
const MEDIA_DIRECT = 'http://localhost:8083'; // 直连，用于验证内部接口不被包装成 R
const MINIO = 'http://localhost:9000';
const BUCKET = 'fitmind';

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

/** 调网关接口（返回统一响应 R） */
/**
 * ★★ 管理端调用自动改用「管理员」token —— #A9 收尾（2026-09-21）
 *
 * 背景：**管理端接口**（建课/改课/上下架/删课/题目/建券/发券/媒资列表与删除/评分…）
 *       现在都要求**管理员**（UserType=1）。以前它们只要求「已登录」，所以本脚本一直用
 *       **学员** token 调它们 —— 那个越权漏洞就是 docs/待修复问题清单.md 里的 #A9。
 *
 *   ② 管理员 token **惰性获取**：各脚本里 staff 登录的位置不一致（有的要到文件后半段
 *      才登录），惰性获取让这层包装对**所有**脚本完全一致，也不必挪动原有代码。
 *
 * ★★ 清单必须覆盖**所有服务**，不只 fm-course：#A9 是分两次收口的 ——
 *    M7-9 给 fm-media / fm-promotion / fm-exam 开了鉴权，但**没有同步改脚本**，
 *    于是 accept-m2-1（媒资列表/删除）与 accept-m4-3（建券/发券）从那天起一直在 403，
 *    直到 M7-11 跑全量回归才暴露。所以这里**一次性列全**。
 */

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
async function callRaw(method, path, { body, token, json = true } = {}) {
  const headers = {};
  if (json) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = token;
  const res = await fetch(GATEWAY + path, {
    method,
    headers,
    body: body !== undefined ? (json ? JSON.stringify(body) : body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json: parsed, text };
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M2-1 训练媒资 —— 端到端验收');
  console.log('========================================');
  console.log(`网关：${GATEWAY}   直连媒资：${MEDIA_DIRECT}   MinIO：${MINIO}`);

  // ==================== 0. 登录拿 token ====================
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
  check('学员登录成功并拿到 token', typeof token === 'string' && token.length > 20);
  if (!token) {
    console.log('\n⚠️  登录失败，后续用例无法继续');
    return summary();
  }

  // ==================== 1. 环境 ====================
  section('1. 环境与对象存储');
  const live = await fetch(`${MINIO}/minio/health/live`).then((r) => r.status).catch(() => 0);
  check('MinIO 健康检查通过', live === 200, `HTTP ${live}`);

  const cors = await fetch(`${MINIO}/${BUCKET}/probe.txt`, {
    method: 'OPTIONS',
    headers: { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'PUT' },
  }).catch(() => null);
  const allowOrigin = cors?.headers.get('access-control-allow-origin');
  check('CORS 预检放行（浏览器可直传）', cors?.status === 204 && !!allowOrigin,
    `status=${cors?.status}, allow-origin=${allowOrigin}`);

  // ==================== 2. 图片上传与访问 ====================
  section('2. 图片上传（服务端中转）');
  const pngBytes = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    'base64'
  );
  const form = new FormData();
  form.append('file', new Blob([pngBytes], { type: 'image/png' }), 'accept-test.png');
  const upRes = await fetch(`${GATEWAY}/ms/files?bizType=media-cover`, {
    method: 'POST',
    headers: { Authorization: token },
    body: form,
  });
  const upJson = await upRes.json().catch(() => null);
  check('上传接口返回 200', upJson?.code === 200, `msg=${upJson?.msg}`);
  const fileId = upJson?.data?.id;
  check('返回文件 id', !!fileId, `data=${JSON.stringify(upJson?.data)}`);
  const fileUrl = upJson?.data?.url;
  check('返回带签名的访问地址', !!fileUrl && fileUrl.includes('X-Amz-Signature'),
    `url=${String(fileUrl).slice(0, 80)}`);

  // 关键：真实 HTTP 请求去访问这个 URL
  const imgRes = await fetch(fileUrl).catch(() => null);
  const imgBuf = imgRes ? Buffer.from(await imgRes.arrayBuffer()) : Buffer.alloc(0);
  check('★ 用返回的 URL 真能下载到图片', imgRes?.status === 200 && imgBuf.length === pngBytes.length,
    `status=${imgRes?.status}, bytes=${imgBuf.length}/${pngBytes.length}`);

  const getFile = await call('GET', `/ms/files/${fileId}`, { token });
  check('按 id 查询文件成功', getFile.json?.code === 200 && getFile.json?.data?.id === fileId,
    `msg=${getFile.json?.msg}`);

  // ==================== 3. 申请上传签名 + 前端直传 ====================
  section('3. 视频上传签名（前端直传）');
  const videoBytes = Buffer.from('FakeMp4Content-ForAcceptanceTest-' + Date.now());
  const sigRes = await call('POST',
    `/ms/medias/upload-signature?filename=验收测试视频.mp4&size=${videoBytes.length}`, { token });
  check('申请签名成功', sigRes.json?.code === 200, `msg=${sigRes.json?.msg}`);
  const mediaId = sigRes.json?.data?.mediaId;
  const uploadUrl = sigRes.json?.data?.uploadUrl;
  check('返回预创建的媒资 id', !!mediaId, `mediaId=${mediaId}`);
  check('返回预签名上传地址', !!uploadUrl && uploadUrl.includes('X-Amz-Signature'),
    `url=${String(uploadUrl).slice(0, 70)}`);

  // 关键：真的用这个 URL 把文件 PUT 上去
  const putRes = await fetch(uploadUrl, {
    method: 'PUT',
    body: videoBytes,
    headers: { 'Content-Type': 'video/mp4' },
  }).catch((e) => ({ status: 0, text: () => e.message }));
  check('★ 用预签名 URL 直传 MinIO 成功', putRes.status === 200,
    `status=${putRes.status}, ${await putRes.text?.().catch(() => '')}`);

  // ==================== 4. 确认上传 ====================
  section('4. 确认上传（服务端核实对象）');
  const confirmRes = await call('POST', `/ms/medias/${mediaId}/confirm`,
    { token, body: { duration: 125, coverUrl: upJson?.data?.rawUrl } });
  check('确认成功', confirmRes.json?.code === 200, `msg=${confirmRes.json?.msg}`);
  check('时长已写入', confirmRes.json?.data?.duration === 125,
    `duration=${confirmRes.json?.data?.duration}`);
  // ⚠️ size 现在是字符串（Long→String）
  check('★ 大小取自 MinIO 真实值', String(confirmRes.json?.data?.size) === String(videoBytes.length),
    `size=${confirmRes.json?.data?.size}, 期望=${videoBytes.length}`);

  // ==================== 5. 播放地址 ====================
  section('5. 播放地址');
  const playRes = await call('GET', `/ms/medias/${mediaId}/play-url`, { token });
  check('获取播放地址成功', playRes.json?.code === 200, `msg=${playRes.json?.msg}`);
  const playUrl = playRes.json?.data?.url;
  check('播放地址带签名', !!playUrl && playUrl.includes('X-Amz-Signature'));
  check('有效期为配置值 7200 秒', playRes.json?.data?.expire === 7200,
    `expire=${playRes.json?.data?.expire}`);

  // 关键：真的下载
  const dlRes = await fetch(playUrl).catch(() => null);
  const dlBuf = dlRes ? Buffer.from(await dlRes.arrayBuffer()) : Buffer.alloc(0);
  check('★ 用播放地址真能下载到视频内容',
    dlRes?.status === 200 && dlBuf.equals(videoBytes),
    `status=${dlRes?.status}, bytes=${dlBuf.length}/${videoBytes.length}`);

  // ★★ 无签名直访**视频**：必须被拒（403）
  //
  // 这条断言的历史值得记一笔（**它差点变成一个"把漏洞改回来"的陷阱**）：
  //   · 最初断言 `403`（bucket 私有）；
  //   · M7-2 时为了演示改成 `200` —— 因为课程封面是浏览器**直链**拉 MinIO 的，
  //     桶一私就全挂，于是把整个 bucket 开了匿名只读（清单 #B3）；
  //   · 但那同时意味着**付费视频能绕过签名直接下载**（去掉 `?X-Amz-Signature` 即可）。
  //   · M8-1 把策略**收窄**为「5 个展示类前缀 + media/ 下的图片扩展名」：
  //       - media/ 下的 **mp4** → 403（= 本条约验的）
  //       - media/ 下的 **图片**（封面）→ 仍可匿名读（否则封面会挂）
  //   → 所以断言改回 `403`。
  //
  // ⚠️ **看到这条红了，不要"按断言把桶改回公开"** —— 那是在重新打开 #B3。
  //    正确的排查顺序：① 桶策略是不是又被整体放开了（跑 `scripts/setup-minio-anonymous.py --show`）；
  //    ② 这个对象是不是**图片**（图片本就该 200，那就说明对象选错了）。
  const rawUrl = `${MINIO}/${BUCKET}/${sigRes.json?.data?.key}`;
  const rawRes = await fetch(rawUrl).catch(() => null);
  check('★★ 无签名直访**视频**被拒绝 403（M8-1 已收窄匿名读，见 #B3）',
    rawRes?.status === 403, `status=${rawRes?.status}（若是 200，说明桶又被整体开放了）`);

  // ==================== 6. 列表与分页 ====================
  section('6. 媒资分页查询');
  const listRes = await call('GET', `/ms/medias?pageNo=1&pageSize=10`, { token });
  check('分页查询成功', listRes.json?.code === 200, `msg=${listRes.json?.msg}`);
  check('返回 total / pages / list 结构',
    // ⚠️ total 现在是字符串：Long 全局序列化为 String（防雪花 id 在 JS 精度丢失）
    String(listRes.json?.data?.total ?? '').length > 0 && Array.isArray(listRes.json?.data?.list),
    `data=${JSON.stringify(listRes.json?.data)?.slice(0, 120)}`);
  const total = listRes.json?.data?.total ?? 0;
  check('能查到刚上传的媒资', total >= 1, `total=${total}`);

  // ==================== 7. 异常处理 ====================
  section('7. 异常处理');
  const notExist = await call('POST', `/ms/medias/999999/confirm`,
    { token, body: { duration: 10 } });
  check('确认不存在的媒资被拒绝', notExist.json?.code !== 200, `msg=${notExist.json?.msg}`);

  const reConfirm = await call('POST', `/ms/medias/${mediaId}/confirm`,
    { token, body: { duration: 125 } });
  check('重复确认是幂等的（不报错）', reConfirm.json?.code === 200, `msg=${reConfirm.json?.msg}`);

  const noTokenRes = await call('GET', `/ms/medias`);
  check('未登录访问 /ms/** 被网关拦截 401', noTokenRes.status === 401, `status=${noTokenRes.status}`);

  // ==================== 8. 内部接口（不被包装成 R） ====================
  section('8. 内部 Feign 接口');
  const internal = await fetch(`${MEDIA_DIRECT}/medias/internal/${mediaId}`).then((r) => r.json()).catch(() => null);
  check('★ 内部接口返回原始对象（无 R 包装）',
    internal && internal.id === mediaId && internal.code === undefined,
    `resp=${JSON.stringify(internal)?.slice(0, 120)}`);

  // ==================== 9. 删除 ====================
  section('9. 删除媒资');
  const delRes = await call('DELETE', `/ms/medias/${mediaId}`, { token });
  check('删除成功', delRes.json?.code === 200 || delRes.status === 200, `msg=${delRes.json?.msg}`);

  const afterDel = await fetch(playUrl).catch(() => null);
  check('★ 删除后 MinIO 对象真的没了（再访问 404）',
    afterDel?.status === 404 || afterDel?.status === 403,
    `status=${afterDel?.status}`);

  const delAgain = await call('DELETE', `/ms/medias/${mediaId}`, { token });
  check('重复删除不报错', delAgain.json?.code === 200 || delAgain.status === 200,
    `msg=${delAgain.json?.msg}`);

  // 清理图片
  if (fileId) {
    await call('DELETE', `/ms/files/${fileId}`, { token });
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
