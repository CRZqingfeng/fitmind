/**
 * 组装「可部署到 EdgeOne Makers 的目录」。
 *
 * 为什么需要它（而不是直接 `deploy ./dist`）：
 *   EdgeOne Makers 的部署包需要**三样东西同时存在**：
 *     ① 静态产物            （index.html + assets/）
 *     ② `cloud-functions/`  （我们的 /api 反代函数）
 *     ③ `package.json` + `edgeone.json`
 *   而 `vite build` 只会产出 ①。所以中间需要一个"拼装"步骤 —— 本脚本就是它。
 *   （CLI 文档原话：手工构建时要把 Makers Functions 目录与 package.json 放进输出目录，
 *     再 `edgeone makers deploy ./dist`。）
 *
 * 产物：`deploy/_build/portal/`  ← 这个目录**不进 git**（每次现拼）
 *
 * 用法：
 *   node scripts/makers-stage.mjs              # 构建 portal + 拼装
 *   node scripts/makers-stage.mjs --no-build   # 跳过构建，只重新拼装（复用已有 dist）
 */

import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')

const APP = 'fitmind-portal'
const APP_DIR = join(ROOT, 'frontend', APP)
const DIST = join(APP_DIR, 'dist')
const TEMPLATE = join(ROOT, 'deploy', 'makers-portal')
const STAGE = join(ROOT, 'deploy', '_build', 'portal')

const noBuild = process.argv.includes('--no-build')

const problems = []
function fail(msg) {
  problems.push(msg)
  console.log(`  ❌ ${msg}`)
}

console.log('============================================================')
console.log('  组装 EdgeOne Makers 部署目录')
console.log('============================================================')

// ---------- 阶段一：构建（可选） ----------
if (noBuild) {
  console.log('① 跳过构建（--no-build）')
} else {
  console.log('① 构建 portal（vue-tsc -b && vite build）')
  const r = spawnSync('npm', ['run', 'build:portal'], {
    cwd: join(ROOT, 'frontend'),
    shell: true, // Windows 下 npm 是 .cmd，必须走 shell
    encoding: 'utf8',
    stdio: 'inherit',
  })
  if (r.status !== 0) {
    console.log(`\n  ❌ 构建失败（退出码 ${r.status}）—— 不继续拼装`)
    process.exit(1)
  }
}

// ---------- 阶段二：源检查（缺一样就别往下走） ----------
console.log('\n② 检查三样输入是否齐备')
const checks = [
  [join(DIST, 'index.html'), '静态产物 index.html'],
  [join(TEMPLATE, 'cloud-functions', 'api', '[[default]].js'), '反代函数'],
  [join(TEMPLATE, 'package.json'), 'package.json'],
  [join(TEMPLATE, 'edgeone.json'), 'edgeone.json'],
]
for (const [p, label] of checks) {
  if (existsSync(p)) console.log(`  ✅ ${label}`)
  else fail(`${label} 缺失：${p.replace(ROOT + '\\', '').replace(ROOT + '/', '')}`)
}
if (problems.length) {
  console.log('\n  ★ 输入不齐，**未做任何写入**')
  process.exit(1)
}

// ---------- 阶段三：拼装 ----------
console.log('\n③ 拼装到 deploy/_build/portal/')
// ★ 先清空目标目录：否则上一次构建的旧 chunk 会留下来，
//   而 index.html 不再引用它们 —— 结果是一个"越攒越大且带垃圾"的部署包。
//
// ★★ 但清空会撞上 Windows 文件锁：如果 `edgeone makers dev` 正在跑（它就是这个目录的服务进程），
//    rmdir 会报 `EBUSY`。这是本项目的惯犯（"打包前先停服务"那条铁律），所以要给人话提示，
//    而不是甩一段 node 堆栈 —— 堆栈只会让人以为是脚本本身坏了。
try {
  rmSync(STAGE, { recursive: true, force: true })
} catch (e) {
  if (e.code === 'EBUSY' || e.code === 'EPERM') {
    console.log('\n  ❌ 无法清空部署包目录 —— 它被占用了（Windows 文件锁）')
    console.log(`     目录：${STAGE.replace(/\\/g, '/')}`)
    console.log('     最常见原因：**`edgeone makers dev` 还在跑**（它正是这个目录的服务进程）')
    console.log('     → 先停掉它（按端口找进程：netstat -ano | findstr :8899），再重跑本脚本')
    process.exit(1)
  }
  throw e
}
mkdirSync(STAGE, { recursive: true })

cpSync(DIST, STAGE, { recursive: true })                                   // ① 静态产物
cpSync(join(TEMPLATE, 'cloud-functions'), join(STAGE, 'cloud-functions'), { recursive: true }) // ② 函数
cpSync(join(TEMPLATE, 'package.json'), join(STAGE, 'package.json'))         // ③ 清单
cpSync(join(TEMPLATE, 'edgeone.json'), join(STAGE, 'edgeone.json'))        // ③ 配置

