/**
 * M8-3a/c 验收：EdgeOne Makers 部署包（静态站 + /api 反代）
 *
 * ★ 为什么同一个脚本既验本地又验线上：
 *   本地 `edgeone makers dev` 与云端部署跑的是**同一份**部署包、同一个运行时，
 *   所以"本地过 = 线上大概率过"，而**线上独有的差异**（DNS/证书/CDN 层）才需要单独跑一次。
 *   用 `--base` 切换目标即可，断言完全一致 —— 避免"本地一套、线上一套"两套脚本漂移。
 *
 * 用法：
 *   node scripts/accept-m8-3a.mjs                          # 默认本地 dev（8899）
 *   node scripts/accept-m8-3a.mjs --base https://xxx.edgeone.app   # 线上
 */

import { readdirSync, readFileSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
const STAGE = join(ROOT, 'deploy', '_build', 'portal')

const argv = process.argv.slice(2)
const baseArg = argv[argv.indexOf('--base') + 1]
const BASE = (argv.includes('--base') ? baseArg : 'http://127.0.0.1:8899').replace(/\/+$/, '')
/** 线上预设域名是**私有**的：需要 `?eo_token=..&eo_time=..` 换一次 HttpOnly Cookie 才能访问 */
const EO_TOKEN = argv.includes('--token') ? argv[argv.indexOf('--token') + 1] : ''

const USER = { type: 1, cellPhone: '13800010001', password: '123456', rememberMe: true }

// ============================================================
// ★★ 两件必须先处理掉的环境问题（都踩过）：
//
// 1) **系统代理**：本机有 `HTTP_PROXY/HTTPS_PROXY=http://127.0.0.1:14703`，Node 会读它。
//    `NO_PROXY=""`（空串）等于"没有例外名单" → 请求全走代理 → 代理返回它自己的 HTML 404，
//    看起来像"站点/隧道没通"，其实完全无关。→ 把目标域名加进 NO_PROXY。
//
// 2) **私有预设域名要换 Cookie**：直接访问得 401；带 `?eo_token` 访问得 302 + Set-Cookie。
//    Node 的 fetch **不管理 Cookie**，所以要自己做一个极简 jar 并把 Cookie 头带上。
// ============================================================
const TARGET_HOST = new URL(BASE).host
{
  const cur = (process.env.NO_PROXY || process.env.no_proxy || '').split(',').map((s) => s.trim()).filter(Boolean)
  if (!cur.includes(TARGET_HOST)) cur.push(TARGET_HOST)
  process.env.NO_PROXY = cur.join(',')
  process.env.no_proxy = process.env.NO_PROXY
  if (process.env.HTTP_PROXY || process.env.HTTPS_PROXY) {
    console.log(`  ℹ️ 检测到系统代理 → 已把 ${TARGET_HOST} 加入 NO_PROXY`)
  }
}

/** 极简 cookie jar：够用即可（本项目只有 eo_token / eo_time 两个） */
const jar = new Map()
function cookieHeader() {
  return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
}
function absorbSetCookie(res) {
  const raw = res.headers.getSetCookie?.() ?? []
  for (const c of raw) {
    const [pair] = c.split(';')
    const i = pair.indexOf('=')
    if (i > 0) jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim())
  }
}

// 包一层 fetch：自动带 Cookie、自动吸收 Set-Cookie
const _fetch = globalThis.fetch
globalThis.fetch = async (input, init = {}) => {
  const headers = new Headers(init.headers || {})
  const ck = cookieHeader()
  if (ck) headers.set('cookie', ck)
  const res = await _fetch(input, { ...init, headers })
  absorbSetCookie(res)
  return res
}

let passed = 0
let failed = 0
/** 跳过项：环境不满足（本地 dev 无法验的），既不算通过也不算失败 —— 但要显式报出来 */
const skipped = []
const failures = []

