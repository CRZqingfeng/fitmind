/**
 * 前端验收**总脚本**：串行跑 M7-2 ~ M7-8，最后出一张汇总表。
 *
 * 运行：
 *   cd frontend && npm run accept:all
 *   npm run accept:all -- --only m7-3,m7-7     # 只跑指定的几个（排查时用）
 *   npm run accept:all -- --skip m7-3          # 跳过指定的几个
 *
 * 为什么要有它（而不是手动一个个跑）：
 *   ① 每个里程碑的验收脚本都是**独立进程**，跑完就没了 ——
 *      「这一轮改动有没有影响别的页面」这件事，只有把它们**串起来跑一遍**才能回答。
 *      按需引入（M7-9）就是靠这个才发现 3 处断言脆弱点的。
 *   ② ★ **必须串行**：这些脚本会写同一套演示数据（下单、发券、发站内信…），
 *      并行跑会互相污染，失败还很难查。
 *   ③ 依赖：后端 15 个服务 + 两个 dev server（5173/5174）都要在。
 *
 * ★ 退出码语义：全部通过 → 0；**任何一项失败 → 1**
 *   （这样 CI / 脚本调用方可以直接用退出码判断，不用去解析文本）
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const FRONTEND = resolve(HERE, '..')
/** 逐脚本完整输出落盘目录（排查失败时看这里，别只依赖屏幕） */
const REGRESS_DIR = resolve(FRONTEND, '..', 'logs', 'regress-web')

/** 按里程碑顺序（也是数据依赖的合理顺序：先基础后业务） */
// ★★ m7-1 必须在列：它是唯一**端到端**验证「401 无感续期」的脚本
//   （主动往 localStorage 塞坏 token → 刷新页面 → 断言真的调了 refresh 并恢复）。
//   历史上它**不在**这个列表里 —— 而 M8-1 把 access 从 30 天改成 30 分钟后，
//   续期从「几乎不会被触发的死代码」变成了**常规路径**，更不能漏。
//
// ★★ M8-2a 也必须在列：它是**唯一**在「同一个浏览器上下文」里同时当学员与管理员的脚本，
//   验的是「两端的 refreshToken Cookie 不会互相覆盖」（#A2）。
//   其余脚本都给每个身份开独立 Context —— 而那种隔离**恰好掩盖**了这个 bug
//   （Cookie 按 domain 隔离、不含端口；localStorage 按 origin 隔离、含端口）。
const ALL = ['m7-1', 'm7-2', 'm7-3', 'm7-4', 'm7-5', 'm7-6', 'm7-7', 'm7-8', 'm7-10', 'm7-12a', 'm7-12b', 'm7-12c', 'm8-2a', 'm8-2c']

// ------------------------------------------------------------
// 参数
// ------------------------------------------------------------
const argv = process.argv.slice(2)
function takeList(flag) {
  const i = argv.indexOf(flag)
  if (i < 0) return null
  const v = argv[i + 1]
  if (!v) {
    console.error(`  ❌ ${flag} 后面要跟逗号分隔的里程碑，如 ${flag} m7-3,m7-7`)
    process.exit(2)
  }
  return v
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    // ★ 允许两种写法：带前缀（m7-3 / m8-2a）或只写序号（3 → m7-3）
    .map((s) => (/^m\d+-/.test(s) ? s : `m7-${s}`))
}

const only = takeList('--only')
const skip = takeList('--skip') ?? []

const targets = (only ?? ALL).filter((n) => !skip.includes(n))
const unknown = targets.filter((n) => !ALL.includes(n))
if (unknown.length) {
  console.error(`  ❌ 不认识：${unknown.join(', ')}（可用：${ALL.join(', ')}）`)
  process.exit(2)
}
if (!targets.length) {
  console.error('  ❌ 没有要跑的里程碑')
  process.exit(2)
}

const COLOR = process.stdout.isTTY
const c = (code, t) => (COLOR ? `\u001b[${code}m${t}\u001b[0m` : t)
const green = (t) => c(32, t)
const red = (t) => c(31, t)
const dim = (t) => c(90, t)

// ------------------------------------------------------------
// 解析每个脚本的汇总行
//   M7-8 是「M7-8 验收结果：42 通过 / 0 失败」
//   M7-5 是「 结果：36 通过 / 0 失败」
//   → 统一用「N 通过 / M 失败」这条来抓，别去认前缀
//
// ★★ 但**两种口径都要认**（与后端 runner 对齐）：
//   · 前端口径：`25 通过 / 0 失败`（数字在「通过」**之前**）
//   · 后端口径：`验收结果：通过 25 项，失败 0 项`（数字在「通过」**之后**）
//   ⚠️ 只认一种的代价是**把通过的脚本误报成「异常终止」** —— M8-2c 就这么被误报过
//     （前端脚本写了后端格式；《清单》#C32 是反过来的那一次）。
//     ★ 解析器该宽容，约定该统一。
// ------------------------------------------------------------
const RE_RESULT = /通过\s*(\d+)\s*项\s*[，,\/]\s*失败\s*(\d+)\s*项/ // 后端口径
const RE_RESULT_FE = /(\d+)\s*通过\s*\/\s*(\d+)\s*失败/ // 前端口径

