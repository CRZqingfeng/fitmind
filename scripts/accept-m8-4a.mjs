/**
 * M8-4a 验收：仓库门面（README / 脱敏 / 忽略规则 / 文档一致性）
 *
 * ★ 为什么"文档阶段"也要验收脚本：
 *   这一阶段的产物不是代码，但**同样会回归** —— 比如以后有人又在脚本里写死隧道地址、
 *   或 README 引用的图片被挪走、或 .gitignore 被改坏。用脚本守住，比"记得检查"可靠。
 *
 * ★ 全部检查不依赖任何服务（纯离线），随时可跑。
 *
 * 用法：node scripts/accept-m8-4a.mjs
 */

import { execSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, extname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')

let pass = 0
let fail = 0
const fails = []
function check(name, ok, detail) {
  if (ok) { pass++; console.log('  [OK] ' + name) }
  else { fail++; fails.push(name); console.log('  [FAIL] ' + name + (detail ? '  -- ' + detail : '')) }
}
function section(t) { console.log('\n【' + t + '】') }

const read = (p) => readFileSync(join(ROOT, p), 'utf8')
const sh = (cmd) => {
  try { return execSync(cmd, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }) }
  catch { return '' }
}
const rel = (p) => p.replace(ROOT + '\\', '').replace(ROOT + '/', '')

console.log('============================================================')
console.log('  M8-4a 验收：仓库门面')
console.log('============================================================')

// ================================================================
section('1. 根 README（门面）')

const readmePath = 'README.md'
const hasReadme = existsSync(join(ROOT, readmePath))
check('README.md 存在', hasReadme)
const rm = hasReadme ? read(readmePath) : ''

// 门面骨架：缺一段就等于门面塌了
const REQUIRED = ['## 这是什么', '## 技术栈', '## 三个最硬的技术点', '## 架构',
  '## 工程质量', '## 快速开始', '## 文档导航', '## 已知局限']
for (const s of REQUIRED) check('含章节「' + s + '」', rm.includes(s))

// ★ 硬判据：正文不出现"见 docs/xxx 第 N 节"（只在末尾导航区出现）
const navIdx = rm.indexOf('## 文档导航')
const bodyBeforeNav = navIdx > 0 ? rm.slice(0, navIdx) : rm
const crossRef = bodyBeforeNav.match(/见\s*`?docs\/[^\s`]+?\s*(第|§)\s*[0-9一二三四五六七八九十]/g) || []
check('★ 正文无「见 docs/xxx 第 N 节」式跳转', crossRef.length === 0, crossRef.slice(0, 2).join(' | '))

// 架构图：GitHub 原生渲染 mermaid，是门面的核心
const mermaidBlocks = rm.match(/```mermaid\n[\s\S]*?```/g) || []
check('含 mermaid 架构图', mermaidBlocks.length >= 1, '找到 ' + mermaidBlocks.length + ' 个')
if (mermaidBlocks[0]) {
  const m = mermaidBlocks[0]
  check('  架构图括号平衡',
    m.split('(').length === m.split(')').length && m.split('[').length === m.split(']').length)
  check('  架构图含网关与 AI 服务', m.includes('fm-gateway') && m.includes('fm-aigc'))
}

// 引用的图片必须真实存在，否则 GitHub 上是裂图
const imgs = [...rm.matchAll(/src="([^"]+\.png)"/g)].map((x) => x[1])
  .concat([...rm.matchAll(/!\[[^\]]*\]\(([^)]+\.png)\)/g)].map((x) => x[1]))
check('引用的截图存在（' + imgs.length + ' 张）',
  imgs.length > 0 && imgs.every((p) => existsSync(join(ROOT, p))),
  imgs.filter((p) => !existsSync(join(ROOT, p))).join(', '))

// 工程质量证据 —— 本项目最不像"练手项目"的地方，必须在门面上出现
check('★ 写出「1124 项断言」这一量级证据', rm.includes('1124'))
check('★ 写出「反向对照」', rm.includes('反向对照'))