function check(name, ok, detail = '') {
  if (ok) {
    passed++
    console.log(`  ✅ ${name}${detail ? `  —— ${detail}` : ''}`)
  } else {
    failed++
    failures.push(name)
    console.log(`  ❌ ${name}${detail ? `  —— ${detail}` : ''}`)
  }
}

function section(t) {
  console.log(`\n【${t}】`)
}

// ================================================================
console.log('============================================================')
console.log(`  M8-3a 验收：Makers 部署包`)
console.log(`  目标：${BASE}`)
if (EO_TOKEN) console.log(`  模式：线上（私有预设域名，需令牌换 Cookie）`)
console.log('============================================================')

// ================================================================
// 换票：`?eo_token=..` → 302 + Set-Cookie；之后凭 Cookie 访问
if (EO_TOKEN) {
  section('0. 预设域名换票（私有域名必须先换 Cookie）')
  const r = await fetch(`${BASE}/?${EO_TOKEN}`, { redirect: 'manual' })
  check('带 eo_token 访问 → 302（换票成功）', r.status === 302, `HTTP ${r.status}`)
  check('拿到 eo_token / eo_time Cookie', jar.size >= 1, `jar=[${[...jar.keys()].join(', ')}]`)
  const noTok = await fetch(`${BASE}/`)  // 现在带 Cookie，应能访问
  check('换票后访问首页 → 不再是 401', noTok.status !== 401, `HTTP ${noTok.status}`)
}

// ================================================================
section('1. 部署包自身（离线自检，不依赖服务）')

check('部署包目录存在', existsSync(STAGE), STAGE.replace(/\\/g, '/'))
if (existsSync(STAGE)) {
  const html = readFileSync(join(STAGE, 'index.html'), 'utf8')
  const refs = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((m) => m[1])
  const missing = refs.filter((r) => !existsSync(join(STAGE, r)))
  check('index.html 引用的 assets 全部在包里', refs.length > 0 && missing.length === 0, `${refs.length} 个引用，缺 ${missing.length}`)
  check('反代函数在包里', existsSync(join(STAGE, 'cloud-functions', 'api', '[[default]].js')))
  check('没有把 functions 目录打成空壳', readdirSync(join(STAGE, 'cloud-functions', 'api')).length > 0)
}

// ================================================================
section('2. 静态站（真·生产产物，不是 dev server）')

const rootRes = await fetch(`${BASE}/`, { redirect: 'manual' })
const rootHtml = rootRes.status === 200 ? await rootRes.text() : ''
check('GET / → 200', rootRes.status === 200, `HTTP ${rootRes.status}`)
check('返回的是 HTML 且非空', rootHtml.includes('<div id="app"') || rootHtml.includes('<!DOCTYPE html>'), `${rootHtml.length} 字节`)
// ★ 生产产物必须引用**带 hash 的 chunk** —— 若引用的是 /src/main.ts，说明部署包里放的是源码
const hashedRef = /\/assets\/[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8}\.js/.test(rootHtml)
check('★ 引用的是构建产物（带 hash 的 chunk），不是源码', hashedRef)

const assetPath = [...rootHtml.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)][0]?.[1]
if (assetPath) {
  const ar = await fetch(`${BASE}${assetPath}`)
  const ct = ar.headers.get('content-type') ?? ''
  check(`静态资源可取（${assetPath}）→ 200`, ar.status === 200, `HTTP ${ar.status} ct=${ct}`)
  // ★ 目录遍历/函数误吞的判据：assets 必须走静态，不能落到反代函数
  check('静态资源没被 /api 函数吞掉', !ar.headers.get('x-accel-buffering'))
}

// ================================================================
section('3. ★ SPA 深链接回退（history 模式的命门）')

