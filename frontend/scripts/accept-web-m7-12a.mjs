/**
 * M7-12a 端到端验收：portal 缺口补齐（10 个接口）
 *
 * 运行：cd frontend && npm run accept:m7-12a
 * 前置：后端 15 服务 + dev server 5173。
 *
 * 本脚本覆盖（都是 M7 方案 §7.1 承诺过、但此前未交付的那几块）：
 *   ① 注册            POST /us/students/register   + GET /us/users/checkCellphone
 *   ② 忘记密码        PUT  /us/students/password
 *   ③ 资料编辑 + 头像  PUT  /us/users/me            + POST /ms/files · DELETE /ms/files/{id}
 *   ④ 身体档案        GET/PUT /us/users/me/profile
 *   ⑤ 我的券 + 兑换码  POST /prs/user-coupons/exchange/{code}
 *   ⑥ 退款详情        GET  /ts/refund-apply/{id}
 *   ⑦ 明细反查退款     GET  /ts/refund-apply/detail/{detailId}   ← 落在**订单详情页**
 *   ⑧ 支付单状态      GET  /ps/pay-orders/{payOrderNo}
 *
 * ★★ 两条本脚本的自觉（都是 M7 踩过的，写在最前面免得忘）：
 *
 *   1. **注册用例必须清理自己造的账号**。这条与 #B7 同族 ——
 *      `accept-m1.mjs` 的注册用例每跑一轮就往 `fm_user.user` 留一个学员，
 *      库里已经攒到 id=4/5 两个 `139…` 账号（实测）。
 *      所以这里注册完、验完，**必须删掉并自检回到基线**（§2 末）。
 *
 *   2. **「期望为空 / 不包含」的断言要同时校验"前置动作真的成功了"**（清单 §四 36 条）。
 *      例如"兑换码第二次必须失败"—— 如果第一次兑换本身就失败了，第二次也会"失败"，
 *      断言照样绿。所以第二次之前先断言第一次**确实成功了**（券数真的 +1）。
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { chromium } from 'playwright'
import { launchBrowser } from './_cdp-browser.mjs'

const PORTAL = 'http://localhost:5173'
const ADMIN = 'http://localhost:5174'
const GATEWAY = 'http://localhost:10010'
const SHOT_DIR = 'D:/develop/develop/code/fitmind/docs/screenshots'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'

const STUDENT_PHONE = '13800010001'
const ADMIN_PHONE = '13800010003'
const SEED_PASSWORD = '123456'
const PORTAL_TOKEN_KEY = 'fitmind:token'
const ADMIN_TOKEN_KEY = 'fitmind:admin:token'

let pass = 0
let fail = 0
/** ★ "未验证"单列一类：绝不与"通过"混在一起（否则就是 #C30 那种静默假绿） */
let skipped = 0
const failures = []
const skipNotes = []

function check(label, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`  ✅ ${label}${detail ? `（${detail}）` : ''}`)
  } else {
    fail++
    failures.push(label + (detail ? ` —— ${detail}` : ''))
    console.log(`  ❌ ${label}${detail ? `（${detail}）` : ''}`)
  }
}

/**
 * 需要"直查 MySQL"的断言。
 *
 * ★★ 为什么单独一个函数：**本机在 WorkBuddy 沙箱里跑时，Node 派生子进程会被拒**
 *   （`spawnSync ... EBUSY`）—— 那是**执行环境的限制，不是功能坏了**。
 *   这种情况下要用**显式"未验证"**表达，并把条数打进汇总行：
 *   · 绝不静默跳过（那就是 #C30 的假绿：断言写在条件分支里，条件不满足就不跑，看着全绿）
 *   · 也绝不误报成 ❌（那不是被测对象的问题，会把人带偏）
 *
 * ★ 更重要的是：**能用接口验证的事实，就不要依赖 SQL**。
 *   例如"账号是否真的删掉了"可以用 `checkCellphone` 反查、
 *   "券是否真的 +1"可以比 `GET /prs/user-coupons` 前后条数 ——
 *   这些断言在任何环境都能跑，SQL 只留作补充证据。
 */
/** ★ "受阻"单列一类：与"通过""失败""未验证"都不同，必须显式可见 */
let blocked = 0
const blockedNotes = []

function checkBlocked(label, note) {
  blocked++
  blockedNotes.push(`${label} —— ${note}`)
  console.log(`  🚧 ${label} —— ${note}`)
}

function checkSql(label, ok, detail = '') {
  if (!SQL_AVAILABLE) {
    skipped++
    skipNotes.push(label)
    console.log(`  ⏭️  ${label} —— 本环境无法执行 MySQL，未验证${detail ? `（${detail}）` : ''}`)
    return
  }
  check(label, ok, detail)
}

function section(t) {
  console.log('')
  console.log('='.repeat(74))
  console.log(`  ${t}`)
  console.log('='.repeat(74))
}

const flat = (s) => (s ?? '').replace(/\s+/g, ' ').trim()

// ============================================================
// 直查数据库（★ 验收 = 直查真实数据，不看返回码）
// ============================================================

/**
 * 执行一条 SQL，返回**按行切好、按 `\t` 切列**的二维数组。
 *
 * ⚠️ `mysql -N` 的列分隔符是 **`\t`**，不是 `|`（清单里专门记过这条）。
 */
function sqlCells(query) {
  const r = spawnSync(
    MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  if (r.status !== 0) return []
  return (r.stdout ?? '')
    .split('\n')
    .map((l) => l.trimEnd())
    .filter((l) => l.length > 0)
    .map((l) => l.split('\t'))
}

function sqlOne(query) {
  const rows = sqlCells(query)
  return rows[0]?.[0] ?? null
}

/** 启动时探一次：本环境到底能不能跑 mysql（探针失败 ≠ 功能失败） */
const SQL_AVAILABLE = (() => {
  const r = spawnSync(
    MYSQL,
    ['-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', 'SELECT 1'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  const ok = r.status === 0 && (r.stdout ?? '').trim() === '1'
  if (!ok) {
    console.log(`  ⚠️ 本环境无法执行 MySQL（${r.error?.code ?? 'exit ' + r.status}）——`)
    console.log('     "直查数据库"类断言将**显式标记为未验证**（不静默跳过、也不算失败）。')
  }
  return ok
})()

// ============================================================
// 网关调用（在页面里发，自动带上该端的 token）
// ============================================================

async function api(page, method, path, body, tokenKey, retries = 2) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await page.evaluate(
        async ([m, p, b, key]) => {
          const token = localStorage.getItem(key)
          const headers = {}
          if (b !== undefined && b !== null) headers['Content-Type'] = 'application/json'
          if (token) headers.Authorization = token
          const init = { method: m, headers }
          if (b !== undefined && b !== null) init.body = JSON.stringify(b)
          const res = await fetch(`http://localhost:10010${p}`, init)
          const text = await res.text()
          let json = null
          try {
            json = JSON.parse(text)
          } catch {
            json = null
          }
          return { status: res.status, body: json, raw: text.slice(0, 160) }
        },
        [method, path, body ?? null, tokenKey],
      )
    } catch (e) {
      const msg = String(e?.message ?? e)
      if (attempt < retries && /Execution context was destroyed|Target closed/i.test(msg)) {
        await page.waitForLoadState('domcontentloaded').catch(() => undefined)
        await page.waitForTimeout(500)
        continue
      }
      throw e
    }
  }
}

