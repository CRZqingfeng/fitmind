#!/usr/bin/env node
/**
 * ============================================================
 * FitMind M12-1 上传增强（断点续传 + 秒传）—— 端到端验收
 * ------------------------------------------------------------
 * 前置：
 *   fm-media(8083) 已启动，MinIO(9000) 已运行且桶可用
 *
 * 用法：
 *   node scripts/accept-m12-1.mjs
 *
 * ★★ 为什么这个脚本直连 8083 而不是走网关 10010：
 *   本机 Nacos 的 gRPC 端口（9848/9849）落在 Windows 的"保留端口段"里，
 *   Docker 绑不上 → 网关起不来。而 fm-media 的鉴权是「网关验签后把用户 id
 *   放进 `user-info` 头」，服务本身只认这个头（见 fm-auth-resource-sdk 的
 *   UserInfoInterceptor / LoginAuthInterceptor）。
 *   所以**直连 + 手写 user-info** 走的是与网关完全相同的服务端代码路径，
 *   只有"网关验签"这一步被跳过。
 *   ⚠️ 这只能用于本地验证：生产环境必须经过网关，否则任何人都能伪造 user-info。
 *
 * 设计原则（本项目踩过的坑，全部前置规避）：
 *   ① 不断言"表里有多少行"这种绝对量 —— 改用「跑前记基线 + 只比本次增量」
 *   ② 断点恢复要**真的只补缺的片**，并且乱序传，不能重传 1..N 再说"恢复成功"
 *   ③ 内容完整性用 SHA-256 逐字节比，不比长度
 *   ④ 引用计数不能只看"删完还能下载"，要证"删第一个对象还在、删第二个才消失"
 *   ⑤ 越权用例必须换一个 user-info，不能拿自己的 taskId 改个 id 就断言
 * ============================================================
 */

import { createHash, randomBytes } from 'node:crypto';

const BASE = process.env.MEDIA_BASE || 'http://localhost:8083';
/**
 * ★★ 对象存储后端（M9-1 起可插拔：minio / oss，M12-1 两个后端都支持分片）
 *
 *   FM_STORAGE_TYPE=minio node scripts/accept-m12-1.mjs   （默认）
 *   FM_STORAGE_TYPE=oss   node scripts/accept-m12-1.mjs
 *
 * ★ 启动服务时必须与这里一致：用 oss 起服务、用 minio 跑脚本，
 *   第 0 段就会红（而红的原因跟功能一点关系都没有）。
 */
const STORAGE_TYPE = process.env.FM_STORAGE_TYPE || 'minio';
const MINIO = process.env.MINIO_BASE || 'http://localhost:9000';
const OSS_PUBLIC = process.env.OSS_PUBLIC_ENDPOINT || 'https://itcrz-9527.oss-cn-beijing.aliyuncs.com';
const USER = process.env.PROBE_USER || '10001';
const OTHER_USER = '10099';
const CHUNK = 5 * 1024 * 1024;

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

function headers(user, extra = {}) {
  return { 'user-info': String(user), 'token-info': 'local-probe', ...extra };
}

/** 直连端口不包 R；走网关会包。两种都能解析。 */
function unwrap(body) {
  if (body && typeof body === 'object' && body.code !== undefined && 'data' in body) return body.data;
  return body;
}

function isOk(res) {
  const b = res.json;
  if (b && typeof b === 'object' && b.code !== undefined) return b.code === 200;
  return res.status >= 200 && res.status < 300;
}

function msgOf(res) {
  return res.json?.msg || res.json?.message || `HTTP ${res.status}`;
}

