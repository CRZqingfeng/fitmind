#!/usr/bin/env node
/**
 * ============================================================
 * M8-2a 前端验收：多身份共存（#A2 —— 两端 refreshToken Cookie 冲突）
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010)、fm-auth(8081)、fm-user(8082) 已启动
 *   · 前端：fitmind-portal(5173)、fitmind-admin(5174) 的 dev server 已启动
 *
 * 用法：
 *   cd frontend && npm run accept:m8-2a
 *
 * ------------------------------------------------------------
 * ★★ 这个脚本存在的唯一理由：**同一个浏览器上下文**。
 *
 *   现有全部前端验收（m7-1 ~ m7-10）都给每个身份开一个独立 Context：
 *       const admin  = await (await browser.newContext(...)).newPage()
 *       const portal = await (await browser.newContext(...)).newPage()
 *   于是两个身份的 Cookie 被浏览器天然隔离 —— **而真实用户只有一个浏览器**。
 *
 *   #A2 正是被这种隔离掩盖掉的问题：
 *
 *     · localStorage 的隔离维度是 **origin（协议 + 域名 + 端口）**
 *         → localhost:5173 与 localhost:5174 各自独立 → 两端 token 不冲突 ✅
 *     · Cookie 的隔离维度是 **domain（协议 + 域名，不含端口）**
 *         → 5173 与 5174 是**同一份** Cookie
 *     · 而两端在 dev 下都直连同一个网关 `http://localhost:10010`
 *         → 这份 Cookie 是实实在在共享的
 *
 *   修复前：两端都写同一个 Cookie 名 `refresh`
 *     → 先登 portal、再登 admin，管理端的 refreshToken **覆盖**了学员的
 *     → 回到 portal，access 一过期去续期，读到的是**管理员的** refreshToken
 *     → 症状：portal 拿到管理员身份的 token（身份串号），或 userType 校验失败直接掉登录
 *
 *   修复后：两端各写各的名字（`refresh` / `admin-refresh`），互不干扰。
 *
 *   ⚠️ 所以本脚本**必须共用一个 Context**。若哪天有人"顺手"改成两个 Context，
 *      它会永远绿 —— 那时它就不再证明任何东西了。
 *
 * ------------------------------------------------------------
 * ★ 为什么这条在 M8-1 之后变得必须验：
 *   M8-1 把 access 的 TTL 从 30 天改成 30 分钟。
 *   这个 bug 的触发条件是"access 到期后去续期"，
 *   所以它的暴露时间从「一个月后」变成了「半小时后」。
 * ============================================================
 */

