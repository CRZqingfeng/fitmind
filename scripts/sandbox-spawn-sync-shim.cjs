'use strict';

/**
 * `spawnSync` 沙箱兼容层（WorkBuddy 沙箱 / LiteSandbox）
 *
 * ## 为什么需要它
 *
 * 本沙箱**只拦"阻塞式"子进程**：`spawnSync` / `execSync` 一律返回 `EBUSY`
 * （`dangerouslyDisableSandbox` 也无效 —— 拦截在进程级注入，不在文件系统沙箱层）。
 * 而**异步**的 `spawn` / `execFile` **完全正常**（2026-10-11 逐项实测）。
 *
 * ★★ 最危险的地方是它的失败方式：**不抛异常**，
 *    返回 `{ error: {code:'EBUSY'}, status: null, stdout: undefined }` ——
 *    于是 `r.stdout?.toString() ?? ''` 会拿到**空串**，而空串恰好满足
 *    「期望为空 / 为 0」的断言 → **静默假绿**（详见《本地环境排障手册》§3.40(b)）。
 *
 * ## 它怎么解决
 *
 * 用**异步 spawn**（可用）在 **Worker 线程**里跑子进程（Worker 有自己的事件循环），
 * 主线程用 `Atomics.wait` 阻塞等待，结果通过 **SharedArrayBuffer** 回传。
 * 对外仍是**同步**的 `spawnSync`，因此 `scripts/accept-*.mjs` 里那些
 * 按同步写法调 `mysql` / `docker` / `curl` 的断言**一行都不用改**。
 *
 * ## 用法
 *
 *     node --require ./scripts/sandbox-spawn-sync-shim.cjs scripts/accept-m6.mjs
 *
 * ★ **加载时自检**：原生 `spawnSync` 本来就能用（例如你在自己终端里跑）时，
 *   本模块**什么都不做** —— 所以可以无脑加上，不改变正常环境的行为。
 * ★ **不会静默挂死**：`Atomics.wait` 带超时；Worker 内部任何异常都会先唤醒主线程。
 */

const cp = require('node:child_process');
const { Worker, isMainThread } = require('node:worker_threads');

// ★ Worker 会继承 execArgv（于是也会加载本文件）。Worker 里不需要这层兼容，
//   直接在子线程里跳过 —— 否则每个 Worker 都会跑一遍自检、打一遍横幅。
if (!isMainThread) {
  module.exports = {};
  return;
}

/** 单个流最多回传多少字节（accept 脚本输出都很小，2MB 足够） */
const STREAM_CAP = 2 * 1024 * 1024;
/** 错误信息（code + message）/ signal 的回传上限 */
const ERR_CAP = 8 * 1024;
const HEADER_BYTES = 64;
const PAYLOAD_BYTES = STREAM_CAP * 2 + ERR_CAP;
/** 单次子进程最多等多久（防止任何意外导致永久阻塞） */
const WAIT_TIMEOUT_MS = 120000;

/** Worker 里执行：在**自己的事件循环**里跑异步 spawn，把结果写进共享内存 */
const WORKER_SRC = `
const { workerData } = require('node:worker_threads');
const { spawn } = require('node:child_process');

const { command, args, options, header, payload, input, streamCap, errCap } = workerData;
const H = new Int32Array(header);
const B = new Uint8Array(payload);

const put = (bytes, offset, cap) => {
  const n = Math.min(bytes.length, cap);
  B.set(bytes.subarray(0, n), offset);
  return n;
};

/** 唤醒主线程 —— ★ 必须保证"无论发生什么"都会被调用一次，否则主线程永久阻塞 */
let woken = false;
const wake = () => {
  if (woken) return;
  woken = true;
  Atomics.store(H, 0, 1);
  Atomics.notify(H, 0);
};

const fail = (code, message) => {
  H[1] = -1;
  H[2] = 1;
  H[5] = put(Buffer.from(JSON.stringify({ code: code || 'SPAWN_ERROR', message: message || '' })), streamCap * 2, errCap);
  wake();
};

// 兜底：Worker 里任何未捕获异常都要先唤醒主线程，否则主线程会一直等
process.on('uncaughtException', (e) => fail(e && e.code, (e && e.message) || String(e)));
process.on('unhandledRejection', (e) => fail(e && e.code, (e && e.message) || String(e)));

const main = () => {
  let child;
  try {
    child = spawn(command, args, options);
  } catch (e) {
    return fail(e && e.code, (e && e.message) || String(e));
  }

  const out = [];
  const err = [];
  if (child.stdout) child.stdout.on('data', (d) => out.push(d));
  if (child.stderr) child.stderr.on('data', (d) => err.push(d));
  if (child.stdin) {
    if (input === null || input === undefined) child.stdin.end();
    else child.stdin.end(Buffer.from(input));
  }

  let settled = false;
  const settle = (status, signal, error) => {
    if (settled) return;
    settled = true;
    H[1] = (status === null || status === undefined) ? -1 : status;
    H[3] = put(Buffer.concat(out), 0, streamCap);
    H[4] = put(Buffer.concat(err), streamCap, streamCap);
    const meta = {};
    if (signal) meta.signal = signal;
    if (error) { meta.code = error.code || 'CHILD_ERROR'; meta.message = error.message || String(error); }
    if (Object.keys(meta).length) H[5] = put(Buffer.from(JSON.stringify(meta)), streamCap * 2, errCap);
    H[2] = error ? 1 : 0;
    wake();
  };

  child.on('error', (e) => settle(null, null, e));
  child.on('close', (code, signal) => settle(code, signal, null));
};

try { main(); } catch (e) { fail(e && e.code, (e && e.message) || String(e)); }
`;

