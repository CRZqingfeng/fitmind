/**
 * M8-3b 验收：cpolar 隧道把本机网关暴露到公网后，
 *   ① 公网可达吗  ② 延迟多少  ③ ★★ SSE 能不能穿过隧道（cpolar 也是反代，同样有缓冲风险）
 *
 * ★ 为什么必须验 SSE：这一路现在有两级反代
 *     浏览器 → EdgeOne 边缘函数 → cpolar 隧道 → 网关
 *   两级里任何一级做缓冲，"逐字输出"就退化成"等几秒一次性吐出来"。
 *   判据只有一个：**chunk 到达时刻是否分散**（只看最终文本分辨不出）。
 *
 * 用法：node scripts/accept-m8-3b.mjs --base https://<隧道子域>.r11.vip.cpolar.cn
 *      ★ `--base` **必填** —— 隧道地址随 cpolar 账号/区域变化，且**不适合写进仓库**，
 *        所以这里不设默认值（出现在源码里的默认值 = 泄露 + 一旦失效就变成误导）。
 *        当前地址在 cpolar 日志里：`logs/cpolar-gateway.log.<YYYYMMDD>`
 */

import { appendFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')

const argv = process.argv.slice(2)
if (!argv.includes('--base')) {
  console.error('❌ 必须用 --base 指定隧道地址（本脚本刻意不设默认值，避免泄露/误导）')
  console.error('   node scripts/accept-m8-3b.mjs --base https://<隧道子域>.r11.vip.cpolar.cn')
  console.error('   隧道地址见：logs/cpolar-gateway.log.<YYYYMMDD>')
  process.exit(2)
}
const BASE = argv[argv.indexOf('--base') + 1].replace(/\/+$/, '')

// ============================================================
// ★★ 走公网必须先把"目标域名"加进 NO_PROXY —— 否则会静默走到本机代理上。
//
// 本机有系统代理（`HTTP_PROXY/HTTPS_PROXY=http://127.0.0.1:14703`），Node 会读它。
// 而 `NO_PROXY=""`（**空字符串**）等于"没有例外名单" → **所有请求都走代理** →
// 代理不认识这个域名，于是返回它自己的 HTML 404 页。
//
// 这个坑的症状极具误导性：**HTTP 404 + 一个 HTML 页**，看起来像"隧道没通"，
// 而实际隧道好得很（curl 直连就是 401 网关响应）。
// ★ 这是本项目已知的"沙箱代理"坑（手册 §3.34：curl 要 `--noproxy "*"`）在 Node 里的形态。
// ============================================================
function ensureDirectHost(host) {
  const cur = (process.env.NO_PROXY || process.env.no_proxy || '').split(',').map((s) => s.trim()).filter(Boolean)
  if (!cur.includes(host)) cur.push(host)
  const joined = cur.join(',')
  process.env.NO_PROXY = joined
  process.env.no_proxy = joined
  return { joined, had: !!(process.env.HTTP_PROXY || process.env.HTTPS_PROXY || process.env.http_proxy || process.env.https_proxy) }
}
const { joined: noProxyList, had: hadProxy } = ensureDirectHost(new URL(BASE).host)
if (hadProxy) {
  console.log(`  ℹ️ 检测到系统代理 → 已把 ${new URL(BASE).host} 加入 NO_PROXY（直连）`)
  console.log(`     NO_PROXY=${noProxyList}`)
}

const USER = { type: 1, cellPhone: '13800010001', password: '123456', rememberMe: true }

let passed = 0
let failed = 0
const failures = []
const evidence = []

function check(name, ok, detail = '') {
  if (ok) {
    passed++
    console.log(`  ✅ ${name}${detail ? `  —— ${detail}` : ''}`)
  } else {
    failed++
    failures.push(`${name}${detail ? ` (${detail})` : ''}`)
    console.log(`  ❌ ${name}${detail ? `  —— ${detail}` : ''}`)
  }
}
function section(t) {
  console.log(`\n【${t}】`)
}
function record(line) {
  evidence.push(line)
}

async function timed(p, init = {}) {
  const t0 = Date.now()
  try {
    const r = await fetch(BASE + p, init)
    const text = await r.text()
    return { status: r.status, ms: Date.now() - t0, text }
  } catch (e) {
    return { status: -1, ms: Date.now() - t0, text: `ERROR ${e.message}` }
  }
}

console.log('============================================================')
console.log('  M8-3b 验收：cpolar 隧道（公网可达 + 延迟 + SSE 穿透）')
console.log(`  目标：${BASE}`)
console.log('============================================================')

// ================================================================
section('1. 公网可达性（走完整公网路径：DNS → cpolar 边缘 → 隧道 → 本机网关）')

const r1 = await timed('/cs/courses/page?pageNo=1&pageSize=2&status=2')
check('未带 token 的受保护接口 → 401（请求确实到了网关）',
  r1.status === 401, `HTTP ${r1.status} ${r1.ms}ms`)
check('响应体是网关的业务结构', r1.text.includes('4011') || r1.text.includes('code'),
  r1.text.slice(0, 100))
record(`GET /cs/courses/page  → HTTP ${r1.status}  ${r1.ms}ms`)

// 多打几次取延迟分布（单次样本说明不了问题）
const lat = []
for (let i = 0; i < 5; i++) {
  const r = await timed('/cs/courses/page?pageNo=1&pageSize=2&status=2')
  lat.push(r.ms)
}
lat.sort((a, b) => a - b)
const avg = Math.round(lat.reduce((a, b) => a + b, 0) / lat.length)
console.log(`  延迟采样(5 次)：min=${lat[0]}ms  中位=${lat[2]}ms  max=${lat[4]}ms  均值=${avg}ms`)
record(`延迟 min=${lat[0]} 中位=${lat[2]} max=${lat[4]} 均值=${avg}ms`)
check('延迟可用（中位数 < 1500ms）', lat[2] < 1500, `中位 ${lat[2]}ms`)

// ================================================================
section('2. 登录链路（POST + Cookie 穿过隧道）')

const lr = await fetch(`${BASE}/as/accounts/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(USER),
})
const lj = await lr.json().catch(() => null)
const d = lj?.data
const token = typeof d === 'string' ? d : (d?.accessToken ?? d?.token)
check('登录 → code 200', lr.status === 200 && lj?.code === 200, `HTTP ${lr.status} code=${lj?.code}`)
check('拿到 access token', typeof token === 'string' && token.split('.').length === 3)
const sc = lr.headers.get('set-cookie') ?? ''
check('★ Set-Cookie 穿过隧道回传', sc.length > 0, sc ? sc.slice(0, 50) + '...' : '(无)')

// ================================================================
section('3. ★★ SSE 穿透隧道（这一路有两级反代，任何一级缓冲都会破坏流式）')

let spread = 0
let chunks = 0
if (token) {
  const t0 = Date.now()
  const sr = await fetch(`${BASE}/ais/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token },
    body: JSON.stringify({ content: '用一句话说明为什么要渐进超负荷', chatType: 'COACH' }),
  })
  const ct = sr.headers.get('content-type') ?? ''
  check('content-type 是 event-stream', ct.includes('text/event-stream'), ct || '(空)')

  if (sr.body) {
    const reader = sr.body.getReader()
    const decoder = new TextDecoder('utf-8')
    const arrivals = []
    let text = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      text += decoder.decode(value, { stream: true })
      arrivals.push(Date.now() - t0)
    }
    chunks = arrivals.length
    spread = arrivals.length > 1 ? arrivals[arrivals.length - 1] - arrivals[0] : 0
    const dataLines = (text.match(/^data:/gm) ?? []).length
    check('SSE 分多个 chunk 到达', chunks > 1, `chunks=${chunks}`)
    check('★★ chunk 到达时刻分散（真流式，未被隧道缓冲）', spread > 300, `spread=${spread}ms`)
    check('SSE 内容非空', text.length > 50 && dataLines > 3, `textLen=${text.length} dataLines=${dataLines}`)
    record(`SSE：chunks=${chunks} spread=${spread}ms textLen=${text.length}`)
  }
}

// ================================================================
section('4. 收尾')
console.log(`  ${passed} 通过 / ${failed} 失败`)
if (failures.length) {
  console.log('\n  失败项：')
  for (const f of failures) console.log(`    - ${f}`)
}

// 把证据落盘（隧道地址会随重启变化，留档便于回溯）
try {
  mkdirSync(join(ROOT, 'logs'), { recursive: true })
  appendFileSync(
    join(ROOT, 'logs', 'm8-3b-tunnel-evidence.log'),
    `\n===== ${new Date().toISOString()} =====\n目标 ${BASE}\n` +
      evidence.map((e) => `  ${e}`).join('\n') +
      `\n  结果：${passed} 通过 / ${failed} 失败\n`,
    'utf8',
  )
  console.log('\n  ✅ 证据已追加到 logs/m8-3b-tunnel-evidence.log')
} catch {
  /* 落盘失败不影响结论 */
}
console.log('============================================================')
process.exit(failed === 0 ? 0 : 1)