import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { chromium } from 'playwright'
import { launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots')

const PORTAL = 'http://localhost:5173'
const ADMIN = 'http://localhost:5174'
const GATEWAY = 'http://localhost:10010'

/** 学员账号：用户端唯一可登录的角色（后端 isStaff=false 时要求 type==学员） */
const STUDENT_PHONE = '13800010001'
/** 管理员账号：管理端要求 type != 学员 */
const ADMIN_PHONE = '13800010003'

/** 两端各自的 localStorage key —— 这一层本来就是分开的（M7 已收口） */
const TOKEN_KEY_PORTAL = 'fitmind:token'
const TOKEN_KEY_ADMIN = 'fitmind:admin:token'

/** ★ #A2 的修复点：两端各自独立的 Cookie 名 */
const COOKIE_PORTAL = 'refresh'
const COOKIE_ADMIN = 'admin-refresh'

/**
 * 塞进 localStorage 后必然触发 401 的"坏 token"。
 *
 * ★ 用 **JWT 的三段形状**，而不是随手一个字符串：解析方可能先做形状判断，
 *   形状不对会走到"非法凭证"的另一个分支，就拿不到我们要验的"续期"路径了。
 *   （与 m7-1 用的是同一个值）
 */
const BROKEN_TOKEN = 'eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.BROKEN_PAYLOAD.BROKEN_SIGNATURE'

let passed = 0
let failed = 0
const failures = []

function check(name, condition, detail = '') {
  if (condition) {
    passed++
    console.log(`  ✅ ${name}`)
  } else {
    failed++
    failures.push(name + (detail ? ` —— ${detail}` : ''))
    console.log(`  ❌ ${name}${detail ? ` —— ${detail}` : ''}`)
  }
}

function section(title) {
  console.log(`\n【${title}】`)
}

/** 前置条件检查：服务不可达时早点报清楚，而不是让 Playwright 抛一个超时 */
async function requireReachable(url, name) {
  try {
    await fetch(url, { method: 'GET', signal: AbortSignal.timeout(8000) })
  } catch {
    console.error(`\n❌ 前置条件不满足：${name} 不可达（${url}）`)
    console.error('   后端：bash scripts/start-all.sh fm-gateway / fm-auth / fm-user')
    console.error('   前端：cd frontend && npm run dev:portal 与 npm run dev:admin')
    process.exit(2)
  }
}

/**
 * 收集浏览器侧错误 + 网络侧记账
 *
 * ★ 分开记账的原因（沿用 m7-1 的做法）：
 *   本脚本会**故意**打出 401（塞坏 token），浏览器会记一条
 *   "Failed to load resource: 401" —— 那是预期行为，不该算失败；
 *   但 "TypeError: xxx is not a function" 是真的坏了，必须算。
 */
function attachCollectors(page, net) {
  const state = { pageErrors: [], consoleErrors: [] }

  page.on('pageerror', (err) => state.pageErrors.push(err.message))

  page.on('console', (msg) => {
    if (msg.type() !== 'error') return
    const text = msg.text()
    if (/Failed to load resource/i.test(text)) return
    state.consoleErrors.push(text)
  })

  page.on('response', async (res) => {
    const url = res.url()
    if (!url.startsWith(GATEWAY)) return
    const status = res.status()
    const path = url.replace(GATEWAY, '')

    // 鉴权链路单独记账：用来证明"走的是哪一条续期端点"
    if (path.includes('/accounts/')) net.authCalls.push(`${status} ${path}`)

    const ct = res.headers()['content-type'] ?? ''
    if (!ct.includes('json')) return
    try {
      const body = await res.json()
      const code = body?.code
      if (typeof code === 'number' && code !== 200) {
        // 401/403 是本脚本**故意**造出来的，单独归类，最后断言时豁免
        net.badCodes.push({
          code,
          status,
          path,
          deliberate: status === 401 || status === 403,
        })
      }
    } catch {
      /* 非 JSON、或响应体已被消费 —— 跳过，不影响断言 */
    }
  })

  return state
}

/** 读 Cookie jar 里的某个 Cookie（HttpOnly 也读得到，因为是从浏览器侧取的） */
async function cookieOf(ctx, name) {
  const list = await ctx.cookies()
  const hit = list.find((c) => c.name === name)
  return hit ? hit.value : null
}

/** 直接用某个 token 调 /us/users/me，返回 {status, code, data} */
async function meWith(token) {
  const res = await fetch(`${GATEWAY}/us/users/me`, {
    headers: { Authorization: token },
    signal: AbortSignal.timeout(15000),
  })
  const body = await res.json().catch(() => ({}))
  return { status: res.status, code: body?.code, data: body?.data }
}

/** 走一遍"验证码登录"：填手机号 → 取验证码（Mock 自动回填）→ 提交 */
async function loginWithCode(page, phone) {
  await page.fill('input[data-testid="login-phone"]', phone)
  await page.click('[data-testid="login-send-code"]')
  await page.waitForFunction(
    () => {
      const el = document.querySelector('input[data-testid="login-credential"]')
      return !!el && el.value.length > 0
    },
    { timeout: 15000 },
  )
  await page.click('[data-testid="login-submit"]')
}

/**
 * ★ 人为构造 401：把坏 token 写进 localStorage → 打开一个**会发鉴权请求**的页面
 *
 *   之后会发生：拦截器拿到 401 → 调**本端**的续期端点 → 换到新 token
 *   → 重放原请求 → 页面恢复。
 *
 *   ⚠️ 为什么必须人为构造：access 的 TTL 现在是 30 分钟，
 *      "干等它过期"在验收里不可行；而塞坏 token 能在几秒内逼出同一条路径。
 *
 *   ★★ 两个必须写对的参数（本脚本第一版就是在这两点上翻的车）：
 *
 *     ① `url` 必须是**会发鉴权请求**的页面。
 *        第一版让 portal 留在首页刷新 —— 而首页不调任何需要登录的接口，
 *        于是根本没有 401，续期也就不会发生。
 *        ★ "续期没被触发"和"续期坏了"在断言上长得一模一样，
 *          只能靠**选对页面**来区分。
 *
 *     ② `readySelector` 只用来确认"页面本体已经起来了"，**不能**拿它当"已恢复"的判据。
 *        第一版我用 `.user-name` / `[data-testid="admin-user-name"]` —— 但两端顶栏
 *        在**未登录**时也渲染这个元素（文案"未登录"）。
 *        更糟的是它带来一个**竞态**：布局先挂载、`fetchMe()` 后返回，
 *        于是选择器瞬间命中，断言在续期完成之前就跑了 —— 时绿时红。
 *
 *        ★★ 真正无竞态的判据只有一个：
 *           **localStorage 里的坏 token 被换成了新 token**。
 *           这件事**只能**由"一次成功的续期"造成，所以它是结构性事实、不是时序巧合。
 *           见下面 waitForFunction 那段（对应项目铁律「异步写入 → 断言必须轮询」）。
 *
 * @param tokenKey      该端的 localStorage key
 * @param url           该端一个会触发鉴权请求的页面
 * @param readySelector 页面本体已挂载的标志（不是"已登录"的判据）
 * @returns 续期后 localStorage 里的新 token
 */
async function forceRefreshByBadToken(page, { tokenKey, url, readySelector }) {
  await page.evaluate(({ key, bad }) => localStorage.setItem(key, bad), {
    key: tokenKey,
    bad: BROKEN_TOKEN,
  })
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
  if (readySelector) {
    await page.waitForSelector(readySelector, { timeout: 25000 })
  }
  // ★★ 等"坏 token 被替换"—— 这一步走完，才说明 401 → 续期 → 重放 整条链真的跑通了
  await page.waitForFunction(
    ({ key, bad }) => {
      const t = localStorage.getItem(key)
      return !!t && t !== bad && t.length > 100
    },
    { key: tokenKey, bad: BROKEN_TOKEN },
    { timeout: 25000 },
  )
  return page.evaluate((key) => localStorage.getItem(key), tokenKey)
}

/**
 * 启动浏览器
 *
 * ★ 回退逻辑沿用 m7-1：Playwright 自带的 Chromium 需要联网下载，
 *   网络受限时会卡住 —— 而"脚本跑不起来"和"功能坏了"是两件完全不同的事。
 *   Windows 自带 Edge，用 channel 直接驱动它即可（同一套 Chromium 内核）。
 */
// ★ 浏览器连接统一见 ./_cdp-browser.mjs —— 本机必须走 CDP，
//   `chromium.launch({ channel: 'msedge' })` 在这里一定失败（见模块头部说明）。

async function main() {
  console.log('============================================================')
  console.log(' FitMind M8-2a 前端验收（多身份共存 / #A2 两端 Cookie 冲突）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(ADMIN, '管理端 dev server')

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()

  // ★★ 全部断言都建立在这一行上：**一个 Context 里同时当学员和管理员**
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const net = { authCalls: [], badCodes: [] }

  const portal = await ctx.newPage()
  const portalState = attachCollectors(portal, net)
  const admin = await ctx.newPage()
  const adminState = attachCollectors(admin, net)

  try {
    // ================================================================
    section('1. ★ 同一个浏览器里，先登学员端、再登管理端')
    // ================================================================
    await portal.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await portal.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(portal, STUDENT_PHONE)
    await portal.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 })

    const portalToken0 = await portal.evaluate((k) => localStorage.getItem(k), TOKEN_KEY_PORTAL)
    check(
      '学员端登录成功，token 已落 localStorage',
      !!portalToken0 && portalToken0.length > 100,
      `长度=${portalToken0 ? portalToken0.length : 0}`,
    )

    const portalCookie0 = await cookieOf(ctx, COOKIE_PORTAL)
    check(
      `学员端登录后写入了 Cookie「${COOKIE_PORTAL}」`,
      !!portalCookie0,
      portalCookie0 ? '有值' : '缺失',
    )
    check(
      `此时不该出现 Cookie「${COOKIE_ADMIN}」（管理端还没登录）`,
      (await cookieOf(ctx, COOKIE_ADMIN)) === null,
      `实际=${await cookieOf(ctx, COOKIE_ADMIN)}`,
    )

    await admin.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(admin, ADMIN_PHONE)
    await admin.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 })
    await admin.waitForSelector('[data-testid="admin-user-name"]', { timeout: 20000 })

    const adminToken0 = await admin.evaluate((k) => localStorage.getItem(k), TOKEN_KEY_ADMIN)
    check(
      '管理端登录成功并进入后台',
      !!adminToken0 && adminToken0.length > 100,
      `长度=${adminToken0 ? adminToken0.length : 0}`,
    )

    const portalCookieNow = await cookieOf(ctx, COOKIE_PORTAL)
    const adminCookieNow = await cookieOf(ctx, COOKIE_ADMIN)

    // ★★★ 本节的三条就是 #A2 的正面证据
    check(
      '★★ 两端的 Cookie **同时**存在（各占各的名字）',
      !!portalCookieNow && !!adminCookieNow,
      `refresh=${portalCookieNow ? '有' : '无'}  admin-refresh=${adminCookieNow ? '有' : '无'}`,
    )
    check(
      '★★ 两个 Cookie 的值**不同**（不是同一份 refreshToken）',
      !!portalCookieNow && !!adminCookieNow && portalCookieNow !== adminCookieNow,
    )
    check(
      '★ 管理端登录没有覆盖学员端的 localStorage token',
      (await portal.evaluate((k) => localStorage.getItem(k), TOKEN_KEY_PORTAL)) === portalToken0,
    )

    await portal.screenshot({ path: join(SHOT_DIR, 'm8-2a-01-portal-logged-in.png') })
    await admin.screenshot({ path: join(SHOT_DIR, 'm8-2a-02-admin-logged-in.png') })

    // ================================================================
    section('2. ★★ 学员端续期：必须拿回「学员」身份，而不是管理员')
    // ================================================================
    const before2 = net.authCalls.length
    // ★ 打开「个人中心」—— 只有它会调 /us/users/me（首页不会，那样就永远等不到 401）
    const portalToken1 = await forceRefreshByBadToken(portal, {
      tokenKey: TOKEN_KEY_PORTAL,
      url: `${PORTAL}/me`,
      readySelector: '[data-testid="me-profile"]',
    })
    const calls2 = net.authCalls.slice(before2)

    check(
      '学员端确实发出了续期请求（而不是碰巧没 401）',
      calls2.some((c) => c.includes('/accounts/refresh')),
      `鉴权调用=[${calls2.join(' | ')}]`,
    )
    check(
      '★★ 学员端走的是**学员端**续期端点（200 /as/accounts/refresh）',
      calls2.some((c) => c.startsWith('200 ') && c.includes('/accounts/refresh') && !c.includes('/admin/')),
      `鉴权调用=[${calls2.join(' | ')}]`,
    )
    check(
      '学员端续期后 localStorage 已换成新 token',
      !!portalToken1 && portalToken1 !== BROKEN_TOKEN && portalToken1 !== portalToken0,
      `长度=${portalToken1 ? portalToken1.length : 0}`,
    )

    const portalMe = await meWith(portalToken1)
    // ★★★ 这是本脚本最核心的一条断言：
    //     修复前 portal 会读到管理员的 refreshToken，这里拿到的就是 ADMIN_PHONE
    check(
      '★★★ 续期后 /us/users/me 返回的是**学员**（修复前这里会拿到管理员）',
      portalMe.data?.cellPhone === STUDENT_PHONE,
      `期望=${STUDENT_PHONE} 实际=${portalMe.data?.cellPhone}（http=${portalMe.status} code=${portalMe.code}）`,
    )

    // 页面侧再验一次：接口对了、页面也必须对（别让"接口对但页面渲染的是旧缓存"蒙过去）
    const portalProfileText = (await portal.textContent('[data-testid="me-profile"]')) ?? ''
    check(
      '★★ 页面上渲染的手机号也是学员的（页面与接口一致）',
      portalProfileText.includes(STUDENT_PHONE),
      `页面片段="${portalProfileText.replace(/\s+/g, ' ').slice(0, 80)}"`,
    )

    await portal.screenshot({ path: join(SHOT_DIR, 'm8-2a-03-portal-after-refresh.png') })

    // ================================================================
    section('3. ★ 反向：管理端续期走自己的端点，拿回管理员身份')
    // ================================================================
    const before3 = net.authCalls.length
    const adminToken1 = await forceRefreshByBadToken(admin, {
      tokenKey: TOKEN_KEY_ADMIN,
      url: `${ADMIN}/`,
      readySelector: '[data-testid="admin-user-name"]',
    })
    const calls3 = net.authCalls.slice(before3)

    check(
      '★★ 管理端走的是**管理端**续期端点（200 /as/accounts/admin/refresh）',
      calls3.some((c) => c.startsWith('200 ') && c.includes('/accounts/admin/refresh')),
      `鉴权调用=[${calls3.join(' | ')}]`,
    )
    check(
      '管理端续期后 localStorage 已换成新 token',
      !!adminToken1 && adminToken1 !== BROKEN_TOKEN && adminToken1 !== adminToken0,
      `长度=${adminToken1 ? adminToken1.length : 0}`,
    )

    const adminMe = await meWith(adminToken1)
    check(
      '★★★ 续期后 /us/users/me 返回的是**管理员**',
      adminMe.data?.cellPhone === ADMIN_PHONE,
      `期望=${ADMIN_PHONE} 实际=${adminMe.data?.cellPhone}（http=${adminMe.status} code=${adminMe.code}）`,
    )

    // ================================================================
    section('4. ★ 登出互不干扰：学员端登出不该踢掉管理端')
    // ================================================================
    // ★ 这一步是"只清本端 Cookie"这个设计的直接验证。
    //   若 logout 图省事把两个 Cookie 都清了，管理端会在这一刻被静默踢掉。
    await portal.goto(`${PORTAL}/`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await portal.waitForSelector('.user-trigger', { timeout: 15000 })
    await portal.click('.user-trigger')
    await portal.click('text=退出登录')
    // ★★ portal 的登出有 ElMessageBox 二次确认 —— 不点确认按钮，登出**根本不会发生**。
    //    （第一版漏了这一步，症状是"等跳转超时"，很容易误判成登出功能坏了）
    await portal.click('.el-message-box__btns .el-button--primary')
    await portal.waitForURL((u) => u.pathname.startsWith('/login'), { timeout: 20000 })

    const portalCookieAfterLogout = await cookieOf(ctx, COOKIE_PORTAL)
    const adminCookieAfterLogout = await cookieOf(ctx, COOKIE_ADMIN)

    check(
      `学员端登出后 Cookie「${COOKIE_PORTAL}」被清除`,
      !portalCookieAfterLogout || portalCookieAfterLogout === '',
      `实际=${portalCookieAfterLogout === null ? 'null' : JSON.stringify(portalCookieAfterLogout)}`,
    )
    check(
      `★★ Cookie「${COOKIE_ADMIN}」**仍在**（登出没有误伤管理端）`,
      !!adminCookieAfterLogout,
      adminCookieAfterLogout ? '有值' : '被清掉了 —— 说明 logout 清错了范围',
    )

    // 管理端此时应该仍然能用：再塞一次坏 token，看它还能不能自己续回来
    const adminToken2 = await forceRefreshByBadToken(admin, {
      tokenKey: TOKEN_KEY_ADMIN,
      url: `${ADMIN}/`,
      readySelector: '[data-testid="admin-user-name"]',
    })
    const adminMe2 = await meWith(adminToken2)
    check(
      '★★★ 学员端已登出，管理端仍能独立续期且身份正确',
      adminMe2.data?.cellPhone === ADMIN_PHONE,
      `期望=${ADMIN_PHONE} 实际=${adminMe2.data?.cellPhone}`,
    )

    await admin.screenshot({ path: join(SHOT_DIR, 'm8-2a-04-admin-survives-portal-logout.png') })

    // ================================================================
    section('5. ★★ 反向对照：手工复现"修复前"的状态，证明这套断言真能抓到 bug')
    // ================================================================
    // ★ 一个只会亮绿灯的验收**没有价值** —— 它必须能证明自己会红。
    //
    //   这里把学员端的 Cookie 手工换成**管理员的** refreshToken。
    //   这**精确等于**修复前那段代码造成的状态：
    //     两端都写同一个名字 `refresh`，管理端登录时把学员端的覆盖掉了。
    //
    //   然后触发学员端续期，看它会不会串号。
    //   如果这一节还能"绿"，就说明前面的断言是恒绿的、抓不到任何东西。
    //
    //   ⚠️ 这一节是**故意制造错误状态**，所以它的期望结果是"页面确实串号"。

    // ① 让学员端重新登录（第 4 节刚把它登出了）
    await portal.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await portal.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(portal, STUDENT_PHONE)
    await portal.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 })

    // ② 把「refresh」覆盖成管理员的 refreshToken（保留其 domain / path / httpOnly）
    const jar = await ctx.cookies()
    const adminCookieObj = jar.find((c) => c.name === COOKIE_ADMIN)
    check('管理端的 Cookie 仍在（这是反向对照的前提）', !!adminCookieObj)
    await ctx.addCookies([{ ...adminCookieObj, name: COOKIE_PORTAL }])
    check(
      '★ 已把学员端 Cookie 覆盖为管理员的 refreshToken（= 修复前的状态）',
      (await cookieOf(ctx, COOKIE_PORTAL)) === (await cookieOf(ctx, COOKIE_ADMIN)),
    )

    // ③ 触发学员端续期，看它拿到的是谁的身份
    await portal.evaluate(({ key, bad }) => localStorage.setItem(key, bad), {
      key: TOKEN_KEY_PORTAL,
      bad: BROKEN_TOKEN,
    })
    await portal.goto(`${PORTAL}/me`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    // ★ 这里刻意**不用** waitForFunction 等"token 被换"：换是换了，只是换成了错的人。
    //   直接等页面把用户信息渲染出来。
    await portal.waitForSelector('[data-testid="me-profile"]', { timeout: 25000 })
    const crossedText = (await portal.textContent('[data-testid="me-profile"]')) ?? ''

    check(
      '★★★ 反向对照：学员端此时拿到了**管理员**身份（复现 #A2 原始症状）',
      crossedText.includes(ADMIN_PHONE),
      `页面片段="${crossedText.replace(/\s+/g, ' ').slice(0, 90)}"`,
    )
    check(
      '★★★ 反向对照：这个身份**不是**学员本人（串号确实发生了）',
      !crossedText.includes(STUDENT_PHONE),
      `页面片段="${crossedText.replace(/\s+/g, ' ').slice(0, 90)}"`,
    )

    await portal.screenshot({
      path: join(SHOT_DIR, 'm8-2a-05-negative-control-identity-crossed.png'),
    })

    // ================================================================
    section('6. 收尾：浏览器异常与业务码')
    // ================================================================
    for (const [label, st] of [
      ['学员端', portalState],
      ['管理端', adminState],
    ]) {
      check(
        `${label}无 JS 运行时异常（pageerror 为 0）`,
        st.pageErrors.length === 0,
        st.pageErrors.slice(0, 3).join(' | '),
      )
      check(
        `${label}无 console error`,
        st.consoleErrors.length === 0,
        st.consoleErrors.slice(0, 3).join(' | '),
      )
    }

    // ★ 项目铁律：业务码非 200 不产生 pageerror / console error，
    //   所以必须单独断言 —— 否则"验收全绿但用户看到红条"。
    const unexpected = net.badCodes.filter((x) => !x.deliberate)
    check(
      '★ 没有"非预期"的业务码非 200 响应（故意造出的 401 已豁免）',
      unexpected.length === 0,
      unexpected.map((x) => `code=${x.code} ${x.path}`).join(' | '),
    )
  } finally {
    await browser.close()
  }

  // ================================================================
  console.log('\n============================================================')
  console.log(` 结果：${passed} 通过 / ${failed} 失败`)
  if (failures.length) {
    console.log(' 失败项：')
    failures.forEach((f) => console.log(`   · ${f}`))
  }
  console.log('============================================================')
  process.exit(failed ? 1 : 0)
}

main().catch((err) => {
  console.error('\n❌ 脚本异常终止：', err)
  process.exit(1)
})
