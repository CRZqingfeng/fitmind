/**
 * FitMind · EdgeOne Makers 反代函数
 *
 * 路径约定：本文件位于 `cloud-functions/api/[[default]].js`
 *   → 匹配 **`/api/*`（含多级）**，也匹配 `/api` 本身。
 *   （EdgeOne 的路由约定：`[x].js` → `/x 单段`；`[x].js` 全路径 → `[...x]` 多段；
 *     `[[x]].js` → 可选段 `:x?`。见 CLI 源码里的转换规则。）
 *
 * 职责：把 `/api/**` 转发到上游网关，并**保持响应流式**。
 *   上游 = `env.API_UPSTREAM`（部署时在 Makers 控制台配）或 fallback 默认值。
 *
 * ─────────────────────────────────────────────────────────────
 * ★★ 两个只有真跑才会发现的坑（都在本地 dev 里实测踩到过）：
 *
 * 1. **请求体不能直接用流转发**：
 *    `fetch(target, { body: request.body })` 在 Node 18+ 会抛 `TypeError: fetch failed`，
 *    因为**用流作 body 必须显式声明 `duplex: 'half'`**。
 *    加上 duplex 后不再抛错，但**上游收到的是坏请求体**（网关报 500「系统繁忙」）——
 *    症状极具误导性：GET 全通、只有 POST 挂。
 *    → 本函数改为**缓冲请求体**（`await request.arrayBuffer()`）。
 *      对本项目是安全的：JSON 接口体都很小，而**大文件（视频）走 MinIO 预签名直传，不经网关**。
 *
 * 2. **必须回传 `Set-Cookie`**：
 *    登录下发的 refresh token 是 HttpOnly Cookie（`AccountServiceImpl` → `WebUtils.writeCookie`，
 *    只设了 `path=/` + `httpOnly`，**没有 Domain/Secure/SameSite**）。
 *    没有 Domain ⇒ 浏览器把它归给「实际响应它的域名」= 本边缘域名 —— 这正是同源反代能成立的原因。
 *    → 转发时**不能漏掉 `Set-Cookie`**，也不能自己造 Cookie 属性。
 * ─────────────────────────────────────────────────────────────
 */

/** 逐跳（hop-by-hop）头 + 与"本地这一跳"绑定的头，转发时必须剥掉 */
const STRIP_HEADERS = new Set([
  'host',
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'content-length', // 缓冲后自己重算
  'accept-encoding', // 交给上游决定，避免"压缩了但不告诉客户端"
])

/**
 * ★ fallback 存在的理由（两层）：
 *   ① 本地 `edgeone makers dev --skip-env-sync` 时 `env` 是空的（实测 `env.API_UPSTREAM === null`），
 *      没有 fallback 就完全没法本地验；
 *   ② 部署后，**环境变量优先**；若平台上没配（实测 CLI 的 `env set` 在非交互环境会静默失败），
 *      这个 fallback 让线上开箱可用。
 *
 * ★ 这里的值由 `scripts/makers-stage.mjs` 在**拼装时注入**（来源：`deploy/upstream.txt`
 *   或环境变量 `FM_API_UPSTREAM`）。**不要手工改这个文件** —— 改了会被下次拼装覆盖。
 *    换隧道地址 = 改 `deploy/upstream.txt` → 重新拼装 → 重新部署。
 */
const DEFAULT_UPSTREAM = '__API_UPSTREAM__'

export async function onRequest(context) {
  const { request, env } = context
  const url = new URL(request.url)

  // 健康检查：只回一句"活着"，**不暴露上游地址**（避免把内网入口写到公网上）
  if (url.pathname === '/api/__health__') {
    return new Response(JSON.stringify({ ok: true, service: 'fitmind-api-proxy' }), {
      headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
    })
  }

  const upstream = (env.API_UPSTREAM || DEFAULT_UPSTREAM).replace(/\/+$/, '')

  // `/api/ls/xxx` → `/ls/xxx`；`/api` → `/`
  const path = url.pathname.replace(/^\/api/, '') || '/'
  const target = upstream + path + url.search

  const headers = new Headers()
  for (const [k, v] of request.headers) {
    if (!STRIP_HEADERS.has(k.toLowerCase())) headers.set(k, v)
  }

  const init = { method: request.method, headers, redirect: 'manual' }

  if (!['GET', 'HEAD'].includes(request.method)) {
    const body = await request.arrayBuffer()
    if (body.byteLength > 0) {
      init.body = body
      headers.set('content-length', String(body.byteLength))
    }
  }

  let resp
  try {
    resp = await fetch(target, init)
  } catch (e) {
    // 上游不可达（隧道断了 / 本机没开机）—— 给一个**能自解释**的错误，而不是笼统的 502
    return new Response(
      JSON.stringify({
        code: 502,
        msg: 'API 上游不可达（演示环境的本机服务或隧道可能未启动）',
        data: null,
        success: false,
      }),
      { status: 502, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } },
    )
  }

  const outHeaders = new Headers(resp.headers)
  // ★ 保持流式的关键：告诉链路不要缓冲。
  //   （实测：即使不加，Node 运行时也没有缓冲 SSE；加上是显式表达意图，且对 CDN 层更保险。）
  outHeaders.set('x-accel-buffering', 'no')
  // 接口响应一律不缓存 —— 否则会出现"退出登录后仍能拿到旧数据"这类幽灵问题
  outHeaders.set('Cache-Control', 'no-store')

  return new Response(resp.body, { status: resp.status, headers: outHeaders })
}