// 路由是前端管的，服务端并不知道 /orders 是什么 —— 必须回退到 index.html。
// 不回退的表现是：用户在 /orders 刷新页面 → 404（而 SPA 内跳转却正常，所以很难发现）。
//
// ★ 判据要认**我们自己的壳**，而不是"任意 HTML"：
//   Vite 产物里 doctype 是**小写** `<!doctype html>`，写 `<!DOCTYPE html>` 会假红（踩过）。
const isOurShell = (b) => /<!doctype html>/i.test(b) && /id="app"/.test(b)

// ★★ 为什么这一段在本地跑是「跳过」而不是「通过/失败」：
//   `edgeone makers dev` 的静态层是**纯文件服务器**，实测**完全不实现 SPA 回退** ——
//   三种办法都试过且都不生效：① `rewrites` ② 放一个 `404.html` ③ 让框架探测认出来。
//   回退是**发布期/平台层**的行为，本地 dev 不是它的预览。
//   → 本地硬断言只会得到一个与实现无关的假红；所以本地跳过、线上真断言。
const isLocal = /^(https?:\/\/)?(127\.0\.0\.1|localhost)/.test(BASE)
if (isLocal) {
  const r = await fetch(`${BASE}/orders`, { redirect: 'manual' })
  const note = `SPA 深链接回退（/orders、/me、/courses）—— 本地实测 HTTP ${r.status}`
  skipped.push(note)
  console.log(`  ⚠️ 跳过：${note}`)
  console.log('     （本地 dev 的静态层是纯文件服务器，不实现 SPA 回退；回退属发布期行为 → 线上验）')
} else {
  for (const p of ['/orders', '/me', '/courses']) {
    const r = await fetch(`${BASE}${p}`, { redirect: 'manual' })
    const body = r.status === 200 ? await r.text() : ''
    check(`深链接 ${p} → 回退到我们的 SPA 壳`, r.status === 200 && isOurShell(body), `HTTP ${r.status}`)
  }
  // ★ 真正该守的是"**`/api` 没被 rewrite 吞掉**" —— 那才会让接口全废。
  //   判据：一个不存在的 /api 路径应仍由**函数**处理（返回 JSON），而不是被改写成 HTML。
  const apiMiss = await fetch(`${BASE}/api/__definitely_missing__`, { redirect: 'manual' })
  const apiMissBody = await apiMiss.text()
  check('★★ 反向对照：不存在的 /api/* 仍由函数处理（返回 JSON，没被 rewrite 吞成 HTML）',
    /json/i.test(apiMiss.headers.get('content-type') ?? '') && !/<!doctype/i.test(apiMissBody),
    `HTTP ${apiMiss.status} ct=${apiMiss.headers.get('content-type')}`)

  // ★ 附带记录（**不断言失败**）：通配 rewrite 会把"不存在的静态资源"也回退到 index.html，
  //   即返回 200 + HTML 而不是 404。这是通配 rewrite 的**标准语义**
  //   （与 nginx `try_files $uri $uri/ /index.html` 完全一致），不是缺陷。
  //   唯一代价：静态资源 404 被掩盖，排查时容易困惑 —— 记下来即可。
  const assetMiss = await fetch(`${BASE}/assets/__definitely_missing__.js`, { redirect: 'manual' })
  console.log(
    `  ℹ️ 记录：不存在的 assets → HTTP ${assetMiss.status}（通配 rewrite 的标准语义；` +
      `代价是静态 404 被掩盖，排查时注意）`,
  )
}

// ================================================================
section('4. /api 反代（同源 → 网关）')