/** 只把**可结构化克隆**的选项传给 Worker（本项目调用点的选项都是 `{ encoding: 'utf8' }`） */
function usableOptions(raw) {
  const o = raw || {};
  const out = { stdio: ['pipe', 'pipe', 'pipe'] };
  if (o.cwd) out.cwd = o.cwd;
  if (o.env) out.env = o.env;
  if (o.shell !== undefined) out.shell = o.shell;
  if (o.windowsHide !== undefined) out.windowsHide = o.windowsHide;
  if (o.timeout !== undefined) out.timeout = o.timeout;
  if (o.killSignal !== undefined) out.killSignal = o.killSignal;
  return out;
}

function spawnSyncReplacement(command, args = [], options = {}) {
  const header = new SharedArrayBuffer(HEADER_BYTES);
  const payload = new SharedArrayBuffer(PAYLOAD_BYTES);
  const H = new Int32Array(header);

  const worker = new Worker(WORKER_SRC, {
    eval: true,
    workerData: {
      command: String(command),
      args: Array.isArray(args) ? args.map(String) : [],
      options: usableOptions(options),
      header,
      payload,
      input: options.input === undefined ? null : String(options.input),
      streamCap: STREAM_CAP,
      errCap: ERR_CAP,
    },
  });

  const waitResult = Atomics.wait(H, 0, 0, WAIT_TIMEOUT_MS);
  void worker.terminate();

  const bytes = new Uint8Array(payload);
  const enc = options.encoding;
  const decode = (buf) => (enc && enc !== 'buffer' ? buf.toString(enc === true ? 'utf8' : enc) : buf);

  const stdout = decode(Buffer.from(bytes.subarray(0, Math.max(0, H[3]))));
  const stderr = decode(Buffer.from(bytes.subarray(STREAM_CAP, STREAM_CAP + Math.max(0, H[4]))));

  let error;
  let signal = null;
  if (H[5] > 0) {
    try {
      const meta = JSON.parse(
        Buffer.from(bytes.subarray(STREAM_CAP * 2, STREAM_CAP * 2 + H[5])).toString('utf8'),
      );
      if (meta.signal) signal = meta.signal;
      if (meta.code) {
        error = new Error(`spawnSync ${command} 失败：${meta.message}`);
        error.code = meta.code;
      }
    } catch (e) { /* meta 不完整时忽略 */ }
  }
  if (waitResult === 'timed-out') {
    error = new Error(`spawnSync ${command}：兼容层等待子进程超时（${WAIT_TIMEOUT_MS}ms）`);
    error.code = 'ETIMEDOUT';
  }

  return {
    pid: 0,
    output: [null, stdout, stderr],
    stdout,
    stderr,
    status: error ? null : H[1],
    signal,
    error,
  };
}

/**
 * `execSync` / `execFileSync` 走的是 Node **内部**的同步 spawn，不经过 `cp.spawnSync`，
 * 所以必须单独替换。本项目现有脚本只用了 `spawnSync`，这里一并补上是为了
 * 以后有人写 `execSync` 时**不会踩同一个坑**（它的报错同样是 EBUSY）。
 */
function execSyncReplacement(command, options = {}) {
  const r = spawnSyncReplacement(command, [], { ...options, shell: true });
  if (r.error) throw r.error;
  if (r.status !== 0) {
    const e = new Error(`Command failed: ${command}\n${r.stderr || ''}`);
    e.status = r.status;
    e.stdout = r.stdout;
    e.stderr = r.stderr;
    throw e;
  }
  return r.stdout;
}

function execFileSyncReplacement(file, args = [], options = {}) {
  const r = spawnSyncReplacement(file, args, options);
  if (r.error) throw r.error;
  if (r.status !== 0) {
    const e = new Error(`Command failed: ${file} ${args.join(' ')}\n${r.stderr || ''}`);
    e.status = r.status;
    e.stdout = r.stdout;
    e.stderr = r.stderr;
    throw e;
  }
  return r.stdout;
}

// ---- 自检：原生可用就什么都不做（保证正常环境零影响） ----
let nativeWorks = false;
try {
  const probe = cp.spawnSync(process.execPath, ['-e', '0'], { encoding: 'utf8' });
  nativeWorks = !probe.error && probe.status === 0;
} catch (e) {
  nativeWorks = false;
}

if (nativeWorks) {
  if (process.env.SANDBOX_SPAWN_SHIM_VERBOSE) {
    process.stderr.write('[spawn-sync-shim] 原生 spawnSync 可用，未接管。\n');
  }
} else {
  cp.spawnSync = spawnSyncReplacement;
  cp.execSync = execSyncReplacement;
  cp.execFileSync = execFileSyncReplacement;
  if (process.env.SANDBOX_SPAWN_SHIM_VERBOSE) {
    process.stderr.write('[spawn-sync-shim] ★ 原生 spawnSync 被沙箱拦截（EBUSY），已接管为 Worker + 共享内存实现。\n');
  }
}