const portalApi = (page, m, p, b) => api(page, m, p, b, PORTAL_TOKEN_KEY)

/**
 * Node 侧直连网关（用于**管理员**的两次调用）。
 *
 * ★ 为什么管理员不走"页面里发请求"（像 portal 那样）：
 *   那是为了复用页面 localStorage 里的 token；而管理员的 token 我在 §1 已经拿到了，
 *   再起一个 5174 的 dev server 只为发两个请求，等于给本脚本加一个没必要的前置依赖
 *   （而且 5174 没起时脚本会直接崩在 goto 上，报错信息还看不出是这个原因）。
 */
async function gw(method, path, { token, body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = token
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let json = null
  try {
    json = await res.json()
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, body: json }
}

async function loginByApi(page, base, path, phone, tokenKey, password = SEED_PASSWORD) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 1, cellPhone: phone, password, rememberMe: true }),
  })
  const body = await res.json()
  if (!body?.data) throw new Error(`登录失败：code=${body?.code} msg=${body?.msg}`)
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await page.evaluate(([k, t]) => localStorage.setItem(k, t), [tokenKey, body.data])
  await page.waitForLoadState('domcontentloaded').catch(() => undefined)
  await page.waitForTimeout(800)
  return body.data
}

/** 直接打网关拿 token（不经过页面），用于"注册完能不能真登录"这种纯接口断言 */
async function loginRaw(path, phone, password) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 1, cellPhone: phone, password, rememberMe: false }),
  })
  const body = await res.json().catch(() => null)
  return { ok: !!body?.data, code: body?.code, msg: body?.msg, token: body?.data ?? null }
}