function runOne(name) {
  const t0 = Date.now()
  const r = spawnSync('npm', ['run', `accept:${name}`], {
    cwd: FRONTEND,
    shell: true, // Windows 下 npm 是 npm.cmd，必须走 shell
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    env: {
      ...process.env,
      // ★ 本机有沙箱代理（http_proxy），不走这个会连不上 localhost 的 dev server
      NO_PROXY: 'localhost,127.0.0.1,::1',
      no_proxy: 'localhost,127.0.0.1,::1',
    },
  })
  const out = `${r.stdout ?? ''}\n${r.stderr ?? ''}`
  const secs = ((Date.now() - t0) / 1000).toFixed(0)

  // ★★ 每个脚本的**完整输出落盘**。
  //    为什么必须做：runner 只在屏幕上打一行汇总，**失败细节要等全部跑完才打印**；
  //    中途去看日志时只能看到一个"N 通过 / M 失败"，排查还得重跑一遍。
  //    （后端 runner 在 M8-2a 就补了这一条，前端这边当时漏了 —— 见清单 #C32 的同族。）
  try {
    mkdirSync(REGRESS_DIR, { recursive: true })
    writeFileSync(join(REGRESS_DIR, `${name}.log`), out, 'utf8')
  } catch {
    /* 落盘失败不影响验收结论 */
  }

  const m = out.match(RE_RESULT) ?? out.match(RE_RESULT_FE)
  if (!m) {
    // 没抓到汇总行 = 脚本中途炸了（超时 / 断言抛异常 / 端口不通…）
    //   ⚠️ 也可能是**汇总格式又变了** —— 先看落盘的 logs/regress-web/<name>.log 的末尾
    return { name, ok: false, pass: null, fail: null, secs, out, aborted: true }
  }
  const pass = Number(m[1])
  const fail = Number(m[2])
  return { name, ok: fail === 0, pass, fail, secs, out, aborted: false }
}

/** 失败时打印最后若干行（完整输出太长，尾部通常就是失败原因） */
function tailOf(text, lines = 24) {
  const arr = text.split(/\r?\n/).filter((l) => l.trim())
  return arr.slice(-lines).join('\n')
}

// ------------------------------------------------------------
// 主流程
// ------------------------------------------------------------
console.log('')
console.log('='.repeat(78))
console.log(`  前端端到端验收（共 ${targets.length} 个里程碑：${targets.join(', ')}）`)
console.log('='.repeat(78))
console.log(dim('  前置：后端 15 服务 + dev server 5173/5174；建议先跑 node scripts/seed-demo-data.mjs --stats'))
console.log('')

const results = []
for (const name of targets) {
  process.stdout.write(`  ▶ ${name} 运行中…`)
  const r = runOne(name)
  results.push(r)
  process.stdout.write('\r' + ' '.repeat(30) + '\r')
  if (r.aborted) console.log(`  ${red('✗')} ${name.padEnd(6)} ${red('异常终止')}  ${dim(r.secs + 's')}`)
  else if (r.ok) console.log(`  ${green('✓')} ${name.padEnd(6)} ${r.pass} 通过 / 0 失败  ${dim(r.secs + 's')}`)
  else console.log(`  ${red('✗')} ${name.padEnd(6)} ${r.pass} 通过 / ${red(String(r.fail))} 失败  ${dim(r.secs + 's')}`)
}

// ---------- 汇总 ----------
const totalPass = results.reduce((a, r) => a + (r.pass ?? 0), 0)
const totalFail = results.reduce((a, r) => a + (r.fail ?? 0), 0)
const bad = results.filter((r) => !r.ok)

console.log('')
console.log('='.repeat(78))
console.log('  汇总')
console.log('='.repeat(78))
console.log(`  ${'里程碑'.padEnd(10)}${'通过'.padStart(6)}${'失败'.padStart(8)}   耗时`)
console.log('  ' + '-'.repeat(44))
for (const r of results) {
  const tag = r.aborted ? '异常终止' : r.ok ? green('通过') : red('失败')
  const p = r.aborted ? '-' : String(r.pass)
  const f = r.aborted ? '-' : String(r.fail)
  console.log(`  ${r.name.padEnd(10)}${p.padStart(6)}${f.padStart(8)}   ${r.secs}s  ${tag}`)
}
console.log('  ' + '-'.repeat(44))
console.log(`  ${'合计'.padEnd(10)}${String(totalPass).padStart(6)}${String(totalFail).padStart(8)}`)

// ---------- 失败详情 ----------
if (bad.length) {
  console.log('')
  console.log('='.repeat(78))
  console.log('  失败详情（每个脚本的输出尾部）')
  console.log('='.repeat(78))
  for (const r of bad) {
    console.log('')
    console.log(red(`  ── ${r.name} ${r.aborted ? '异常终止' : `${r.fail} 项失败`} ──`))
    console.log(
      tailOf(r.out)
        .split('\n')
        .map((l) => '  ' + l)
        .join('\n'),
    )
  }
}

console.log('')
if (bad.length) {
  console.log(red(`  ❌ 未全部通过：${bad.map((r) => r.name).join(', ')}（共 ${bad.length} 个里程碑有问题）`))
  console.log('  ' + dim('提示：单个排查用 npm run accept:all -- --only ' + bad[0].name))
  process.exit(1)
}
console.log(green(`  ✅ 全部通过：${results.length} 个里程碑，合计 ${totalPass} 项断言`))
process.exit(0)
