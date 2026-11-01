#!/usr/bin/env node
/**
 * ============================================================
 * M7-1 前端验收：登录 / 请求层（401 续期）/ 布局 / 退出
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010)、fm-auth(8081)、fm-user(8082) 已启动
 *   · 前端：fitmind-portal(5173)、fitmind-admin(5174) 的 dev server 已启动
 *
 * 用法：
 *   cd frontend && npm run accept
 *   （等价于 node scripts/accept-web-m7-1.mjs）
 *
 * ------------------------------------------------------------
 * ★ 这个脚本的取舍，跟后端那批验收脚本保持一致：**不接受"看起来渲染出来了"**。
 *   具体做了三件后端也在做的事：
 *
 *   (1) 交叉验证 —— 页面显示的手机号必须等于"直接调接口拿到"的手机号。
 *       「渲染出东西了」和「渲染的是对的数据」是两件事
 *       （对应后端 M6-6 的"三层对账"思路）。
 *
 *   (2) ★★ 主动构造 401 —— 把一个**坏 token** 写进 localStorage 再刷新页面，
 *       然后断言两件事：页面最终仍能正常显示用户信息 **且** 确实发出过
 *       `GET /as/accounts/refresh`。
 *
 *       ⚠️ 为什么必须人为构造：本项目 accessToken 的 TTL 是 **30 天**、
 *          refreshToken 只有 **30 分钟**（`JwtConstants` 的既有取值）——
 *          正常使用**几乎永远不会**触发续期。如果只跑"正常流程"，
 *          这段续期代码就是**从未被执行过的死代码**，等它第一次真正跑起来
 *          （线上或演示时）才会暴露问题。这里把它逼出来跑一遍。
 *
 *   (3) 只断言**结构性事实**（data-testid + 接口字段），不依赖界面文案。
 *       断言绑文案 = 改一个按钮字脚本就挂，而挂掉的脚本很容易被"重跑一次"掩盖。
 *
 *   (4) 同时统计浏览器 JS 运行时异常（pageerror），必须为 0 ——
 *       "页面看着是对的"但控制台在报错，是前端最常见的"隐性坏了"。
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

/** ⚠️ 两个端的 storage key 不同（见各自 utils/token.ts 的注释） */
const TOKEN_KEY_PORTAL = 'fitmind:token'
const TOKEN_KEY_ADMIN = 'fitmind:admin:token'

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
 * 收集浏览器侧的错误
 *
 * ★ 分开收集 pageerror（JS 运行时异常）和 console error：
 *   401 续期那一步会**故意**打出一个 401 响应，浏览器会记一条
 *   "Failed to load resource: 401" —— 那是预期行为，不该算失败。
 *   但 "TypeError: xxx is not a function" 这类是真的坏了，必须算。
 */
function attachErrorCollectors(page) {
  const state = { pageErrors: [], consoleErrors: [] }
  page.on('pageerror', (err) => state.pageErrors.push(err.message))
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return
    const text = msg.text()
    // 网络层的状态码错误是预期内的（401 续期用例会故意造一个）
    if (/Failed to load resource/i.test(text)) return
    state.consoleErrors.push(text)
  })
  return state
}

/** 走一遍"验证码登录"：填手机号 → 取验证码（Mock 自动回填）→ 提交 */
async function loginWithCode(page, phone) {
  await page.fill('input[data-testid="login-phone"]', phone)
  await page.click('[data-testid="login-send-code"]')
  // Mock 模式下后端直接返回验证码，前端自动回填 —— 等它填进去
  await page.waitForFunction(
    () => {
      const el = document.querySelector('input[data-testid="login-credential"]')
      return !!el && el.value.length > 0
    },
    { timeout: 15000 },
  )
  const code = await page.inputValue('input[data-testid="login-credential"]')
  await page.click('[data-testid="login-submit"]')
  return code
}