/** 已登录的页面等它稳定（M7-9 的教训：注入 token 后要等，否则首个请求会读到旧状态） */
async function waitStable(page, selector, timeout = 30000) {
  await page.waitForSelector(selector, { timeout })
  await page.waitForTimeout(500)
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })

  // ★ 本次注册的手机号（§2 造、§2 末清理）—— 用时间戳保证可重复跑
  const newPhone = `139${String(Date.now()).slice(-8)}`
  const newPassword = 'abc12345'
  const resetPassword = 'xyz98765'

  const browser = await launchBrowser()
  const portal = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
  /**
   * ★ 一个**未登录**的干净上下文，专门跑注册 / 忘记密码。
   *
   *   为什么不能用 `portal`（它已登录学员）：
   *   注册页是 `public` 路由，而登录页的守卫里有这一条 ——
   *   「已登录还去 /login → 直接回首页」。于是注册成功后的
   *   `router.replace('/login?phone=...')` 会被守卫**弹回 `/`**，
   *   脚本看到 URL 是 `/` 就会判失败 —— 而那其实是**守卫在正确工作**。
   *   （第一版就是这么错的，报错信息还看不出原因。）
   */
  const anon = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()

  const pageErrors = []
  const businessErrors = []
  /**
   * ★★ 业务错误收集开关 —— 负向用例（刻意打 403 / 刻意重复兑换）必须先关掉它，
   *   否则"我自己的负向测试"会被算成"页面有业务错误"，
   *   变成"负向测越充分、健康度越红"，最后只能删掉健康度断言。
   *   （M7-8 / M7-10 都吃过这条，口径要排除"我自己的行为"。）
   */
  let collectErrors = true
  for (const [pg, tag] of [
    [portal, 'portal'],
    [anon, 'anon'],
  ]) {
    pg.on('pageerror', (e) => pageErrors.push(`[${tag}] ${e.message}`))
    pg.on('response', async (res) => {
      if (!collectErrors || !res.url().includes('localhost:10010')) return
      try {
        const json = await res.json()
        if (json && typeof json.code === 'number' && json.code !== 200) {
          businessErrors.push(`[${tag}] ${res.request().method()} ${res.url().split('10010')[1]} → ${json.code}`)
        }
      } catch {
        /* 非 JSON */
      }
    })
  }

  // ================================================================
  section('1. 登录（学员走页面 + 管理员拿 token）')
  // ================================================================
  await loginByApi(portal, PORTAL, '/as/accounts/login', STUDENT_PHONE, PORTAL_TOKEN_KEY)
  check('学员登录成功', true)
  const adminLogin = await loginRaw('/as/accounts/admin/login', ADMIN_PHONE, SEED_PASSWORD)
  const adminToken = adminLogin.token
  check('管理员登录成功（§6 生成兑换码要用它）', adminLogin.ok, `code=${adminLogin.code}`)

  // ================================================================
  section('2. 注册（★ 本节的账号会在节末自己清理）')
  // ================================================================
  const baselineUsers = SQL_AVAILABLE ? Number(sqlOne(`SELECT COUNT(*) FROM fm_user.user`) ?? -1) : null
  console.log(`     基线：fm_user.user ${SQL_AVAILABLE ? baselineUsers : '(未查)'} 行；本次将注册 ${newPhone}`)

  /**
   * ① 手机号可用性接口。
   *
   * ⚠️ ★★ 两条实测出来的硬事实：
   *   1. 参数名是 **`cellphone`**，不是 `phone` ——
   *      传 `phone` 会掉进"缺少必要参数" → HTTP 200 + `code 500`「系统繁忙」，
   *      报错完全看不出是参数名写错（第一版就踩了）。
   *   2. ~~这个接口要求登录，所以免登录的注册页不能用它~~ → **#A16 已修**：
   *      网关 `excludePath` 放行了 `GET:/us/users/checkCellphone`，
   *      注册页的手机号预校验（`handleCheckPhone`）**已启用**，见下面的失焦断言。
   */
  const usableBefore = await portalApi(portal, 'GET', `/us/users/checkCellphone?cellphone=${newPhone}`)
  check('★ 未注册的手机号 → checkCellphone 返回 true（注意参数名是 cellphone）',
    usableBefore.body?.data === true, `data=${usableBefore.body?.data}`)
  // ★ 顺手验一下"已注册的号返回 false"—— 否则"恒返回 true"也能让上一条绿
  const usableTaken = await portalApi(portal, 'GET', `/us/users/checkCellphone?cellphone=${STUDENT_PHONE}`)
  check('★ 已注册的手机号 → 返回 false（证明这个接口不是恒 true）',
    usableTaken.body?.data === false, `data=${usableTaken.body?.data}`)

  // ② 页面注册（★ 用未登录的 anon 页面，因为登录页守卫会把已登录用户弹回首页）
  await anon.goto(`${PORTAL}/register`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await waitStable(anon, '[data-testid="register-phone"]')
  await anon.fill('[data-testid="register-phone"]', newPhone)
  /**
   * ★★ 失焦要验两件事，缺一不可：
   *   ① **留在注册页**（护栏）—— 第一版 checkCellphone 是 401 的，
   *      401 → 全局 `forceLogin()` → 页面跳到 `/login?redirect=/register`，
   *      用户还没注册就被踢走（这就是 #A16 的成因，现已修）
   *   ② **给出"可以注册"的提示** —— 只验①会漏掉"预校验其实没接上"的情况
   *      （页面没跳走，但那个功能压根没生效，等于白做）
   */
  await anon.click('[data-testid="register-phone"]')
  await anon.keyboard.press('Tab')
  await anon.waitForTimeout(1200)
  check('★★ 手机号失焦后仍在注册页（没有因 401 被强制跳去登录页）',
    anon.url().includes('/register'), anon.url())
  const phoneHint = flat(await anon.textContent('.register-card'))
  check('★★ 且提示「该手机号可以注册」（证明预校验真的接上了、不是没生效）',
    phoneHint.includes('可以注册'), phoneHint.slice(0, 60))

  await anon.click('[data-testid="register-send-code"]')
  let code = ''
  for (let i = 0; i < 25; i++) {
    code = await anon.inputValue('[data-testid="register-code"]')
    if (code) break
    await anon.waitForTimeout(300)
  }
  check('★★ Mock 模式验证码自动回填（POST /us/codes 拿到的）', /^\d{4,8}$/.test(code), `code=${code}`)
  await anon.fill('[data-testid="register-password"]', newPassword)
  await anon.fill('[data-testid="register-confirm-password"]', newPassword)
  await anon.fill('[data-testid="register-name"]', 'M7-12 验收账号')
  await anon.screenshot({ path: join(SHOT_DIR, 'm7-12a-01-register-filled.png') })
  await anon.click('[data-testid="register-submit"]')
  // 注册成功 → 跳回登录页（注册接口不返回 token，所以必须登录一次）
  await anon.waitForURL(/\/login/, { timeout: 30000 }).catch(() => undefined)
  await anon.waitForTimeout(800)
  check('★ 注册成功后跳到登录页，且手机号被带过去（不用重敲）',
    anon.url().includes('/login') && anon.url().includes(newPhone), anon.url().slice(0, 90))

  // ③ ★★ 注册"真的成功了"的唯一证明：新账号**能用新密码登录**
  const loginAfterRegister = await loginRaw('/as/accounts/login', newPhone, newPassword)
  check('★★ 新账号能用刚设的密码登录（注册真的落库了）', loginAfterRegister.ok,
    `code=${loginAfterRegister.code} msg=${loginAfterRegister.msg ?? ''}`)

  // ④ 直查库确认（不看返回码）
  const newUserId = SQL_AVAILABLE ? sqlOne(`SELECT id FROM fm_user.user WHERE cell_phone='${newPhone}'`) : null
  checkSql('★★ 直查 MySQL：新账号在 fm_user.user 里', !!newUserId, `id=${newUserId}`)
  const detailRows = SQL_AVAILABLE
    ? Number(sqlOne(`SELECT COUNT(*) FROM fm_user.user_detail WHERE id=${newUserId ?? -1}`) ?? 0)
    : null
  checkSql('★ 直查 MySQL：user_detail 也建了（注册是一条两表写入）', detailRows === 1, `${detailRows} 行`)

  // ⑤ 负向：同一手机号再注册必须失败
  //   ★ 先关掉业务错误收集 —— 这个非 200 是**我刻意打出来的**
  collectErrors = false
  const dup = await anon.evaluate(
    async ([phone, pwd, code2]) => {
      const res = await fetch('http://localhost:10010/us/students/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cellPhone: phone, code: code2, password: pwd }),
      })
      let json = null
      try {
        json = await res.json()
      } catch {
        /* null */
      }
      return { status: res.status, code: json?.code, msg: json?.msg }
    },
    [newPhone, newPassword, '1234'],
  )
  /**
   * ⚠️ 这条负向的判据要宽松一点：重复注册可能因为**校验码错**（我传的 '1234' 不是真码）
   *   而失败，那样"失败"的原因就不是"手机号已占用"了 —— 变成假绿。
   *   所以断言的是「**没成功**」，并额外把 msg 打出来给人看是哪种失败。
   */
  check('★ 负向：重复注册同一手机号不会成功',
    dup.status !== 200 || dup.code !== 200, `HTTP ${dup.status} code=${dup.code} msg=${dup.msg ?? ''}`)
  collectErrors = true

  // ================================================================
  section('3. 忘记密码（PUT /us/students/password）')
  //   ★ 复用 §2 造的账号，所以清理必须放在本节之后
  // ================================================================
  /**
   * ★ 本节曾经整段被"网关没放行"卡住（#A16），现已修复。
   *
   *   修复前的实测（匿名、无 token）：
   *     POST /us/codes                → HTTP 200 ✅ 已放行
   *     POST /us/students/register    → HTTP 200 ✅ 已放行
   *     PUT  /us/students/password    → HTTP **401** ❌
   *     GET  /us/users/checkCellphone → HTTP **401** ❌
   *   根因：`fm.auth.excludePath` 只列了 login/refresh/codes/register，
   *   而 `requireLoginPath` 里的 `/us/**` 把这两个**登录前**接口一起兜进了登录墙：
   *     · 忘记密码 —— 用户正是因为登不进去才要重置
   *     · 注册页校验手机号 —— 注册时还没有账号
   *   后者尤其致命：注册页本就是免登录页，它一 401 就会被全局拦截器判成
   *   "登录态过期" → `forceLogin()` 整页跳走 → **注册页直接不可用**。
   *
   *   → 已在 `fm-gateway/src/main/resources/application.yml` 的 excludePath 补上这两条。
   *     下面第一条断言就是对**这次修复的回归**：必须"过网关"（HTTP 200），
   *     只剩业务错误（验证码错）—— 而不是 401。
   */
  const anonReset = await gw('PUT', '/us/students/password', {
    body: { cellPhone: newPhone, code: '0000', password: resetPassword },
  })
  check('★ #A16 回归：匿名能"过网关"调 PUT /us/students/password（不再 401）',
    anonReset.status === 200, `HTTP ${anonReset.status}（401 说明又没放行）`)
  check('★ 且拦住它的是业务校验而不是鉴权（假验证码 → 业务错误码）',
    anonReset.body?.code !== 200, `code=${anonReset.body?.code} msg=${anonReset.body?.msg ?? ''}`)
  const anonCheckPhone = await gw('GET', `/us/users/checkCellphone?cellphone=${newPhone}`)
  check('★ #A16 回归：匿名能调 GET /us/users/checkCellphone（注册页预校验的前提）',
    anonCheckPhone.status === 200, `HTTP ${anonCheckPhone.status}`)
  /**
   * ⚠️ 这个接口的返回值语义是「**可以注册**」= true（很容易读反）。
   *   此刻 `newPhone` 已经在 §2 注册过了 → 期望 false。
   * ★ 再拿一个肯定没注册的号验 true —— 两边都验，才能排除"恒返回某个值"的假绿。
   */
  check('★ 语义正确：已注册的号 → false（即"不可再注册"）',
    anonCheckPhone.body?.data === false, `phone=${newPhone} data=${anonCheckPhone.body?.data}`)
  const freePhone = `139${String(Date.now()).slice(-8)}`
  const anonCheckFree = await gw('GET', `/us/users/checkCellphone?cellphone=${freePhone}`)
  check('★ 反向：未注册的号 → true（证明它不是恒 false）',
    anonCheckFree.body?.data === true, `phone=${freePhone} data=${anonCheckFree.body?.data}`)

  await anon.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await waitStable(anon, '[data-testid="login-forgot"]')
  await anon.click('[data-testid="login-forgot"]')
  await anon.waitForSelector('[data-testid="forgot-phone"]', { timeout: 10000 })
  await anon.fill('[data-testid="forgot-phone"]', newPhone)
  await anon.click('[data-testid="forgot-send-code"]')
  let resetCode = ''
  for (let i = 0; i < 25; i++) {
    resetCode = await anon.inputValue('[data-testid="forgot-code"]')
    if (resetCode) break
    await anon.waitForTimeout(300)
  }
  check('忘记密码弹窗里验证码也能自动回填', /^\d{4,8}$/.test(resetCode), `code=${resetCode}`)
  await anon.fill('[data-testid="forgot-password"]', resetPassword)
  await anon.screenshot({ path: join(SHOT_DIR, 'm7-12a-02-forgot-filled.png') })
  await anon.click('[data-testid="forgot-submit"]')
  /**
   * ★ 弹窗关闭 = 提交成功。
   *   断言"弹窗消失"而不是"没报错"—— 后者也可能是静默失败。
   */
  let forgotClosed = true
  await anon
    .waitForSelector('[data-testid="forgot-phone"]', { state: 'hidden', timeout: 25000 })
    .catch(() => {
      forgotClosed = false
    })
  await anon.waitForTimeout(600)
  check('★★ 提交后弹窗自动关闭（改密成功的页面表现）', forgotClosed, `url=${anon.url()}`)
  check('★ 且页面停在登录页（没有整页跳走）', anon.url().includes('/login'), anon.url())
  await anon.screenshot({ path: join(SHOT_DIR, 'm7-12a-02b-forgot-done.png') })

  /**
   * ★★ 改密成功的判据必须**双向**，单看一边都可能假绿：
   *   只看"新密码能登" → 后端压根没改（新旧密码恰好相同）也会绿；
   *   只看"旧密码不能登" → 账号被锁/被删也会绿。
   *   ⚠️ 下面"旧密码不能再登"是**刻意打出来的失败**，但走的是 Node 侧直连
   *      （不经过页面），所以不会污染页面的业务错误收集。
   */
  const loginNew = await loginRaw('/as/accounts/login', newPhone, resetPassword)
  check('★★ 重置后：新密码能登录', loginNew.ok, `code=${loginNew.code}`)
  const loginOld = await loginRaw('/as/accounts/login', newPhone, newPassword)
  check('★★ 重置后：旧密码不能再登录（证明密码真的被改了，不是"没报错"）',
    !loginOld.ok, `code=${loginOld.code} msg=${loginOld.msg ?? ''}`)

  // ---- ★★ 清理：把本脚本造的账号删掉，并用**接口**自证删干净了 ----
  //   ★ 注意：忘记密码已成功，此刻生效的密码是 resetPassword（不是 newPassword）
  if (SQL_AVAILABLE) {
    sqlCells(`DELETE FROM fm_user.user_body_profile WHERE id=${newUserId ?? -1}`)
    sqlCells(`DELETE FROM fm_user.user_detail WHERE id=${newUserId ?? -1}`)
    sqlCells(`DELETE FROM fm_user.user WHERE id=${newUserId ?? -1}`)
    /**
     * ★★ 清理的判据用 `checkCellphone` 反查（**接口级**），而不是只看 DELETE 的退出码 ——
     *   "命令没报错"不等于"行真的没了"（同一个接口既能验"删干净了"，
     *   也顺带证明了"手机号重新可注册"这个业务事实）。
     */
    const usableAfterClean = await portalApi(portal, 'GET', `/us/users/checkCellphone?cellphone=${newPhone}`)
    check('★★ 清理：注册用例自己造的账号已删除（手机号重新可注册）',
      usableAfterClean.body?.data === true, `data=${usableAfterClean.body?.data}`)
    const afterUsers = Number(sqlOne(`SELECT COUNT(*) FROM fm_user.user`) ?? -1)
    check('★★ 清理：人数回到基线（#B7 同族，不重蹈覆辙）',
      afterUsers === baselineUsers, `${baselineUsers} → ${afterUsers}`)
  } else {
    /**
     * ⚠️⚠️ 这一段必须**大声**：清理靠 SQL，而本环境跑不了 SQL →
     *   注册用例造的账号会**留在库里**（正是 #B7 描述的污染形态）。
     *   所以这里不做"看起来干净"的假断言，而是把残留的手机号打出来，
     *   让人能手工删掉；并把它计入"被阻塞"清单。
     */
    const stillThere = await portalApi(portal, 'GET', `/us/users/checkCellphone?cellphone=${newPhone}`)
    // ★ checkCellphone 返回 true = 可注册 = 账号**已不在**；false = 不可注册 = 账号**仍在**
    const leaked = stillThere.body?.data === false
    checkBlocked(`注册用例的清理（残留手机号 ${newPhone}）`,
      `本环境无法执行 MySQL → DELETE 未执行；该账号${leaked ? '仍残留在库里' : '已不存在'}`)
    if (leaked) {
      console.log(`     ⚠️⚠️ 请在能跑 mysql 的终端里执行：`)
      console.log(`         DELETE FROM fm_user.user_body_profile WHERE id=(SELECT id FROM fm_user.user WHERE cell_phone='${newPhone}');`)
      console.log(`         DELETE FROM fm_user.user_detail      WHERE id=(SELECT id FROM fm_user.user WHERE cell_phone='${newPhone}');`)
      console.log(`         DELETE FROM fm_user.user            WHERE cell_phone='${newPhone}';`)
    }
  }

  // ================================================================
  section('4. 资料编辑 + 头像上传（PUT /us/users/me · POST /ms/files）')
  // ================================================================
  await portal.goto(`${PORTAL}/me`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await waitStable(portal, '[data-testid="me-profile"]')

  // 造一张真实的小 PNG（1x1）—— 必须是真图片，否则后端按图片类型处理会失败
  const pngPath = join(tmpdir(), `m7-12a-avatar-${Date.now()}.png`)
  writeFileSync(
    pngPath,
    Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AF/8kQFAAAAAElFTkSuQmCC',
      'base64',
    ),
  )

  const nickBefore = `M7-12 昵称 ${String(Date.now()).slice(-5)}`
  await portal.click('[data-testid="me-edit-open"]')
  await portal.waitForSelector('[data-testid="me-edit-name"]', { timeout: 10000 })
  await portal.fill('[data-testid="me-edit-name"]', nickBefore)
  // 隐藏的 <input type=file> 不能 click，但可以直接 setInputFiles
  await portal.setInputFiles('input[type="file"]', pngPath)
  // 上传是"申请→POST multipart→返回"，等"保存后生效"的提示或按钮恢复
  await portal.waitForTimeout(3000)
  await portal.screenshot({ path: join(SHOT_DIR, 'm7-12a-03-edit-dialog.png') })
  await portal.click('[data-testid="me-edit-save"]')
  await portal.waitForSelector('[data-testid="me-edit-name"]', { state: 'hidden', timeout: 20000 })
  await portal.waitForTimeout(1200)

  // ★★ 页面值 == 接口值
  const meApi = (await portalApi(portal, 'GET', '/us/users/me')).body?.data
  check('★★ 昵称：接口已更新', meApi?.name === nickBefore, `接口="${meApi?.name}"`)
  const shownName = flat(await portal.textContent('[data-testid="me-display-name"]'))
  check('★★ 页面显示的昵称 == 接口值（前端不做本地改写）', shownName === nickBefore,
    `页面="${shownName}" / 接口="${meApi?.name}"`)

  // ★ 头像：存的是 rawUrl（不带签名），且落在 avatar/ 前缀（匿名读白名单里）
  check('★★ 头像已写入 icon，且是 avatar/ 前缀（匿名可读白名单内）',
    typeof meApi?.icon === 'string' && meApi.icon.includes('/avatar/'),
    `${String(meApi?.icon ?? '').slice(0, 90)}`)
  /**
   * ★★ 断言"去掉签名参数也能访问" —— 这条专门防"存了带签名的 url"。
   *   签名 url 过一会儿会过期，图就变破图；而 rawUrl 才是能长期用的那个。
   *   从 Node 侧发请求（没有 CORS 限制），只看状态码。
   */
  if (meApi?.icon) {
    const bare = String(meApi.icon).split('?')[0]
    let bareStatus = 0
    try {
      const r = await fetch(bare)
      bareStatus = r.status
    } catch (e) {
      bareStatus = -1
      console.log(`     ⚠️ 匿名访问头像异常：${String(e?.message ?? e).slice(0, 80)}`)
    }
    check('★★ 头像 rawUrl 去掉签名后仍可匿名访问（证明存的不是带签名的临时地址）',
      bareStatus === 200, `HTTP ${bareStatus} ← ${bare.replace('http://localhost:9000', '')}`)
  }
  const avatarSrc = await portal.getAttribute('[data-testid="me-avatar"] img', 'src').catch(() => null)
  check('★ 页面头像 <img> 拿到了 src', !!avatarSrc && avatarSrc.length > 20,
    String(avatarSrc ?? '').slice(0, 70))

  // ================================================================
  section('5. 身体档案（GET/PUT /us/users/me/profile）')
  // ================================================================
  await portal.reload({ waitUntil: 'domcontentloaded' })
  await portal.waitForSelector('[data-testid="body-profile-edit"]', { timeout: 30000 })
  await portal.waitForTimeout(800)

  const hadProfile = (await portal.locator('[data-testid="body-profile"]').count()) > 0
  console.log(`     基线：该学员${hadProfile ? '已有' : '还没有'}身体档案`)

  await portal.click('[data-testid="body-profile-edit"]')
  await portal.waitForSelector('[data-testid="bp-form-height"]', { timeout: 10000 })
  const height = 182
  const weight = 64
  await portal.fill('[data-testid="bp-form-height"] input', String(height))
  await portal.fill('[data-testid="bp-form-weight"] input', String(weight))
  await portal.screenshot({ path: join(SHOT_DIR, 'm7-12a-04-body-profile-form.png') })
  await portal.click('[data-testid="bp-form-save"]')
  await portal.waitForSelector('[data-testid="bp-form-height"]', { state: 'hidden', timeout: 20000 })
  await portal.waitForTimeout(1200)

  // ★★ 页面 BMI == 接口 bmi，且 bmi 是后端算出来的
  const profApi = (await portalApi(portal, 'GET', '/us/users/me/profile')).body?.data
  check('★★ 保存后 GET 回来了身体档案', !!profApi, JSON.stringify(profApi ?? null).slice(0, 80))
  check('★★ BMI 由**后端**算出（接口返回非空）', profApi?.bmi != null, `bmi=${profApi?.bmi}`)
  check('★ 身高体重已落库', Number(profApi?.height) === height && Number(profApi?.weight) === weight,
    `height=${profApi?.height} weight=${profApi?.weight}`)
  const bmiShown = flat(await portal.textContent('[data-testid="bp-bmi"]'))
  check('★★ 页面 BMI == 接口 bmi（前端一个公式都不重算）',
    bmiShown === String(profApi?.bmi), `页面="${bmiShown}" / 接口="${profApi?.bmi}"`)
  const freqShown = flat(await portal.textContent('[data-testid="bp-freq"]'))
  check('★ 页面每周频次 == 接口 weeklyFrequency',
    freqShown.startsWith(String(profApi?.weeklyFrequency ?? '')), `页面="${freqShown}"`)
  await portal.screenshot({ path: join(SHOT_DIR, 'm7-12a-05-body-profile.png') })

  // 再存一次（更新语义：同一接口既能新增也能更新）
  await portal.click('[data-testid="body-profile-edit"]')
  await portal.waitForSelector('[data-testid="bp-form-weight"]', { timeout: 10000 })
  await portal.fill('[data-testid="bp-form-weight"] input', '65.5')
  await portal.click('[data-testid="bp-form-save"]')
  await portal.waitForSelector('[data-testid="bp-form-weight"]', { state: 'hidden', timeout: 20000 })
  await portal.waitForTimeout(1200)
  const profApi2 = (await portalApi(portal, 'GET', '/us/users/me/profile')).body?.data
  check('★ 二次保存是"更新"而不是"新增"（体重已改、表里仍只有这一份档案）',
    Number(profApi2?.weight) === 65.5, `weight=${profApi2?.weight}`)
  const profRows = Number(sqlOne(
    `SELECT COUNT(*) FROM fm_user.user_body_profile p JOIN fm_user.user u ON u.id=p.id WHERE u.cell_phone='${STUDENT_PHONE}'`) ?? -1)
  checkSql('★ 直查 MySQL：该学员的身体档案只有 1 行（没有重复插入）', profRows === 1, `${profRows} 行`)

  // ================================================================
  section('6. 我的优惠券 + 兑换码（POST /prs/user-coupons/exchange/{code}）')
  // ================================================================
  /**
   * ★★ 这一节要**两张券**才能测干净 —— 根因在 `exchangeCoupon` 的校验逻辑。
   *
   *   它要求 `obtain_way == 2`（兑换码方式），所以：
   *     · 兑换**成功**链路             → 需要一张 obtain_way=2 的券
   *     · 兑换**失败不能吃码**（#A17）  → 需要一张 obtain_way≠2 的券（校验必失败）
   *   后者是 #A17 的照妖镜：修复前是"先置位、后校验"，而置位写在 Redis、回滚的是 MySQL
   *   （Redis **不参与** MySQL 事务）→ 校验一失败，码就被**永久吃掉**：
   *   第二次兑换会从「该优惠券不支持兑换码领取」变成「兑换码已被使用」。
   *
   * ★ 为什么不用"券列表接口"拿 couponId：
   *   `CouponController` **只有 POST（建券）和 PUT /{id}/issue（发放）**，没有 GET 列表
   *   —— 这正是清单 `#C27` 记的那条「优惠券没有券列表查询接口」。
   *   所以券 id 只能由**演示数据**提供。
   *
   * ★★ 脚本坚持**不建券**：券没有删除接口，建了就是**永久垃圾**，
   *   比"少测一条"更糟（这是"脚本必须收拾自己的现场"的延伸）。
   *   缺券时显式标"前置不足"，绝不静默跳过。
   */
  const EXCHANGE_COUPON_ID = '999000001' // obtain_way=2：兑换码专用演示券（#B8 补入）
  const MANUAL_COUPON_ID = '2103354040328781826' // obtain_way=1：手动领取券，兑换必然校验失败

  const genCodes = async (couponId) => {
    const res = await gw('GET', `/prs/exchange-codes/generate/${couponId}`, { token: adminToken })
    return Array.isArray(res.body?.data) ? res.body.data : []
  }
  const exch = (code) => portalApi(portal, 'POST', `/prs/user-coupons/exchange/${code}`)

  const okCodes = await genCodes(EXCHANGE_COUPON_ID)
  check('★ 为「兑换码专用券」生成兑换码（前置由演示数据提供）', okCodes.length > 0,
    `${okCodes.length} 个，示例 ${okCodes[0] ?? '-'}`)

  if (!okCodes.length) {
    checkBlocked('兑换码兑换链路',
      `库里没有 id=${EXCHANGE_COUPON_ID} 的券（obtain_way=2）→ 见待修复清单 #B8`)
  } else {
    const studentId = SQL_AVAILABLE
      ? sqlOne(`SELECT id FROM fm_user.user WHERE cell_phone='${STUDENT_PHONE}'`)
      : null
    /**
     * ★★ 券数的"前/后"用**接口**取（`GET /prs/user-coupons`），不是 SQL。
     *   理由：接口级断言在任何环境都能跑，而 SQL 在受限环境会被跳过 ——
     *   让核心断言依赖一个"可能被跳过"的数据源，等于给假绿留口子。
     */
    const couponsApiBefore = (await portalApi(portal, 'GET', '/prs/user-coupons')).body?.data
    const couponsBefore = Array.isArray(couponsApiBefore) ? couponsApiBefore.length : -1

    // ① 学员在页面上兑换（成功链路）
    const exchangeCode = okCodes[0]
    await portal.goto(`${PORTAL}/my-coupons`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await waitStable(portal, '[data-testid="coupon-exchange-input"]')
    check('★ 兑换码入口在页面上（用户能自己输入）', true)
    await portal.fill('[data-testid="coupon-exchange-input"]', exchangeCode)
    await portal.click('[data-testid="coupon-exchange-submit"]')
    await portal.waitForTimeout(2500) // 等"券已到账"后的列表刷新
    await portal.screenshot({ path: join(SHOT_DIR, 'm7-12a-06-coupons.png') })

    const couponsApiAfter = (await portalApi(portal, 'GET', '/prs/user-coupons')).body?.data
    const couponsAfter = Array.isArray(couponsApiAfter) ? couponsApiAfter.length : -1
    check('★★ 页面上点一下，券真的到账（券数 +1）', couponsAfter === couponsBefore + 1,
      `${couponsBefore} → ${couponsAfter}`)
    if (SQL_AVAILABLE) {
      const sqlAfter = Number(sqlOne(`SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE user_id=${studentId}`) ?? -1)
      checkSql('★ 直查 MySQL：user_coupon 也 +1（与接口口径一致）',
        sqlAfter === couponsAfter, `sql=${sqlAfter} / api=${couponsAfter}`)
    }

    const cardCount = await portal.locator('[data-testid="coupon-card"]').count()
    check('★★ 页面券卡片数 == 接口返回条数',
      cardCount === couponsAfter, `页面 ${cardCount} / 接口 ${couponsAfter}`)

    // ② ★ 负向：同一个码再兑换一次必须失败
    //   ★★ 前置已断言"第一次成功"（券数 +1），所以这里的"失败"不会是
    //      "两次都失败"造成的假绿 —— 这正是本项目最警惕的那种假绿。
    //   ★ 这个 500 是**刻意打出来的**：`portalApi` 走的是 `page.evaluate` 里的 fetch，
    //     会被 page 的 response 监听捕获 → 必须先关掉业务错误收集。
    collectErrors = false
    const again = await exch(exchangeCode)
    collectErrors = true
    check('★★ 负向：同一兑换码第二次兑换必须失败（一次性语义）',
      again.body?.code !== 200, `code=${again.body?.code} msg=${again.body?.msg ?? ''}`)
    const couponsFinal = (await portalApi(portal, 'GET', '/prs/user-coupons')).body?.data
    const finalCount = Array.isArray(couponsFinal) ? couponsFinal.length : -1
    check('★ 第二次兑换没有多发出券', finalCount === couponsAfter, `${couponsAfter} → ${finalCount}`)

    // ③ ★★★ #A17 回归：**校验失败不能吃掉码**
    /**
     * 这是本次修复（把只读校验提到置位之前）的定点回归。
     * 判据**不是**"第一次失败" —— 而是**两次的错误必须一模一样**：
     *   · 修复后：两次都是「该优惠券不支持兑换码领取」（码还在，用户换个码还能用）
     *   · 修复前：第二次会变成「兑换码已被使用」（码已被 Redis bitmap 永久占掉）
     * 所以断言"第 2 次错误 == 第 1 次错误 且不含'已被使用'"，能精确抓住这个 bug。
     */
    const badCodes = await genCodes(MANUAL_COUPON_ID)
    if (!badCodes.length) {
      checkBlocked('#A17 回归（校验失败不吃码）',
        `没有 id=${MANUAL_COUPON_ID} 的券可用来生成"必失败"的码`)
    } else {
      collectErrors = false
      const bad1 = await exch(badCodes[0])
      const bad2 = await exch(badCodes[0])
      collectErrors = true
      const msg1 = String(bad1.body?.msg ?? '')
      const msg2 = String(bad2.body?.msg ?? '')
      check('★ 前置：这个码的兑换确实失败了（该券不是"兑换码方式"）',
        bad1.body?.code !== 200 && /不支持|兑换方式/.test(msg1), `msg="${msg1}"`)
      check('★★★ #A17 回归：同一个"校验失败"的码再兑换，错误**不变**（码没被吃掉）',
        msg2 === msg1 && !/已被使用/.test(msg2), `第1次="${msg1}" / 第2次="${msg2}"`)
    }
  }

  // ================================================================
  section('7. 退款详情 + 订单明细反查退款')
  // ================================================================
  const refundPage = (await portalApi(portal, 'GET', '/ts/refund-apply/page?pageNo=1&pageSize=10')).body?.data
  const refundItem = (refundPage?.list ?? [])[0]
  check('★ 该学员有退款申请可用（前置数据来自 seed/历次验收）', !!refundItem,
    `total=${refundPage?.total} id=${refundItem?.id}`)

  if (refundItem) {
    await portal.goto(`${PORTAL}/refunds`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await waitStable(portal, '[data-testid="refund-card"]')
    await portal.locator('[data-testid="refund-detail-open"]').first().click()
    await portal.waitForSelector('[data-testid="refund-detail"]', { timeout: 20000 })
    await portal.waitForTimeout(600)
    await portal.screenshot({ path: join(SHOT_DIR, 'm7-12a-07-refund-detail.png') })

    const detailApi = (await portalApi(portal, 'GET', `/ts/refund-apply/${refundItem.id}`)).body?.data
    const shownCourse = flat(await portal.textContent('[data-testid="detail-course-name"]'))
    const shownStatus = flat(await portal.textContent('[data-testid="detail-status"]'))
    check('★★ 详情抽屉的课程名 == 接口 courseName',
      shownCourse === String(detailApi?.courseName ?? ''), `页面="${shownCourse}"`)
    check('★★ 详情抽屉的状态 == 接口 statusDesc',
      shownStatus === String(detailApi?.statusDesc ?? ''), `页面="${shownStatus}" / 接口="${detailApi?.statusDesc}"`)
    check('★ 详情里有列表项没有的字段（审批/渠道/原支付单），说明它确实调了详情接口',
      detailApi?.payOrderNo !== undefined && detailApi?.refundChannel !== undefined,
      `payOrderNo=${detailApi?.payOrderNo} channel=${detailApi?.refundChannel}`)
    await portal.keyboard.press('Escape')
    await portal.waitForTimeout(600)

    // ★ 订单详情页：按明细反查退款（detail/{detailId}）
    await portal.goto(`${PORTAL}/orders/${refundItem.orderId}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await portal.waitForSelector('[data-testid="detail-course-row"]', { timeout: 30000 })
    await portal.waitForTimeout(1500)
    const refundRowCount = await portal.locator('[data-testid="detail-refund-row"]').count()
    check('★★ 订单详情页按明细反查到了退款申请（detail/{detailId} 真的被用上了）',
      refundRowCount > 0, `${refundRowCount} 行`)
    if (refundRowCount > 0) {
      const rowStatus = flat(await portal.locator('[data-testid="detail-refund-status"]').first().textContent())
      check('★ 订单详情里的退款状态 == 接口 statusDesc',
        rowStatus === String(detailApi?.statusDesc ?? ''), `页面="${rowStatus}" / 接口="${detailApi?.statusDesc}"`)
    }
    await portal.screenshot({ path: join(SHOT_DIR, 'm7-12a-08-order-refund-row.png') })
  }

  // ================================================================
  section('8. 支付单状态（GET /ps/pay-orders/{payOrderNo}）')
  // ================================================================
  /**
   * ★ 要看到 PayView 的"渠道侧支付单"区块，需要一个**待支付**的订单
   *   （已支付的订单不会去申请支付单，那条区块就不会渲染）。
   *   所以这里造一笔待支付订单：加购 → 下单（**不支付**）→ 进支付页。
   *   本节末会把它取消掉，不留垃圾数据。
   */
  /**
   * ★ 选一门"能下单"的付费课 —— 三个排除条件缺一不可（都是实测撞出来的）：
   *   ① `free === false && price > 0`：**免费课不能下单**，后端 `validateCourses` 明确拒绝
   *      （"免费课不该走下单，直接报名，否则会造出一堆 0 元订单"）
   *   ② **不在"我的课程"里**：已购课程再加购，后端返回
   *      `code 500`「《xxx》你已经买过了，无需重复加入」—— 第一版就挑中了已购的课
   *   ③ **不在购物车里**：重复加购虽然会忽略，但会导致后面的下单带着旧数据，不干净
   */
  const portalCoursePage = (await portalApi(portal, 'GET', '/ss/courses/portal?pageNo=1&pageSize=20')).body?.data
  /**
   * ★ 先把购物车清空（可重复执行的常规动作，M7-3 也是这么开头的）。
   *   不清的话：上一轮加购的课会留在车里 → 被下面的"排除"条件筛掉，
   *   而且如果它落在第 1 页，会连带把候选池压小。
   */
  await portalApi(portal, 'DELETE', '/ts/carts')
  const myLessons = (await portalApi(portal, 'GET', '/ls/lessons/page?pageNo=1&pageSize=50')).body?.data?.list ?? []
  const enrolledIds = new Set(myLessons.map((l) => String(l.courseId)))

  /**
   * ★ 候选池要**翻页找**：第 1 页 20 门课里有 10 门该学员已经买过，
   *   剩下能满足"未购、未加购"的付费课可能一门不剩（实测第 1 页挑不出来）。
   */
  const candidates = [...(portalCoursePage?.list ?? [])]
  for (let p = 2; p <= 3 && candidates.length; p++) {
    const more = (await portalApi(portal, 'GET', `/ss/courses/portal?pageNo=${p}&pageSize=20`)).body?.data?.list
    if (!more?.length) break
    candidates.push(...more)
  }
  const paidCourse = candidates.find(
    (c) => c.free === false && Number(c.price) > 0 && !enrolledIds.has(String(c.id)),
  )
  check('★ 找到一门"未购买"的付费课（免费课不能下单）', !!paidCourse,
    `courseId=${paidCourse?.id} price=${paidCourse?.price} 候选=${candidates.length} 已购=${enrolledIds.size}`)

  let pendingOrderId = null
  if (paidCourse) {
    const cart = await portalApi(portal, 'POST', '/ts/carts', { courseId: paidCourse.id })
    check('★ 加入购物车', cart.body?.code === 200, `code=${cart.body?.code} msg=${cart.body?.msg ?? ''}`)
    /**
     * ★ 下单是**两步**，不能一步到位：
     *   ① `GET  /ts/orders/prePlace` 先拿 `orderId`（后端用它做主键做**幂等**：
     *      同一个 id 重复提交只会返回已存在的订单，不会下两单）
     *   ② `POST /ts/orders` 带上这个 orderId 才提交
     *   → 第一版直接 POST 只有 `courseIds`，被后端判成 400（缺少 orderId）。
     *   数组参数必须自己 `join(',')`（axios 默认的 `courseIds[]=` 名字对不上）。
     */
    const pre = await portalApi(portal, 'GET', `/ts/orders/prePlace?courseIds=${paidCourse.id}`)
    const preOrderId = pre.body?.data?.orderId
    check('★ 下单预览拿到 orderId（幂等主键）', !!preOrderId, `orderId=${preOrderId}`)

    if (preOrderId) {
      const placed = await portalApi(portal, 'POST', '/ts/orders', {
        orderId: preOrderId,
        courseIds: [paidCourse.id],
        userCouponIds: [],
      })
      pendingOrderId = placed.body?.data?.orderId ?? null
      check('★ 下单成功（此时是待支付）', !!pendingOrderId,
        `code=${placed.body?.code} orderId=${pendingOrderId} msg=${placed.body?.msg ?? ''}`)
    }

    if (pendingOrderId) {
      await portal.goto(`${PORTAL}/pay/${pendingOrderId}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
      await portal.waitForSelector('[data-testid="pay-order-status"]', { timeout: 30000 })
      await portal.waitForTimeout(800)
      const payOrderNo = flat(await portal.textContent('[data-testid="pay-order-no"]'))
      const tagShown = flat(await portal.textContent('[data-testid="pay-order-status-tag"]'))
      const payOrderApi = (await portalApi(portal, 'GET', `/ps/pay-orders/${payOrderNo}`)).body?.data
      check('★★ 支付单状态区块拿到支付单号', /^PAY\d+/.test(payOrderNo), payOrderNo)
      check('★★ 接口确实返回了这张支付单（status=1 未支付）',
        payOrderApi?.status === 1, `status=${payOrderApi?.status} amount=${payOrderApi?.amount}`)
      check('★★ 页面显示的渠道侧状态 == 接口 status（未支付）',
        tagShown.includes('未支付'), `页面="${tagShown}"`)
      check('★ 支付单金额 == 订单实付金额',
        Number(payOrderApi?.amount) === Number(paidCourse.price),
        `pay=${payOrderApi?.amount} / course=${paidCourse.price}`)
      await portal.screenshot({ path: join(SHOT_DIR, 'm7-12a-09-pay-order-status.png') })

      // 清理：取消这笔待支付订单
      const cancelled = await portalApi(portal, 'PUT', `/ts/orders/${pendingOrderId}/cancel`)
      check('★ 清理：待支付订单已取消', cancelled.body?.code === 200, `code=${cancelled.body?.code}`)
    }
  }

  // ================================================================
  section('9. 权限边界（★ 学员不能干运营的活）')
  // ★ 接下来的 403 是**刻意**打出来的
  collectErrors = false
  // ================================================================
  // ★ 靶子取 §6 那张**真实存在的券**（兑换码专用演示券）—— 它既存在、又是运营专属操作的对象
  const r1 = await portalApi(portal, 'GET', `/prs/exchange-codes/generate/${EXCHANGE_COUPON_ID}`)
  check('★★ 学员想给自己生成兑换码 → 403（兑换码生成是运营专属）',
    r1.status === 403, `HTTP ${r1.status}`)
  /**
   * ★ 靶子要选"真的存在的管理端写操作"。
   *   第一版我打的是 `GET /prs/coupons`（想验"拉券列表"）—— 但那个接口
   *   **根本不存在**（`CouponController` 只有 POST 与 PUT issue，见 #C27），
   *   所以它返回 200 是"路由没匹配上"的产物，**证明不了任何权限边界**。
   *   → 换成真正存在的管理端操作：给券"开始发放"。
   */
  const r2 = await portalApi(portal, 'PUT', `/prs/coupons/${EXCHANGE_COUPON_ID}/issue`)
  check('★★ 学员想替运营"开始发放"券 → 403', r2.status === 403,
    `HTTP ${r2.status} code=${r2.body?.code}`)
  // ★ 反向对照：`/files/**` 是**通用文件服务**，学员必须能用（否则头像传不了）
  const r3 = await portalApi(portal, 'GET', '/ms/files/1')
  check('★ 反向对照：学员能访问通用文件服务（/files 刻意不圈管理员 —— 头像要用）',
    r3.status === 200 || r3.body?.code === 200, `HTTP ${r3.status} code=${r3.body?.code}`)
  collectErrors = true

  // ================================================================
  section('10. 浏览器运行时健康度')
  // ================================================================
  check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '))
  check('★ 全程没有「业务码非 200」的响应（这类失败只弹红条，不留日志）',
    businessErrors.length === 0, businessErrors.slice(0, 3).join(' | '))

  await browser.close()

  console.log('')
  console.log('='.repeat(74))
  /**
   * ★ 汇总行格式必须与**前端口径**一致（`N 通过 / M 失败`，数字在「通过」之前）——
   *   `accept-web-all.mjs` 用 /(\d+)\s*通过\s*\/\s*(\d+)\s*失败/ 解析它。
   *   ⚠️ 写成后端格式（`验收结果：通过 N 项…`）会让 runner 解析不到，
   *      把"全绿"误报成"异常终止"（#C32 同族，已有先例）。
   *   所以"未验证"只**追加**在后面，绝不插进这两个数字之间。
   */
  console.log(`  结果：${pass} 通过 / ${fail} 失败`)
  if (skipped) console.log(`  另有 ${skipped} 条「直查数据库」断言因本环境无法执行 MySQL 而未验证`)
  if (blocked) console.log(`  另有 ${blocked} 项因**前置数据/环境限制**无法验收（见下）`)
  if (failures.length) console.log('  失败项：\n    ' + failures.join('\n    '))
  if (blockedNotes.length) console.log('  🚧 受阻项（不是失败，但也绝不是通过）：\n    ' + blockedNotes.join('\n    '))
  if (skipNotes.length) console.log('  未验证项：\n    ' + skipNotes.join('\n    '))
  console.log('  截图：' + SHOT_DIR)
  console.log('='.repeat(74))
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('验收脚本异常：', e)
  process.exit(1)
})