async function api(method, path, { user = USER, json, form } = {}) {
  const h = headers(user);
  let body;
  if (json !== undefined) {
    h['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (form) {
    body = form;
  }
  const res = await fetch(BASE + path, {
    method,
    headers: h,
    body,
    // ★ 没有超时的 fetch 在"对端卡住"时会一直等，表现为整个脚本静默挂死 ——
    //   而人看到的是"脚本跑一半不动了"，第一反应往往是去查服务端，方向就偏了。
    signal: AbortSignal.timeout(60000),
  }).catch((e) => ({ status: 0, text: async () => e.message }));
  const text = await (res.text ? res.text() : Promise.resolve(''));
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    /* 非 JSON（比如 401 空响应）*/
  }
  return { status: res.status, json: parsed, text, headers: res.headers };
}

/**
 * 访问对象存储（签名地址/直链）。
 *
 * ★★ 必须**把响应体读干净**（arrayBuffer）或显式 cancel：
 *   只取 status 就返回，等于把一条 15MB 的响应丢在半路，连接一直占着，
 *   对象存储侧会阻塞在"写给客户端"上 —— 后续的 DELETE 就得排队。
 *   本脚本在这一条上实测踩过：同一个删除接口，正常 20ms，
 *   拖着未消费的大响应时变成 7.7 秒，验收脚本里甚至放大到 279 秒（看起来像"服务挂了"）。
 */
async function fetchObject(url) {
  if (!url) return { status: 0, contentType: '', buffer: Buffer.alloc(0) };
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(60000) });
    const buffer = Buffer.from(await res.arrayBuffer());
    return { status: res.status, contentType: res.headers.get('content-type') || '', buffer };
  } catch (e) {
    return { status: 0, contentType: '', buffer: Buffer.alloc(0), error: e.message };
  }
}

async function sha256(buf) {
  return createHash('sha256').update(buf).digest('hex');
}

/** 分片上传：返回服务端回执（失败时抛错，避免"静默 0 进度"） */
async function putPart(taskId, partNumber, buf, { user = USER, contentType = 'application/octet-stream' } = {}) {
  const form = new FormData();
  form.append('chunk', new Blob([buf], { type: contentType }), `part-${partNumber}`);
  const res = await api('PUT', `/files/uploads/${taskId}/parts/${partNumber}`, { user, form });
  if (!isOk(res)) throw new Error(`第 ${partNumber} 片上传失败：${msgOf(res)}`);
  return unwrap(res.json);
}

function makeFile(sizes) {
  const buffers = sizes.map((n) => randomBytes(n));
  const all = Buffer.concat(buffers);
  return { buffers, all };
}