/**
 * 启动浏览器
 *
 * ★ 为什么要写回退：Playwright 自带的 Chromium 需要联网下载（约 130MB），
 *   在网络受限的环境里这一步可能长时间卡住 —— 而"验收脚本跑不起来"
 *   和"功能坏了"是两件完全不同的事，不该混在一起判断。
 *   Windows 自带 Edge，Playwright 官方支持用 channel 直接驱动它，
 *   于是没有下载也能跑完整验收（同一套 Chromium 内核，行为一致）。
 */
// ★ 浏览器连接统一见 ./_cdp-browser.mjs —— 本机必须走 CDP，
//   `chromium.launch({ channel: 'msedge' })` 在这里一定失败（见模块头部说明）。

async function main() {
  console.log('============================================================')
  console.log(' FitMind M7-1 前端验收（登录 / 请求层 / 401 续期 / 布局 / 退出）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(ADMIN, '管理端 dev server')

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  const errors = attachErrorCollectors(page)

  // 记录续期请求（用于证明续期链路真的跑过，而不是"没走到 401"）
  const refreshCalls = []
  page.on('response', (res) => {
    if (res.url().includes('/as/accounts/refresh')) refreshCalls.push(res.status())
  })

  try {
    // ================================================================
    section('1. 用户端：未登录访问受保护页面 → 应被路由守卫送到登录页')
    // ================================================================
    // 直接从 /me 进（不是 /login），验证守卫是否生效
    await page.goto(`${PORTAL}/me`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await page.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    check('未登录访问 /me 被重定向到登录页', page.url().includes('/login'), `当前 URL=${page.url()}`)
    check(
      '重定向时带上了 redirect 参数（登录后可跳回原页面）',
      page.url().includes('redirect='),
      `当前 URL=${page.url()}`,
    )
    await page.screenshot({ path: join(SHOT_DIR, 'm7-1-01-portal-login.png') })

    // ================================================================
    section('2. 用户端：验证码登录（Mock 模式自动回填）')
    // ================================================================
    const code = await loginWithCode(page, STUDENT_PHONE)
    check('Mock 模式验证码已自动回填到输入框', /^\d{4,6}$/.test(code), `取到的值="${code}"`)

    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 20000 })
    check('登录成功后离开登录页', !page.url().includes('/login'), `当前 URL=${page.url()}`)

    const token = await page.evaluate((key) => localStorage.getItem(key), TOKEN_KEY_PORTAL)
    check(
      'accessToken 已写入 localStorage（刷新页面不掉登录的依据）',
      !!token && token.length > 100,
      `长度=${token ? token.length : 0}`,
    )

    await page.waitForSelector('.user-name', { timeout: 15000 })
    const nameOnPage = (await page.textContent('.user-name')) ?? ''
    check('顶部栏渲染出当前用户名', nameOnPage.trim().length > 0, `实际="${nameOnPage.trim()}"`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-1-02-portal-home.png'), fullPage: true })

    // ================================================================
    section('3. ★ 交叉验证：页面显示的数据 == 接口返回的数据')
    // ================================================================
    // 直接在浏览器里调接口拿"真值"（复用同一份 token，避免两边环境不一致）
    const apiUser = await page.evaluate(async ({ gateway, key }) => {
      const t = localStorage.getItem(key)
      const res = await fetch(`${gateway}/us/users/me`, { headers: { Authorization: t } })
      return (await res.json()).data
    }, { gateway: GATEWAY, key: TOKEN_KEY_PORTAL })

    check('接口 /us/users/me 返回了用户数据', !!apiUser && !!apiUser.cellPhone)

    await page.click('.user-trigger')
    await page.click('text=个人中心')
    await page.waitForSelector('[data-testid="me-profile"]', { timeout: 20000 })
    const profileText = (await page.textContent('[data-testid="me-profile"]')) ?? ''

    check(
      '页面上显示的手机号 == 接口返回的手机号',
      profileText.includes(apiUser.cellPhone),
      `接口="${apiUser.cellPhone}"`,
    )
    check(
      '页面上显示的用户 ID == 接口返回的 ID',
      profileText.includes(` ${apiUser.id} `) || profileText.includes(`\n${apiUser.id}\n`) || profileText.includes(apiUser.id),
      `接口 id="${apiUser.id}"（类型 ${typeof apiUser.id}）`,
    )
    check(
      '★ 后端 Long 序列化为字符串（说明类型声明 string 是必要的）',
      typeof apiUser.id === 'string',
      `实际类型=${typeof apiUser.id}`,
    )
    check(
      '页面上显示的角色 == 接口返回的角色名',
      profileText.includes(apiUser.roleName),
      `接口 roleName="${apiUser.roleName}"`,
    )
    await page.screenshot({ path: join(SHOT_DIR, 'm7-1-03-portal-me.png'), fullPage: true })

    // ================================================================
    section('4. ★★ 401 自动续期（人为构造，否则这段代码永远不会被执行）')
    // ================================================================
    const brokenToken = 'eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.BROKEN_PAYLOAD.BROKEN_SIGNATURE'
    refreshCalls.length = 0

    // ① 把坏 token 写进 localStorage（store 里是内存副本，改完必须刷新页面才生效）
    await page.evaluate(({ key, bad }) => localStorage.setItem(key, bad), {
      key: TOKEN_KEY_PORTAL,
      bad: brokenToken,
    })
    await page.goto(`${PORTAL}/me`, { waitUntil: 'domcontentloaded', timeout: 30000 })

    // ② 页面应当先 401 → 拦截器自动续期 → 重放原请求 → 最终正常渲染
    let recovered = true
    try {
      await page.waitForSelector('[data-testid="me-profile"]', { timeout: 20000 })
    } catch {
      recovered = false
    }
    check(
      '坏 token 触发 401 后，页面最终仍正常渲染用户信息（说明续期 + 重放成功）',
      recovered,
      recovered ? '' : `当前 URL=${page.url()}（若已跳登录页，说明续期链路失败）`,
    )
    check(
      '确实发出过 GET /as/accounts/refresh（证明走的是续期，而不是碰巧没 401）',
      refreshCalls.length >= 1,
      `观察到 ${refreshCalls.length} 次`,
    )
    check(
      '续期接口返回 200（HttpOnly Cookie 被正确携带 —— withCredentials 生效）',
      refreshCalls.includes(200),
      `状态码序列=[${refreshCalls.join(',')}]`,
    )

    const refreshedToken = await page.evaluate((key) => localStorage.getItem(key), TOKEN_KEY_PORTAL)
    check(
      'localStorage 中的坏 token 已被替换为有效的新 token',
      !!refreshedToken && refreshedToken !== brokenToken && refreshedToken.length > 100,
      `长度=${refreshedToken ? refreshedToken.length : 0}`,
    )

    // ================================================================
    section('5. 用户端：退出登录')
    // ================================================================
    await page.goto(`${PORTAL}/`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('.user-trigger', { timeout: 15000 })
    await page.click('.user-trigger')
    await page.click('text=退出登录')
    // ElMessageBox 的确认按钮（文案在组件里配置，这里用结构选择器更稳）
    await page.click('.el-message-box__btns .el-button--primary')
    await page.waitForURL((url) => url.pathname.startsWith('/login'), { timeout: 20000 })
    check('退出后跳回登录页', page.url().includes('/login'), `当前 URL=${page.url()}`)

    const tokenAfterLogout = await page.evaluate((key) => localStorage.getItem(key), TOKEN_KEY_PORTAL)
    check('退出后 localStorage 中 token 已清除', tokenAfterLogout === null, `实际="${tokenAfterLogout}"`)

    // 再访问受保护页面，应再次被拦回登录页（证明退出是"真的退了"）
    await page.goto(`${PORTAL}/me`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('input[data-testid="login-phone"]', { timeout: 20000 })
    check('退出后访问 /me 再次被拦回登录页', page.url().includes('/login'), `当前 URL=${page.url()}`)

    // ================================================================
    section('6. 管理端：登录（账号类型要求与用户端相反）')
    // ================================================================
    await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await page.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    const adminCode = await loginWithCode(page, ADMIN_PHONE)
    check('管理端 Mock 验证码自动回填', /^\d{4,6}$/.test(adminCode), `取到的值="${adminCode}"`)

    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 20000 })
    check('管理员登录成功并进入后台', !page.url().includes('/login'), `当前 URL=${page.url()}`)

    const adminToken = await page.evaluate((key) => localStorage.getItem(key), TOKEN_KEY_ADMIN)
    check(
      '管理端 token 写在独立的 storage key（不与用户端互相覆盖）',
      !!adminToken && adminToken.length > 100,
      `长度=${adminToken ? adminToken.length : 0}`,
    )

    // ★ 这里原来等的是 `[data-testid="admin-welcome"]`（断言文案含"欢迎"）——
    //   那个元素在 **M7-8 重写后台首页**（改成 KPI 看板）时就被删掉了。
    //   而本脚本当时**不在总验收的 ALL 列表里**（M8-1 才补进去），
    //   于是这条断言烂了很久没人发现。
    //   → 改锚到「顶栏显示当前登录用户」，它表达的才是
    //     "已进入后台、且认得当前用户"这个**长期有效**的语义，
    //     而不是某一版首页的文案。
    await page.waitForSelector('[data-testid="admin-user-name"]', { timeout: 20000 })
    const adminUserName = ((await page.textContent('[data-testid="admin-user-name"]')) ?? '').trim()
    check(
      '后台顶栏显示出当前登录用户（而不是"未登录"）',
      adminUserName.length > 0 && adminUserName !== '未登录',
      `实际="${adminUserName.slice(0, 40)}"`,
    )

    const adminApiUser = await page.evaluate(async ({ gateway, key }) => {
      const t = localStorage.getItem(key)
      const res = await fetch(`${gateway}/us/users/me`, { headers: { Authorization: t } })
      return (await res.json()).data
    }, { gateway: GATEWAY, key: TOKEN_KEY_ADMIN })
    check(
      '管理端 /us/users/me 返回的角色类型不是学员（符合 isStaff 校验）',
      adminApiUser?.type !== 2,
      `实际 type=${adminApiUser?.type}（1=管理员 2=学员 3=教练）`,
    )
    check(
      '管理端页面显示的用户名 == 接口返回值',
      adminUserName.includes(adminApiUser?.name || adminApiUser?.cellPhone || '###'),
      `页面="${adminUserName}" / 接口 name="${adminApiUser?.name}"`,
    )
    await page.screenshot({ path: join(SHOT_DIR, 'm7-1-04-admin-dashboard.png'), fullPage: true })

    // ================================================================
    section('7. 浏览器运行时健康度')
    // ================================================================
    check(
      '全程无 JS 运行时异常（pageerror）',
      errors.pageErrors.length === 0,
      errors.pageErrors.slice(0, 3).join(' | '),
    )
    check(
      '全程无非网络类 console error',
      errors.consoleErrors.length === 0,
      errors.consoleErrors.slice(0, 3).join(' | '),
    )
  } finally {
    await browser.close()
  }

  // ---------- 汇总 ----------
  console.log('\n============================================================')
  console.log(` 结果：${passed} 通过 / ${failed} 失败`)
  if (failures.length) {
    console.log(' 失败项：')
    failures.forEach((f) => console.log(`   · ${f}`))
  }
  console.log(` 截图：${SHOT_DIR}`)
  console.log('============================================================')
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('脚本异常：', e)
  process.exit(2)
})
