#!/usr/bin/env node
/**
 * ============================================================
 * 本地「假网关」—— 转发 /pls/** → fm-plan(8095) 与 /ais/** → fm-aigc(8094)
 * ------------------------------------------------------------
 * 用途：本机不想起 Nacos + fm-gateway + fm-auth + fm-user 时，
 *      给前端 dev server 一个能用的 10010，把计划模块的页面跑起来。
 *
 * 为什么要有这个文件（而不是"验完就删的临时脚本"）：
 *   谁写临时脚本谁省事，但**下一个人回归时验不了** —— 等于"上次验过"四个字没有价值。
 *   所以它进仓库：`node scripts/dev-plan-proxy.mjs` 一条命令就能复现。
 *
 * 它做了网关做的三件事：
 *   1. 去掉第一层路径前缀（`/pls/plans` → `/plans`、`/ais/plan/skeleton` → `/plan/skeleton`），
 *      与网关 `StripPrefix=1` 一致
 *   2. 透传登录用户（`user-info` 头）—— 真网关是从 JWT 里解出来的
 *   3. 补 `request-from: gateway`，让 fm-common 把返回值包成 `R` 结构
 *
 * ⚠️ 仅供本地调试：**user-info 写死成 1**，任何人访问都是"学员1"。
 *    它的价值是"能点页面"，不是"验权限"——权限请用 `scripts/accept-*.mjs`
 *    配合真实网关验（那些脚本已经覆盖匿名 401 / 跨用户 403）。
 *
 * ★ M11-4 起多了 `/ais/**`：AI 定制要同时打 fm-plan（选材落库）和 fm-aigc（骨架），
 *   两条链路缺一条，结果页就出不来。**这两个前缀必须都转发**，
 *   否则 `/ais/plan/skeleton` 会落到下面那个"空但成功"的兜底里，
 *   前端拿到 `data:null` → 表现为"骨架永远是空的"，而日志里一条错都没有。
 *
 * 用法：
 *   node scripts/dev-plan-proxy.mjs           # 监听 10010
 *   PORT=10011 USER_ID=2 node scripts/dev-plan-proxy.mjs
 *   AIGC_PORT=8094 PLAN_PORT=8095 node scripts/dev-plan-proxy.mjs
 * ============================================================
 */
import { createServer } from 'node:http'

const PORT = Number(process.env.PORT || 10010)
const UPSTREAM_HOST = process.env.UPSTREAM_HOST || '127.0.0.1'
const PLAN_PORT = Number(process.env.PLAN_PORT || process.env.UPSTREAM_PORT || 8095)
const AIGC_PORT = Number(process.env.AIGC_PORT || 8094)
const USER_ID = String(process.env.USER_ID || 1)

/** 前端 dev server 的源；CORS 要回显它，不能用 *（带 cookie 的请求不允许 *） */
const ALLOW_ORIGIN = process.env.ALLOW_ORIGIN || 'http://localhost:5173'

/** 前缀 → 上游端口（顺序有意义：先匹配到的先用） */
const ROUTES = [
  { prefix: '/pls', port: PLAN_PORT, name: 'fm-plan' },
  { prefix: '/ais', port: AIGC_PORT, name: 'fm-aigc' },
]

const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'transfer-encoding',
  'upgrade',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
])

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin': origin || ALLOW_ORIGIN,
    'Access-Control-Allow-Credentials': 'true',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,Authorization,request-from,user-info',
    'Access-Control-Max-Age': '600',
  }
}

const server = createServer((req, res) => {
  const origin = req.headers.origin

  // 预检：直接 204，不打到后端（后端也没配 CORS）
  if (req.method === 'OPTIONS') {
    res.writeHead(204, corsHeaders(origin))
    res.end()
    return
  }

  const route = ROUTES.find((r) => req.url.startsWith(r.prefix + '/'))

  // 其它前缀（/us /as /ts …）：给一个"空但成功"的响应，而不是 404。
  // ★ 为什么不是 404：前端 `request.ts` 会把后端 msg 弹成红色 toast，
  //   一句"本代理只转发 …"会盖在页面上，截出来的图没法用。
  //   这里返回 200 + data:null，页面就是"未登录"的正常样子。
  if (!route) {
    if (/^\/(us|as|ts|ps|prs|es|ms|cs|ls|ss|ds|sms|rs)\//.test(req.url)) {
      res.writeHead(200, { 'Content-Type': 'application/json', ...corsHeaders(origin) })
      res.end(JSON.stringify({ code: 200, msg: 'OK', data: null, success: true }))
      return
    }
    res.writeHead(404, { 'Content-Type': 'application/json', ...corsHeaders(origin) })
    res.end(JSON.stringify({
      code: 404,
      msg: `本代理只转发 ${ROUTES.map((r) => r.prefix + '/**').join(' 与 ')}，收到的是 ${req.url}`,
    }))
    return
  }

  // 与网关 StripPrefix=1 等价：去掉第一层前缀
  const path = req.url.slice(route.prefix.length)

  const headers = {}
  for (const [k, v] of Object.entries(req.headers)) {
    if (!HOP_BY_HOP.has(k.toLowerCase())) headers[k] = v
  }
  headers.host = `${UPSTREAM_HOST}:${route.port}`
  headers['user-info'] = USER_ID
  headers['request-from'] = 'gateway'
  // 真实网关会把 token 换成 user-info；这里 token 可能是假的，别传给后端误导排查
  delete headers.authorization

  const upstream = fetch(`http://${UPSTREAM_HOST}:${route.port}${path}`, {
    method: req.method,
    headers,
    body: req.method === 'GET' || req.method === 'HEAD' ? undefined : req,
    duplex: 'half',
  })

  upstream
    .then(async (r) => {
      const buf = Buffer.from(await r.arrayBuffer())
      const out = {}
      r.headers.forEach((v, k) => {
        if (!HOP_BY_HOP.has(k.toLowerCase())) out[k] = v
      })
      res.writeHead(r.status, { ...out, ...corsHeaders(origin) })
      res.end(buf)
    })
    .catch((e) => {
      console.error('转发失败：', req.method, path, e.message)
      res.writeHead(502, { 'Content-Type': 'application/json', ...corsHeaders(origin) })
      res.end(JSON.stringify({ code: 502, msg: `${route.name} 不可达（${e.message}）` }))
    })
})

server.listen(PORT, () => {
  console.log(`假网关已启动：http://127.0.0.1:${PORT}`)
  for (const r of ROUTES) {
    console.log(`  ${r.prefix}/** → http://${UPSTREAM_HOST}:${r.port}/**（去前缀，${r.name}）`)
  }
  console.log(`  固定身份 user-info=${USER_ID} · CORS 回显 ${ALLOW_ORIGIN}`)
  console.log('  ⚠️ 只用于本地看页面；权限验真请走 scripts/accept-*.mjs + 真网关')
})
