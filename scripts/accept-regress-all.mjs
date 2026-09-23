#!/usr/bin/env node
/**
 * ============================================================
 * 后端验收 · 全量回归（17 个脚本，按顺序串行）
 * ------------------------------------------------------------
 * 用法：
 *   node scripts/accept-regress-all.mjs
 *   node scripts/accept-regress-all.mjs --only m5,m6
 *   node scripts/accept-regress-all.mjs --skip m6
 *   node scripts/accept-regress-all.mjs --interval=5     # 只调试时用（会踩验证码冷却）
 *
 * ------------------------------------------------------------
 * ★★ 为什么必须「串行 + 间隔 65 秒」：
 *   这些脚本共用同一批演示账号，而「发送验证码」有 **65 秒冷却**。
 *   连着跑，第二个脚本就会拿不到验证码 —— 症状是"登录失败"，
 *   看上去像功能坏了，其实只是踩了限流。
 *   （这条约定在 M6-7 的全量回归里定下来：按顺序串行，同手机号间隔 65 秒）
 *
 * ★ 「期望项数」只做**漂移提示**，不判失败：
 *   前端验收里存在"断言写在条件分支内"的情况（见《待修复问题清单》#C30），
 *   项数会随数据浮动。所以这里把它当**信号**用 ——
 *   数字变了就值得看一眼，但不该因此让整轮回归变红。
 *
 * ★ 退出码：全部通过 → 0；任何一项失败 → 1。
 * ============================================================
 */

import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
/** 每个脚本的**完整**输出落盘到 logs/regress/<name>.log —— 结果要可复核 */
const LOG_DIR = join(ROOT, 'logs', 'regress')

/**
 * 执行顺序 = M6-7 全量回归的顺序（也是数据依赖的合理顺序：先基础后业务）
 * expected 是 M6-7 记录的项数，仅用于漂移提示
 */
const ALL = [
  // ★ m1 从 25 → 27：它的注册用例补上了「清理 + 自检」两条断言（#B7）
  ['m1', 27],
  ['m2-1', 30],
  ['m2-2a', 58],
  ['m2-2b', 55],
  ['m2-2c', 63],
  ['m2-3', 44],
  ['m3-1', 46],
  ['m3-2', 39],
  ['m3-3a', 65],
  ['m3-3b', 53],
  ['m4-1', 63],
  ['m4-2', 32],
  ['m4-3', 35],
  ['m4-4', 85],
  ['m5', 95],
  ['m6', 214],
  ['auth', 28],
  // ★ M8-2b 的**主旅程 e2e** 必须最后跑：它是一条贯穿 11 个服务的完整用户旅程
  //   （注册 → 逛 → 买 → 支付 → 学 → 打卡 → 测 → 说 → 问 AI），每一步直查中间件对账。
  //   它用的是**全新注册的账号**，所以不受"验证码 65 秒冷却"影响（每次都是新手机号）。
  ['e2e', 64],
  // ★ M8-2c：异步链路 + 跨端联动（对账不变量 / MQ 拓扑守护 / 消费失败实测 / 定时任务触发）
  ['m8-2c', 28],
]

// ------------------------------------------------------------
// 参数
// ------------------------------------------------------------
const argv = process.argv.slice(2)