async function main() {
  console.log('========================================');
  console.log(' FitMind M12-1 断点续传 + 秒传 —— 端到端验收');
  console.log('========================================');
  console.log(`服务：${BASE}   存储后端：${STORAGE_TYPE}   探针用户：${USER}`);

  // ==================== 0. 环境 ====================
  section('0. 环境');
  if (STORAGE_TYPE === 'oss') {
    // OSS 没有"存活"概念（托管服务）；匿名列桶返回 403 = 服务可达 + 权限在生效
    const r = await fetch(`${OSS_PUBLIC}/`).then((x) => x.status).catch(() => 0);
    check('OSS 端点可达且匿名列桶被拒绝（403）', r === 403, `HTTP ${r} ← ${OSS_PUBLIC}`);
  } else {
    const live = await fetch(`${MINIO}/minio/health/live`).then((r) => r.status).catch(() => 0);
    check('MinIO 健康检查通过', live === 200, `HTTP ${live}`);
  }

  // ==================== 1. 老路径不能坏（A1） ====================
  section('1. 老的一次性上传（回归 A1）');
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    'base64',
  );
  const oldForm = new FormData();
  oldForm.append('file', new Blob([png], { type: 'image/png' }), 'm12-regress.png');
  const oldUp = await api('POST', '/files?bizType=common', { form: oldForm });
  const oldFile = unwrap(oldUp.json);
  check('老接口 POST /files 仍然可用', isOk(oldUp), msgOf(oldUp));
  check('返回文件 id 与签名地址', !!oldFile?.id && !!oldFile?.url, JSON.stringify(oldFile)?.slice(0, 100));
  const oldDl = await fetchObject(oldFile?.url);
  check('老路径上传的图片能下载且内容一致', oldDl.buffer.equals(png),
    `bytes=${oldDl.buffer.length}/${png.length}`);

  // ==================== 2. 初始化：分片大小夹紧（A2 + D1） ====================
  section('2. 初始化分片上传（A2 / D1：chunkSize 夹紧）');
  const sizes = [CHUNK, CHUNK, CHUNK, 1000];
  const fileA = makeFile(sizes);
  const hashA = await sha256(fileA.all);
  console.log(`     文件：${fileA.all.length} 字节  sha256=${hashA.slice(0, 16)}…`);

  const initRes = await api('POST', '/files/uploads', {
    json: { hash: hashA, filename: '深蹲标准动作.mp4', size: fileA.all.length, bizType: 'action-cover', chunkSize: 1024 },
  });
  const taskA = unwrap(initRes.json);
  check('init 成功', isOk(initRes), msgOf(initRes));
  check('★★ chunkSize=1024 被夹到 5MiB（S3 硬约束，违反只会在合并时报错）',
    taskA?.chunkSize === CHUNK, `chunkSize=${taskA?.chunkSize}`);
  check('chunkTotal = ceil(size/chunkSize)', taskA?.chunkTotal === 4, `chunkTotal=${taskA?.chunkTotal}`);
  check('首次 init 未命中秒传', taskA?.instant === false, `instant=${taskA?.instant}`);
  check('目标 key 由 hash 派生（秒传可预测）', String(taskA?.objectKey || '').includes(hashA),
    `objectKey=${taskA?.objectKey}`);
  check('已传分片为空', Array.isArray(taskA?.uploadedChunks) && taskA.uploadedChunks.length === 0);

  // ==================== 3. 分片上传与进度（A3） ====================
  section('3. 分片上传 + 进度（A3，乱序传）');
  const taskId = taskA.taskId;
  const p1 = await putPart(taskId, 1, fileA.buffers[0]);
  check('第 1 片上传成功，进度回执正确', p1?.partNumber === 1 && p1?.uploadedChunks?.includes(1),
    JSON.stringify(p1));
  const p2 = await putPart(taskId, 2, fileA.buffers[1]);
  check('第 2 片后进度 = 66%', p2?.uploadedPercent === 66, `percent=${p2?.uploadedPercent}`);
  // ★ 乱序：真实网络里分片是并发的，恢复时更不会按顺序补
  const p4 = await putPart(taskId, 4, fileA.buffers[3]);
  check('★ 第 4 片（乱序）上传成功', p4?.uploadedChunks?.includes(4), JSON.stringify(p4?.uploadedChunks));

  // ==================== 4. 断点：重新 init 复用同一任务（A4） ====================
  section('4. 断点恢复：同一 hash 重新 init（A4）');
  const reInit = await api('POST', '/files/uploads', {
    json: { hash: hashA, filename: '深蹲标准动作.mp4', size: fileA.all.length, bizType: 'action-cover' },
  });
  const taskA2 = unwrap(reInit.json);
  check('★★ 返回同一个 taskId（没有新建任务）', String(taskA2?.taskId) === String(taskId),
    `first=${taskId} second=${taskA2?.taskId}`);
  check('★★ 已传分片来自服务端状态（不是靠客户端记忆）', JSON.stringify(taskA2?.uploadedChunks) === JSON.stringify([1, 2, 4]),
    `uploadedChunks=${JSON.stringify(taskA2?.uploadedChunks)}`);
  check('秒传未误命中（本任务还没合并）', taskA2?.instant === false);

  // ==================== 5. 缺片必须拒绝（A6） ====================
  section('5. 缺片合并必须被拒（A6）');
  const badComplete = await api('POST', `/files/uploads/${taskId}/complete`);
  check('缺第 3 片时 complete 被拒绝', !isOk(badComplete), msgOf(badComplete));
  check('拒绝理由指出了缺哪片', String(msgOf(badComplete)).includes('3') || String(msgOf(badComplete)).includes('分片'),
    msgOf(badComplete));

  // ==================== 6. 补齐并合并 + 内容校验（A5） ====================
  section('6. 只补缺的片再合并（A5）');
  await putPart(taskId, 3, fileA.buffers[2]);
  const done = await api('POST', `/files/uploads/${taskId}/complete`);
  const fileA1 = unwrap(done.json);
  check('补齐后 complete 成功', isOk(done) && !!fileA1?.id, msgOf(done));
  check('文件大小与原始一致', String(fileA1?.size) === String(fileA.all.length),
    `size=${fileA1?.size} 期望=${fileA.all.length}`);

  const dl = await fetchObject(fileA1?.url);
  const dlHash = await sha256(dl.buffer);
  check('★★ 下载回来的内容 SHA-256 与原始完全相同（逐字节，不比长度）',
    dl.status === 200 && dlHash === hashA, `status=${dl.status} hash=${dlHash.slice(0, 12)}…`);
  check('★ 合并后的 Content-Type 正确（否则 <video> 不渲染，直链却是 200）',
    dl.contentType.includes('video/mp4'), `content-type=${dl.contentType}`);

  // 分片临时对象应已清理：再传同号分片到已完成任务必须被拒（也侧证任务已关闭）
  const latePart = await api('PUT', `/files/uploads/${taskId}/parts/1`, {
    form: (() => {
      const f = new FormData();
      f.append('chunk', new Blob([fileA.buffers[0]]), 'p1');
      return f;
    })(),
  });
  check('已完成的任务不能再收分片', !isOk(latePart), msgOf(latePart));

  // ==================== 7. complete 幂等（A10） ====================
  section('7. complete 幂等（A10）');
  const twice = await api('POST', `/files/uploads/${taskId}/complete`);
  const fileA1Again = unwrap(twice.json);
  check('重复 complete 返回同一文件 id（幂等）',
    isOk(twice) && String(fileA1Again?.id) === String(fileA1?.id),
    `first=${fileA1?.id} second=${fileA1Again?.id} msg=${msgOf(twice)}`);

  // ==================== 8. 秒传 + 引用计数（A7/A8/A9） ====================
  section('8. 秒传与引用计数（A7/A8/A9）');
  const instantRes = await api('POST', '/files/uploads', {
    json: { hash: hashA, filename: '同一份动作视频-再传一次.mp4', size: fileA.all.length, bizType: 'action-cover' },
  });
  const instant = unwrap(instantRes.json);
  check('★★ 第二次上传同一内容 → instant=true（0 字节上传）', isOk(instantRes) && instant?.instant === true,
    JSON.stringify(instant)?.slice(0, 120));
  check('秒传直接返回文件记录', !!instant?.file?.id, JSON.stringify(instant?.file));
  check('秒传返回的是新的一条 file 记录（不是复用上一条）',
    String(instant?.file?.id) !== String(fileA1?.id), `first=${fileA1?.id} instant=${instant?.file?.id}`);
  check('★★ 两条记录指向同一个对象 key（这才是"不重复占存储"）',
    instant?.file?.rawUrl === fileA1?.rawUrl, `a=${fileA1?.rawUrl} b=${instant?.file?.rawUrl}`);

  const fileA2 = instant?.file;
  const del1 = await api('DELETE', `/files/${fileA1.id}`);
  check('删除第 1 条逻辑记录成功', isOk(del1), msgOf(del1));
  const stillThere = await fetchObject(fileA1?.url);
  check('★★ 删掉一条后物理对象仍在（另一条还能用）', stillThere.status === 200,
    `status=${stillThere.status}（若 404 说明删的是共享字节，引用计数没生效）`);
  const stillReadable = await fetchObject(fileA2?.url);
  check('★ 第二条记录仍能下载', stillReadable.status === 200 && stillReadable.buffer.length === fileA.all.length,
    `status=${stillReadable.status} bytes=${stillReadable.buffer.length}`);

  const del2 = await api('DELETE', `/files/${fileA2.id}`);
  check('删除第 2 条逻辑记录成功', isOk(del2), msgOf(del2));
  const gone = await fetchObject(fileA1?.url);
  check('★★ 最后一个引用删除后物理对象才消失（404/403）',
    gone.status === 404 || gone.status === 403, `status=${gone.status}（若仍 200 说明对象永远删不掉）`);

  // ==================== 9. 取消上传（A11） ====================
  section('9. 取消上传（A11）');
  const fileB = makeFile([CHUNK, 512]);
  const hashB = await sha256(fileB.all);
  const initB = await api('POST', '/files/uploads', {
    json: { hash: hashB, filename: 'cancel-me.mp4', size: fileB.all.length, bizType: 'common' },
  });
  const taskB = unwrap(initB.json);
  await putPart(taskB.taskId, 1, fileB.buffers[0]);
  const abortRes = await api('DELETE', `/files/uploads/${taskB.taskId}`);
  check('abort 成功', isOk(abortRes), msgOf(abortRes));
  const afterAbort = await api('POST', `/files/uploads/${taskB.taskId}/complete`);
  check('取消后不能再合并', !isOk(afterAbort), msgOf(afterAbort));
  const progressAfterAbort = await api('GET', `/files/uploads/${taskB.taskId}`);
  check('取消后仍可查任务（状态可追溯）', isOk(progressAfterAbort) && !!unwrap(progressAfterAbort.json)?.taskId);

  // ==================== 10. 越权（A12） ====================
  section('10. 越权校验（A12）');
  const otherProgress = await api('GET', `/files/uploads/${taskB.taskId}`, { user: OTHER_USER });
  check('★ 别人的任务不能查进度', !isOk(otherProgress), msgOf(otherProgress));
  const otherPart = await api('PUT', `/files/uploads/${taskB.taskId}/parts/2`, {
    user: OTHER_USER,
    form: (() => {
      const f = new FormData();
      f.append('chunk', new Blob([fileB.buffers[1]]), 'p2');
      return f;
    })(),
  });
  check('★ 别人的任务不能塞分片', !isOk(otherPart), msgOf(otherPart));
  const otherComplete = await api('POST', `/files/uploads/${taskB.taskId}/complete`, { user: OTHER_USER });
  check('★ 别人的任务不能合并', !isOk(otherComplete), msgOf(otherComplete));

  // ==================== 11. 参数校验 ====================
  section('11. 参数校验');
  const badHash = await api('POST', '/files/uploads', {
    json: { hash: 'not-a-sha256', filename: 'x.mp4', size: 100, bizType: 'common' },
  });
  check('hash 非 64 位 hex 被拒绝', !isOk(badHash), msgOf(badHash));
  const zeroSize = await api('POST', '/files/uploads', {
    json: { hash: 'a'.repeat(64), filename: 'x.mp4', size: 0, bizType: 'common' },
  });
  check('size=0 被拒绝', !isOk(zeroSize), msgOf(zeroSize));
  const wrongSizePart = await api('PUT', `/files/uploads/${taskB.taskId}/parts/2`, {
    form: (() => {
      const f = new FormData();
      // 第 2 片应为 512 字节，这里故意给 100 字节
      f.append('chunk', new Blob([fileB.buffers[1].subarray(0, 100)]), 'p2-wrong');
      return f;
    })(),
  });
  check('★ 分片大小不符被拒绝（不然后面合并出来的对象和声明 size 对不上）',
    !isOk(wrongSizePart), msgOf(wrongSizePart));

  // ==================== 12. 视频媒资 target=media ====================
  section('12. 视频走分片上传（target=media）');
  const fileC = makeFile([CHUNK, 2000]);
  const hashC = await sha256(fileC.all);
  const initC = await api('POST', '/files/uploads', {
    json: { hash: hashC, filename: '深蹲考核视频.mp4', size: fileC.all.length, bizType: 'media', target: 'media' },
  });
  const taskC = unwrap(initC.json);
  check('视频任务初始化成功（target=media）', isOk(initC) && !!taskC?.taskId, msgOf(initC));
  check('对象落在 media/ 前缀（付费内容，不能匿名读）',
    String(taskC?.objectKey || '').startsWith('media/'), `key=${taskC?.objectKey}`);
  await putPart(taskC.taskId, 1, fileC.buffers[0]);
  await putPart(taskC.taskId, 2, fileC.buffers[1]);
  const doneC = await api('POST', `/files/uploads/${taskC.taskId}/complete`);
  const mediaFile = unwrap(doneC.json);
  check('合并成功并返回 mediaId（前端可直接走播放/引用逻辑）',
    isOk(doneC) && !!mediaFile?.mediaId, `resp=${JSON.stringify(mediaFile)?.slice(0, 120)}`);

  const playRes = await api('GET', `/medias/${mediaFile?.mediaId}/play-url`);
  const playUrl = unwrap(playRes.json)?.url;
  check('用 mediaId 能拿到播放地址', isOk(playRes) && !!playUrl, msgOf(playRes));
  const playDl = await fetchObject(playUrl);
  check('★ 播放地址能真的下载到视频且内容一致',
    playDl.status === 200 && (await sha256(playDl.buffer)) === hashC,
    `status=${playDl.status} bytes=${playDl.buffer.length}/${fileC.all.length}`);

  /**
   * ★★ 视频必须"只有签名能看"：`media/` 前缀在桶策略里是**拒绝匿名读**的。
   * 这条断言对两个后端都成立，而且它防的是一类**看起来功能全好**的事故：
   * 桶策略一放开，功能测试全绿，付费视频却能被任何人直接下载。
   */
  const rawVideo = await fetchObject(mediaFile?.rawUrl);
  check('★★ 视频直链（无签名）被拒绝 403（media/ 前缀不允许匿名读）',
    rawVideo.status === 403, `status=${rawVideo.status} ← ${String(mediaFile?.rawUrl).slice(0, 70)}`);

  const instantC = await api('POST', '/files/uploads', {
    json: { hash: hashC, filename: '同一段考核视频.mp4', size: fileC.all.length, bizType: 'media', target: 'media' },
  });
  const instantMedia = unwrap(instantC.json);
  check('同一用户重传同一视频 → 秒传命中', instantMedia?.instant === true, msgOf(instantC));
  check('★★ 秒传返回新的 mediaId（两个学员各一条媒资记录，删互不影响）',
    !!instantMedia?.mediaId && String(instantMedia.mediaId) !== String(mediaFile?.mediaId),
    `first=${mediaFile?.mediaId} second=${instantMedia?.mediaId}`);

  // ★★★ 私有前缀的秒传必须拒绝跨用户：否则知道 hash 就能拿到别人的付费视频
  const otherInstant = await api('POST', '/files/uploads', {
    user: OTHER_USER,
    json: { hash: hashC, filename: '别人的视频.mp4', size: fileC.all.length, bizType: 'media', target: 'media' },
  });
  const otherVo = unwrap(otherInstant.json);
  check('★★ media/ 是私有前缀 → 别人不能靠 hash 秒传（只能老老实实传）',
    isOk(otherInstant) && otherVo?.instant === false,
    `instant=${otherVo?.instant}（若为 true，说明私有前缀的秒传越权了）`);
  if (otherVo?.taskId) {
    await api('DELETE', `/files/uploads/${otherVo.taskId}`, { user: OTHER_USER });
  }

  // ==================== 13. 清理 ====================
  section('12. 清理');
  const delOld = oldFile?.id ? await api('DELETE', `/files/${oldFile.id}`) : { status: 0 };
  check('清理老接口上传的图片', isOk(delOld), msgOf(delOld));

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