const fences = (rm.match(/```/g) || []).length
check('代码围栏成对', fences % 2 === 0, '找到 ' + fences + ' 个')

// ================================================================
section('2. 脱敏（公开前置）')

const SKIP_DIRS = new Set(['.git', 'node_modules', 'target', '_build', 'logs', '.idea', 'dist'])
const TEXT_EXT = new Set(['.md', '.mjs', '.js', '.json', '.yml', '.yaml', '.java',
  '.ts', '.vue', '.sql', '.sh', '.txt', '.properties'])
const SECRETS = [
  ['真实隧道子域', /\b(33604a3e|66ef6109)\b/g],
  ['真实 eo_token', /eo_token=[0-9a-f]{16,}/g],
  ['API key 字面量', /sk-[a-zA-Z0-9]{16,}/g],
]
const hits = []
;(function walk(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (!SKIP_DIRS.has(e.name)) walk(join(dir, e.name)); continue }
    if (!TEXT_EXT.has(extname(e.name).toLowerCase())) continue
    const p = join(dir, e.name)
    let txt = ''
    try { txt = readFileSync(p, 'utf8') } catch { continue }
    for (const pair of SECRETS) {
      const label = pair[0]
      const rx = pair[1]
      // deploy/upstream.txt 是本地文件（已 gitignore），不算泄露
      if (rx.test(txt) && !p.includes('upstream.txt')) hits.push(label + ' @ ' + rel(p))
    }
  }
})(ROOT)
check('★ 仓库内无真实隧道子域 / eo_token / API key', hits.length === 0, hits.slice(0, 3).join(' | '))

const tracked = sh('git -c core.quotepath=false ls-files').split('\n').filter(Boolean)
check('已入库文件不含 .env（.env.example 除外）',
  !tracked.some((f) => /(^|\/)\.env$/.test(f) || /\.env\.local$/.test(f)))
check('已入库文件不含隧道地址文件', !tracked.some((f) => f.endsWith('upstream.txt')))
check('已入库文件不含部署产物', !tracked.some((f) => f.includes('deploy/_build')))
check('已入库文件不含 node_modules / logs / target',
  !tracked.some((f) => f.includes('node_modules') || f.startsWith('logs/') || f.includes('/target/')))

// ================================================================
section('3. .gitignore 覆盖')

const gi = read('.gitignore')
const RULES = ['/.env', '.edgeone/', 'docker/.env', 'deploy/upstream.txt',
  'deploy/_build/', 'logs/', '.tmp/', 'node_modules/']
for (const r of RULES) check('忽略规则 ' + r, gi.includes(r))

// ================================================================
section('4. 文档一致性（实读核对的结果不能又漂回去）')

const manual = read('docs/本地环境排障手册.md')
// §一 业务服务表必须列全 15 个
const SVC = ['fm-auth', 'fm-user', 'fm-media', 'fm-course', 'fm-search', 'fm-learning', 'fm-remark',
  'fm-trade', 'fm-pay', 'fm-promotion', 'fm-message', 'fm-exam', 'fm-data', 'fm-aigc', 'fm-gateway']
const missing = SVC.filter((s) => !new RegExp('\\|\\s*\\*{0,2}' + s + '\\*{0,2}\\s*\\|').test(manual))
check('手册 §一 端口表列全 15 个服务', missing.length === 0, '缺 ' + missing.join(', '))
check('手册 §一 标注了数据来源', manual.includes('实读 15 份'))
const GW = ['/as', '/us', '/ms', '/cs', '/ss', '/ls', '/ts', '/ps', '/prs',
  '/rs', '/es', '/ds', '/sms', '/ais']
check('手册 §一 列出 14 条网关前缀', GW.every((p) => manual.includes('`' + p + '`')))

const highlights = read('docs/技术亮点与踩坑汇编.md')
check('汇编含 M7 章节', highlights.includes('M7 前端'))
check('汇编含 M8 章节', highlights.includes('M8 联调'))
check('汇编已修过期数字（35 条）',
  highlights.includes('检查清单 35 条') && !highlights.includes('检查清单 19 条'))

// ================================================================
section('5. 工作区状态')

const dirty = sh('git status --porcelain').trim()
check('★ git status 干净（无未提交改动）', dirty === '', dirty.split('\n').slice(0, 3).join(' | '))
const recent = sh('git log --oneline -8').split('\n').filter((l) => /M8|M7|基础设施/.test(l))
check('有提交历史（M7/M8/基础设施 ≥ 4 条）', recent.length >= 4, '找到 ' + recent.length + ' 条')

// ================================================================
console.log('\n============================================================')
console.log('  结果：' + pass + ' 通过 / ' + fail + ' 失败')
if (fail) {
  console.log('  失败项：')
  for (const f of fails) console.log('    - ' + f)
}
console.log('============================================================')
process.exit(fail ? 1 : 0)