function takeList(flag) {
  const i = argv.indexOf(flag)
  if (i < 0) return null
  const v = argv[i + 1]
  if (!v) {
    console.error(`  ❌ ${flag} 后面要跟逗号分隔的名字，如 ${flag} m5,m6`)
    process.exit(2)
  }
  return v
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

function takeNumber(flag, fallback) {
  const hit = argv.find((a) => a.startsWith(`${flag}=`))
  if (!hit) return fallback
  const n = Number(hit.split('=')[1])
  return Number.isFinite(n) ? n : fallback
}

const only = takeList('--only')
const skip = takeList('--skip') ?? []
const intervalSec = takeNumber('--interval', 65)

const targets = ALL.filter(([name]) => {
  if (only && !only.includes(name)) return false
  if (skip.includes(name)) return false
  return true
})

if (!targets.length) {
  console.error(`  ❌ 没有要跑的脚本（可用：${ALL.map(([n]) => n).join(', ')}）`)
  process.exit(2)
}

// ------------------------------------------------------------
// ------------------------------------------------------------
// 汇总行的解析
// ------------------------------------------------------------
/**
 * ★★ 本项目的后端脚本**汇总格式并不统一**，两种都要认：
 *
 *   老脚本（m1 ~ m6）：`验收结果：通过 25 项，失败 0 项`   ← 数字在「通过」**之后**
 *   新脚本（auth）    ：`28 通过 / 0 失败`                 ← 数字在「通过」**之前**
 *
 * ★★ 这里踩过一次：我照抄了**前端**脚本的格式去解析**后端**，
 *    结果 16 个脚本**全部被误报成"中途终止"** —— 而它们其实都跑通了。
 *    （同一族的教训见手册 §四 第 18 条：口径要与实际对齐，别凭印象。）
 */
const RE_BACKEND = /通过\s*(\d+)\s*项\s*[，,/]\s*失败\s*(\d+)\s*项/
const RE_FRONTEND = /(\d+)\s*通过\s*\/\s*(\d+)\s*失败/

function parseSummary(out) {
  const b = out.match(RE_BACKEND)
  if (b) return { pass: Number(b[1]), fail: Number(b[2]) }
  const f = out.match(RE_FRONTEND)
  if (f) return { pass: Number(f[1]), fail: Number(f[2]) }
  return null
}

function runOne(name) {
  const t0 = Date.now()
  const r = spawnSync('node', [`scripts/accept-${name}.mjs`], {
    cwd: ROOT,
    shell: true, // Windows 下需要走 shell
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    env: {
      ...process.env,
      // ★ 本机有沙箱代理，不走这个会连不上 localhost 的服务
      NO_PROXY: 'localhost,127.0.0.1,::1',
      no_proxy: 'localhost,127.0.0.1,::1',
    },
  })
  const out = `${r.stdout ?? ''}\n${r.stderr ?? ''}`
  const secs = Math.round((Date.now() - t0) / 1000)
  const status = r.status

  // ★ 完整输出落盘：只留"末尾 N 行"是不够的 ——
  //   本次就因此拿不到某几个脚本的失败细节，只能重跑一遍
  try {
    mkdirSync(LOG_DIR, { recursive: true })
    writeFileSync(join(LOG_DIR, `${name}.log`), out, 'utf8')
  } catch {
    /* 落盘失败不影响判定 */
  }

  const summary = parseSummary(out)
  if (!summary) {
    // 既没抓到汇总行、退出码也不是 0 → 才算"中途终止"（超时 / 抛异常 / 服务不通…）
    return { name, ok: false, pass: null, fail: null, secs, status, out, aborted: true }
  }
  return {
    name,
    ok: summary.fail === 0,
    pass: summary.pass,
    fail: summary.fail,
    secs,
    status,
    out,
    aborted: false,
  }
}

/** 失败时打印尾部若干行（完整输出太长，尾部通常就是失败原因） */
function tailOf(text, lines = 28) {
  return text
    .split(/\r?\n/)
    .filter((l) => l.trim())
    .slice(-lines)
    .join('\n')
}

function sleep(sec) {
  if (sec <= 0) return
  // 用 Atomics.wait 做同步 sleep，保持整体是"串行脚本"而不是事件循环
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, sec * 1000)
}

// ------------------------------------------------------------
// 主流程
// ------------------------------------------------------------
console.log('============================================================')
console.log(' FitMind 后端验收 · 全量回归')
console.log(` 共 ${targets.length} 个脚本 · 脚本间间隔 ${intervalSec}s（验证码冷却）`)
console.log('============================================================')

const results = []
let totalPass = 0
let totalFail = 0

for (let i = 0; i < targets.length; i++) {
  const [name, expected] = targets[i]
  process.stdout.write(`\n[${i + 1}/${targets.length}] accept-${name} ... `)
  const r = runOne(name)
  results.push({ ...r, expected })

  if (r.aborted) {
    console.log(`❌ 中途终止（没抓到汇总行，退出码 ${r.status}）`)
  } else {
    totalPass += r.pass
    totalFail += r.fail
    // ★ 项数 = 通过 + 失败（失败的那条不重复计入通过），期望值用 M6-7 回归的总项数
    const total = r.pass + r.fail
    const drift = total === expected ? '' : `  ⚠️ 项数漂移（M6-7 记录 ${expected}，本次 ${total}）`
    console.log(`${r.ok ? '✅' : '❌'} ${r.pass} 通过 / ${r.fail} 失败 · ${r.secs}s${drift}`)
  }

  // 失败时立刻把现场打出来 —— 不要等最后才发现
  if (!r.ok) {
    console.log('  ---- 末尾输出 ----')
    console.log(
      tailOf(r.out)
        .split('\n')
        .map((l) => `  ${l}`)
        .join('\n'),
    )
  }

  if (i < targets.length - 1 && intervalSec > 0) {
    process.stdout.write(`  ⏳ 等待 ${intervalSec}s（验证码冷却）...`)
    sleep(intervalSec)
    console.log(' 继续')
  }
}

// ------------------------------------------------------------
// 汇总
// ------------------------------------------------------------
console.log('\n============================================================')
console.log(' 汇总')
console.log('============================================================')
for (const r of results) {
  const mark = r.aborted ? '❌' : r.ok ? '✅' : '❌'
  const num = r.aborted ? '—' : `${r.pass}/${r.pass + r.fail}`
  console.log(`  ${mark} accept-${r.name.padEnd(8)} ${num.padStart(10)}  ${r.secs}s`)
}
console.log('  ────────────────────────────────')
console.log(`  合计 ${totalPass} 项通过 / ${totalFail} 项失败`)
console.log(`  各脚本完整输出：logs/regress/<name>.log（排查失败时看这里，别只依赖屏幕）`)
console.log('============================================================')

process.exit(totalFail === 0 && results.every((r) => !r.aborted) ? 0 : 1)