// ---------- 阶段三之二：注入上游地址 ----------
// 函数里 `DEFAULT_UPSTREAM` 是个占位符，这里替换成真实地址。
// 取值优先级：--upstream > 环境变量 FM_API_UPSTREAM > deploy/upstream.txt > 本机默认
const UPSTREAM_FILE = join(ROOT, 'deploy', 'upstream.txt')
function resolveUpstream() {
  const i = process.argv.indexOf('--upstream')
  if (i >= 0 && process.argv[i + 1]) return { url: process.argv[i + 1], from: '--upstream 参数' }
  if (process.env.FM_API_UPSTREAM) return { url: process.env.FM_API_UPSTREAM, from: '环境变量 FM_API_UPSTREAM' }
  if (existsSync(UPSTREAM_FILE)) {
    const v = readFileSync(UPSTREAM_FILE, 'utf8').split('\n').map((s) => s.trim()).find((s) => s && !s.startsWith('#'))
    if (v) return { url: v, from: 'deploy/upstream.txt' }
  }
  return { url: 'http://127.0.0.1:10010', from: '本机默认（⚠️ 部署后会不可达）' }
}
const upstream = resolveUpstream()
const stageFn = join(STAGE, 'cloud-functions', 'api', '[[default]].js')
let fnText = readFileSync(stageFn, 'utf8')
if (!fnText.includes('__API_UPSTREAM__')) {
  problems.push('函数里没有 __API_UPSTREAM__ 占位符（拼装脚本与模板不同步？）')
} else {
  fnText = fnText.replace('__API_UPSTREAM__', upstream.url)
  writeFileSync(stageFn, fnText, 'utf8')
}
console.log(`\n③-² 注入上游地址：${upstream.url}`)
console.log(`     来源：${upstream.from}`)

// ---------- 阶段四：自检 ----------
console.log('\n④ 自检（不信"复制成功"，直接复查目标目录）')
function countFiles(dir) {
  let n = 0
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    n += e.isDirectory() ? countFiles(p) : 1
  }
  return n
}
const idx = join(STAGE, 'index.html')
const fn = join(STAGE, 'cloud-functions', 'api', '[[default]].js')
const html = existsSync(idx) ? readFileSync(idx, 'utf8') : ''

console.log(`  index.html          ${existsSync(idx) ? '✅' : '❌'}`)
console.log(`  反代函数            ${existsSync(fn) ? '✅' : '❌'}`)
console.log(`  package.json        ${existsSync(join(STAGE, 'package.json')) ? '✅' : '❌'}`)
console.log(`  edgeone.json        ${existsSync(join(STAGE, 'edgeone.json')) ? '✅' : '❌'}`)

// ★ 关键自检：index.html 引用的每个 /assets/ 文件都必须真的在包里。
//   漏一个 → 线上白屏，而部署本身会"成功"。
const refs = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((m) => m[1])
const missing = refs.filter((r) => !existsSync(join(STAGE, r)))
console.log(`  index.html 引用 ${refs.length} 个 assets，缺失 ${missing.length} 个 ${missing.length ? '❌' : '✅'}`)
if (missing.length) {
  for (const m of missing.slice(0, 5)) console.log(`      ❌ ${m}`)
  problems.push('index.html 引用了不存在的 assets')
}

// ★ 反代函数必须带 fallback（本地 dev 时 env 为空，实测 env.API_UPSTREAM === null）
const fnSrc = existsSync(fn) ? readFileSync(fn, 'utf8') : ''
console.log(`  函数含 API_UPSTREAM fallback  ${/DEFAULT_UPSTREAM/.test(fnSrc) ? '✅' : '❌'}`)
console.log(`  上游地址已注入（无残留占位符）  ${fnSrc.includes('__API_UPSTREAM__') ? '❌' : '✅'}`)
if (fnSrc.includes('__API_UPSTREAM__')) problems.push('上游地址占位符未被替换')

// ★★★ 这条是拿一次真实事故换来的，别删：
//   部署包的 `package.json` **一旦声明框架依赖**（vue / vite…），平台的框架探测会把项目
//   判定为"框架项目" → 期望"执行构建、产物在 outputDirectory（如 dist/）" →
//   **直接放在根目录的静态产物被完全无视，全站 404**；
//   而函数走独立通道照常工作 ⇒ 症状是"接口通、页面全 404"，极难联想到 package.json。
const pkg = existsSync(join(STAGE, 'package.json'))
  ? JSON.parse(readFileSync(join(STAGE, 'package.json'), 'utf8'))
  : {}
const frameworkDeps = ['vue', 'vite', '@vitejs/plugin-vue', 'react', 'next', 'nuxt', '@angular/core']
  .filter((d) => pkg.dependencies?.[d] || pkg.devDependencies?.[d])
console.log(
  `  package.json 不含框架依赖  ${frameworkDeps.length === 0 ? '✅' : `❌ 发现：${frameworkDeps.join(', ')}`}`,
)
if (frameworkDeps.length) {
  problems.push(
    `package.json 声明了框架依赖（${frameworkDeps.join(', ')}）—— 平台会按"框架项目"处理，静态产物将不被伺服（全站 404）`,
  )
}

const files = countFiles(STAGE)
console.log(`\n  部署包：${STAGE.replace(/\\/g, '/')}`)
console.log(`  文件数：${files}`)

console.log('\n============================================================')
if (problems.length) {
  console.log(`  ❌ 自检未通过（${problems.length} 项）`)
  process.exit(1)
}
console.log('  ✅ 组装完成')
console.log('')
console.log('  本地验证：  cd deploy/_build/portal && edgeone makers dev --port 8899')
console.log('  真实部署：  edgeone makers deploy deploy/_build/portal -n fitmind-portal -e production')
console.log('============================================================')