const h = await fetch(`${BASE}/api/__health__`)
const hj = await h.json().catch(() => null)
check('/api/__health__ → ok:true', h.status === 200 && hj?.ok === true, JSON.stringify(hj))
// ★ 健康检查不该把内网上游地址泄到公网上
const hText = JSON.stringify(hj ?? {})
check('健康检查不泄露上游地址', !/https?:\/\//.test(hText), hText.slice(0, 80))

// 未带 token 的受保护接口：应当 401（说明请求真的到了网关，而不是被函数吞了）
const g = await fetch(`${BASE}/api/cs/courses/page?pageNo=1&pageSize=2&status=2`)
const gj = await g.json().catch(() => null)
check('受保护接口不带 token → 401（请求确实到了网关）', g.status === 401 && gj?.code === 4011, `HTTP ${g.status} code=${gj?.code}`)

// ================================================================
section('5. 登录（POST + 请求体 + HttpOnly Cookie）')

const lr = await fetch(`${BASE}/api/as/accounts/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(USER),
})
const lj = await lr.json().catch(() => null)
// ★ 登录接口的 data 就是 token 字符串本身（不是对象）
const d = lj?.data
const token = typeof d === 'string' ? d : (d?.accessToken ?? d?.token)
check('登录 → code 200', lr.status === 200 && lj?.code === 200, `HTTP ${lr.status} code=${lj?.code} msg=${lj?.msg ?? ''}`)
check('拿到 access token（JWT 形状）', typeof token === 'string' && token.split('.').length === 3, token ? token.slice(0, 20) + '...' : 'null')

const setCookie = lr.headers.get('set-cookie') ?? ''
// ★ refresh token 走 HttpOnly Cookie —— 反代**必须原样回传 Set-Cookie**，否则续期链路整条断掉
check('★ Set-Cookie 被反代回传（refresh token 靠它）', setCookie.length > 0, setCookie ? setCookie.slice(0, 60) + '...' : '(无)')
check('cookie 名是 portal 的 refresh（不是 admin-refresh）', /(^|,\s*)refresh=/.test(setCookie) && !/admin-refresh/.test(setCookie), setCookie.slice(0, 60))

if (token) {
  const me = await fetch(`${BASE}/api/us/users/me`, { headers: { Authorization: token } })
  const mj = await me.json().catch(() => null)
  check('带 token 访问 /us/users/me → 200', me.status === 200 && mj?.code === 200, `HTTP ${me.status} code=${mj?.code}`)
  check('返回的是学员身份（不是管理员）', mj?.data?.type === 2 || mj?.data?.type === '2', `type=${mj?.data?.type}`)
}

// ================================================================
section('6. ★★ SSE 流式（反代最容易悄悄破坏的一环）')

if (token) {
  const t0 = Date.now()
  const sr = await fetch(`${BASE}/api/ais/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token },
    body: JSON.stringify({ content: '用一句话说明为什么要渐进超负荷', chatType: 'COACH' }),
  })
  const ct = sr.headers.get('content-type') ?? ''
  check('SSE 接口 content-type 是 event-stream', ct.includes('text/event-stream'), ct || '(空)')

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
    // ★ 判据：chunk 到达时刻是否**分散**。
    //   只看最终文本分辨不出"流式"与"缓冲后一次性返回"—— 两者拿到的文本完全一样。
    const spread = arrivals.length > 1 ? arrivals[arrivals.length - 1] - arrivals[0] : 0
    const dataLines = (text.match(/^data:/gm) ?? []).length
    check('SSE 分多个 chunk 到达（>1）', arrivals.length > 1, `chunks=${arrivals.length}`)
    check('★★ chunk 到达时刻分散（真流式，非缓冲）', spread > 300, `spread=${spread}ms`)
    check('SSE 内容非空且有多条 data 行', text.length > 50 && dataLines > 3, `textLen=${text.length} dataLines=${dataLines}`)
  }
}

// ================================================================
section('7. 收尾')
console.log(`  ${passed} 通过 / ${failed} 失败${skipped.length ? ` / ${skipped.length} 跳过` : ''}`)
if (skipped.length) {
  console.log('\n  跳过项（环境不满足，线上需验）：')
  for (const s of skipped) console.log(`    - ${s}`)
}
if (failures.length) {
  console.log('\n  失败项：')
  for (const f of failures) console.log(`    - ${f}`)
}
console.log('============================================================')
process.exit(failed === 0 ? 0 : 1)
