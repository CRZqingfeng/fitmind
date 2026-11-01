/**
 * ============================================================================
 * M8-2c 前端验收：跨端联动（一条退款申请穿「学员端 ↔ 管理端」）
 * ----------------------------------------------------------------------------
 * 运行：cd frontend && npm run accept:m8-2c
 *
 * ★★ 这条用例补的是什么：
 *
 *   m7-3 验的是**学员侧**退款（申请 → 列表）；
 *   m7-8 验的是**管理端**退款页（列表 == 接口 / 详情金额 == 接口）——
 *   但它只是"页面渲染对不对"，**没有真的点过审批按钮**。
 *   → 于是「管理端的动作，学员端能不能看见」**从来没有人验过**。
 *
 *   本脚本用**同一个浏览器、同一个 Context、两个 Page**（复用 M8-2a 建立的
 *   多身份能力），把一条退款申请用**同一个 refund_apply.id** 串起来：
 *
 *     ① 学员（portal，全 UI）：买课 → 支付 → 申请退款
 *     ② 管理端（admin，UI）：列表里**看到同一条** → 点「同意」
 *     ③ （脚本模拟**渠道异步回调** —— 这是"银行"这一侧，不属于我们的 UI）
 *     ④ 学员（portal，UI）：状态变「退款成功」+ 该课从「我的课程」消失 + 收到站内信
 *
 * ★ 为什么 ③ 由脚本做：真实世界里退款是**渠道异步回调**驱动的
 *   （管理端页面自己也在 `waitForFinal` 等它）。Mock 渠道用
 *   `POST /ps/mock/refund/{退款单号}` 代替"银行来电话"。
 *
 * ★★ 两个必须绕开的坑（都踩过）：
 *   · 验证码**每手机号 60 秒限流** → 学员的买课 / 申请退款**必须走 UI**，
 *     否则"先 API 登录、再 UI 登录"会在 60 秒内取两次码 → 第二次被拒。
 *   · 雪花 id **绝不能过 `Number()`**（超过 2^53 静默丢精度，见清单 #C33）。
 *
 * 前置条件：15 服务 + 网关 + dev server 5173 / 5174 已启动
 * ============================================================================
 */

import { chromium } from 'playwright'
import { spawnSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots')

const PORTAL = 'http://localhost:5173'
const ADMIN = 'http://localhost:5174'
const GATEWAY = 'http://localhost:10010'

const STUDENT_PHONE = '13800010001'
const ADMIN_PHONE = '13800010003'

const TOKEN_KEY_PORTAL = 'fitmind:token'
const TOKEN_KEY_ADMIN = 'fitmind:admin:token'

const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'

/** 退款状态（从服务端枚举抄，不猜）：见 RefundStatus.java */
const REFUND_SUCCESS = 5

let passed = 0
let failed = 0
const failures = []

// ============================================================
// 断言 / 输出
// ============================================================

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

async function summary() {
  console.log('\n============================================================')
  // ★ 格式必须与**前端口径**一致（`N 通过 / M 失败`，数字在「通过」**之前**）——
  //   `accept-web-all.mjs` 用 /(\d+)\s*通过\s*\/\s*(\d+)\s*失败/ 解析这一行。
  //   ⚠️ 第一版我写成了**后端格式**（`验收结果：通过 N 项，失败 M 项`）→
  //     runner 解析不到 → 把「25 通过 / 0 失败」误报成**「异常终止」**（#C32 同族，方向相反）。
  console.log(`  ${passed} 通过 / ${failed} 失败`)
  if (failures.length) {
    console.log('  失败明细：')
    for (const f of failures) console.log(`    ❌ ${f}`)
  }
  console.log('============================================================')
}

// ============================================================
// 基础设施
// ============================================================

function sql(query) {
  const r = spawnSync(
    MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  if (r.status !== 0) throw new Error(`SQL 失败：${query}\n${(r.stderr || '').trim()}`)
  return (r.stdout || '').trim()
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitFor(fn, { timeoutMs = 25000, intervalMs = 800, label = '' } = {}) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await fn()
    if (last) return last
    await sleep(intervalMs)
  }
  if (label) console.log(`     ⏱ 等待超时：${label}`)
  return last
}

async function requireReachable(url, name) {
  try {
    await fetch(url, { method: 'GET', signal: AbortSignal.timeout(8000) })
  } catch {
    console.error(`\n❌ 前置条件不满足：${name} 不可达（${url}）`)
    console.error('   前端：cd frontend && npm run dev:portal 与 npm run dev:admin')
    process.exit(2)
  }
}

// ★ 浏览器连接统一见 ./_cdp-browser.mjs —— 本机必须走 CDP，
//   `chromium.launch({ channel: 'msedge' })` 在这里一定失败（见模块头部说明）。

/**
 * 收集浏览器侧错误 + 网络侧记账（沿用 m8-2a / m7-1 的做法）
 *
 * ★★ 业务码非 200 **不会**产生 pageerror 也不会产生 console error ——
 *    所以必须单独记账并在最后断言"非预期的业务码为 0"。
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
    const ct = res.headers()['content-type'] ?? ''
    if (!ct.includes('json')) return
    try {
      const body = await res.json()
      const code = body?.code
      if (typeof code === 'number' && code !== 200) {
        const path = url.replace(GATEWAY, '')
        // 退款相关接口在申请前会正常返回"不可退"这类业务码，单独归类
        const expected = path.includes('/refund-apply') && res.status() < 500
        net.badCodes.push({ code, status: res.status(), path, expected })
      }
    } catch {
      /* 非 JSON / 已被消费 */
    }
  })

  return state
}

/** 用页面里的 token 直接打网关（读不到接口返回值时才用；能点 UI 就点 UI） */
async function apiCall(page, method, path, body, tokenKey) {
  return page.evaluate(
    async ({ gateway, method, path, body, tokenKey }) => {
      const token = localStorage.getItem(tokenKey)
      const res = await fetch(gateway + path, {
        method,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: token } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      })
      const text = await res.text()
      let json = null
      try {
        json = JSON.parse(text)
      } catch {
        /* 非 JSON */
      }
      return { status: res.status, json, text }
    },
    { gateway: GATEWAY, method, path, body, tokenKey },
  )
}

/** 验证码登录（Mock 短信会自动回填验证码） */
async function loginWithCode(page, phone) {
  await page.fill('input[data-testid="login-phone"]', phone)
  await page.click('[data-testid="login-send-code"]')
  await page.waitForFunction(
    () => {
      const el = document.querySelector('input[data-testid="login-credential"]')
      return !!el && el.value.length > 0
    },
    { timeout: 20000 },
  )
  await page.click('[data-testid="login-submit"]')
}

// ============================================================
// 清理（★ 抽成函数：任何一站提前收场都要走它）
// ============================================================

/**
 * 删掉本次造的数据。
 *
 * ★★ 明细与订单必须**同一维度**删（先按 order_id 删明细、再按 id 删订单）——
 *    这正是 M8-2c §1① 抓到的那 13 条孤儿明细的成因（见清单 #C34①）。
 */
function cleanup({ studentId, orderId, applyId, courseId }) {
  if (applyId) {
    sql(`DELETE FROM fm_message.user_inbox WHERE source_id='${applyId}'`)
    sql(`DELETE FROM fm_pay.refund_order WHERE biz_refund_order_no='${applyId}'`)
    sql(`DELETE FROM fm_trade.refund_apply WHERE id=${applyId}`)
  }
  if (orderId) {
    sql(`DELETE FROM fm_pay.pay_order WHERE biz_order_no=${orderId}`)
    sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`)
    sql(`DELETE FROM fm_trade.trade_order WHERE id=${orderId}`)
  }
  if (studentId && courseId) {
    // 退款正常会把课表撤掉；这里只作兜底（比如中途失败没走到那一步）
    sql(`DELETE FROM fm_learning.learning_lesson WHERE user_id=${studentId} AND course_id=${courseId}`)
  }
}

// ============================================================

async function main() {
  console.log('============================================================')
  console.log(' M8-2c 前端验收：跨端联动（一条退款申请穿学员端 ↔ 管理端）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(ADMIN, '管理端 dev server')
  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  // ★★ 全部断言都建立在这一行上：**一个 Context 里同时当学员和管理员**
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const net = { badCodes: [] }

  const student = await ctx.newPage()
  const studentState = attachCollectors(student, net)
  const admin = await ctx.newPage()
  const adminState = attachCollectors(admin, net)

  try {
    // ================================================================
    section('1. 学员端（UI）：登录 → 买课 → 支付')
    // ================================================================
    await student.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await student.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(student, STUDENT_PHONE)
    await student.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 25000 })

    const me = await apiCall(student, 'GET', '/us/users/me', null, TOKEN_KEY_PORTAL)
    const studentId = me.json?.data?.id
    check('★ 学员登录成功并取到 userId', !!studentId, `userId=${studentId}`)

    // ★ 找一门"该学员没买过、且没有进行中订单"的付费课
    //   （否则下单会被"已有待支付订单/已购买"拦掉 —— m7-3 踩过）
    const courseId = sql(
      `SELECT c.id FROM fm_course.course c
        WHERE c.status=2 AND c.price > 0
          AND NOT EXISTS (SELECT 1 FROM fm_learning.learning_lesson l
                           WHERE l.course_id=c.id AND l.user_id=${studentId})
          AND NOT EXISTS (SELECT 1 FROM fm_trade.trade_order_detail d
                           WHERE d.course_id=c.id AND d.user_id=${studentId} AND d.status IN (1,2,4))
        ORDER BY c.id DESC LIMIT 1`,
    )
    const courseName = sql(`SELECT name FROM fm_course.course WHERE id=${courseId}`)
    check('★ 找到一门可下单的付费课', !!courseId, `courseId=${courseId} ${courseName}`)
    if (!courseId) {
      await summary()
      return
    }
    console.log(`     课程：${courseId} ${courseName}`)

    // 加购 → 结算 → 支付（照抄 m7-3 已验证的路径）
    await student.goto(`${PORTAL}/course/${courseId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await student.waitForSelector('[data-testid="course-buy"]', { timeout: 20000 })
    await student.click('[data-testid="course-buy"]')
    await sleep(1200)

    await student.goto(`${PORTAL}/cart`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await student.waitForSelector('[data-testid="cart-row"]', { timeout: 20000 })
    await student.click('[data-testid="cart-checkout"]')
    await student.waitForSelector('[data-testid="checkout-submit"]', { timeout: 20000 })
    await student.click('[data-testid="checkout-submit"]')

    await student.waitForSelector('[data-testid="pay-amount"]', { timeout: 20000 })
    const orderId = student.url().split('/pay/')[1]?.split('?')[0]
    check('★ 下单成功并进入支付页（URL 带订单号）', !!orderId, `orderId=${orderId}`)
    if (!orderId) {
      await summary()
      return
    }

    await student.click('[data-testid="pay-mock-submit"]')
    await student.waitForSelector('[data-testid="pay-success"]', { timeout: 40000 })
    check('★ 支付成功（走真实回调链路）', true)

    // ★ 支付成功的异步后果之一：课表被创建（MQ: order.pay → fm-learning）
    const lessonOk = await waitFor(
      () => sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${studentId} AND course_id=${courseId}`) === '1',
      { timeoutMs: 25000, label: '课表创建（MQ 异步）' },
    )
    check('★ 直查 DB：课表已创建（支付 → MQ → 写课表）', lessonOk)
    await student.screenshot({ path: join(SHOT_DIR, 'm8-2c-01-paid.png') })

    // ================================================================
    section('2. 学员端（UI）：申请退款')
    // ================================================================
    await student.goto(`${PORTAL}/orders/${orderId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await student.waitForSelector('[data-testid="detail-refund-btn"]', { timeout: 20000 })
    await student.click('[data-testid="detail-refund-btn"]')
    await student.waitForSelector('[data-testid="refund-reason"]', { timeout: 15000 })
    // el-select：必须先点开再选（click 是 toggle，所以判据看 aria-expanded）
    await student.click('[data-testid="refund-reason"]')
    await sleep(500)
    await student.locator('.el-select-dropdown__item').first().click()
    await sleep(300)
    await student.click('[data-testid="refund-submit"]')

    const applyId = await waitFor(
      () => {
        const v = sql(`SELECT id FROM fm_trade.refund_apply WHERE order_id=${orderId} ORDER BY id DESC LIMIT 1`)
        return v || null
      },
      { timeoutMs: 20000, label: '退款申请落库' },
    )
    check('★ 退款申请已创建（直查 DB，不看返回码）', !!applyId, `refundApplyId=${applyId}`)
    check(
      '★ 申请状态 = 待审批(1)',
      sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${applyId}`) === '1',
      `status=${sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${applyId}`)}`,
    )
    await student.screenshot({ path: join(SHOT_DIR, 'm8-2c-02-refund-applied.png') })

    // ================================================================
    section('3. 管理端（UI）：看到同一条 → 点「同意」')
    // ================================================================
    await admin.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(admin, ADMIN_PHONE)
    await admin.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 25000 })
    await admin.waitForSelector('[data-testid="admin-user-name"]', { timeout: 20000 })
    check('★ 管理端登录成功（与学员端**同一个浏览器 Context**）', true)

    await admin.goto(`${ADMIN}/refunds`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await admin.waitForSelector('[data-testid="refund-table"]', { timeout: 20000 })
    // 用订单 id 过滤 → 列表里应当**恰好**是我们刚提交的那一条
    await admin.fill('[data-testid="refund-filter-order"]', String(orderId))
    await admin.click('button:has-text("查询")')
    await sleep(1500)

    const rows = admin.locator('[data-testid="refund-table"] tbody tr')
    const rowCount = await rows.count()
    const rowText = rowCount ? (await rows.first().textContent()) ?? '' : ''
    check(
      '★★ 管理端**看到了**学员刚提交的那一条（同一订单号，跨端可见）',
      rowCount === 1 && rowText.includes(courseName),
      `行数=${rowCount} 行内容=「${rowText.trim().slice(0, 80)}」`,
    )
    check(
      '★ 该行状态显示「待审批」',
      rowText.includes('待审批'),
      `行内容=「${rowText.trim().slice(0, 80)}」`,
    )
    await admin.screenshot({ path: join(SHOT_DIR, 'm8-2c-03-admin-sees-it.png') })

    // ---- 点「同意」→ 弹窗填审批意见 → 确认 ----
    await rows.first().locator('[data-testid="refund-approve"]').click()
    await admin.waitForSelector('.el-message-box', { timeout: 15000 })
    const boxText = (await admin.locator('.el-message-box').textContent()) ?? ''
    check(
      '★ 审批弹窗提示了"同意后会调用渠道发起退款（异步）"',
      boxText.includes('异步') || boxText.includes('渠道'),
      `弹窗=「${boxText.trim().slice(0, 70)}」`,
    )
    await admin.locator('.el-message-box input').first().fill('M8-2c 联调验收：同意')
    await admin.locator('.el-message-box__btns button', { hasText: '确认同意' }).click()

    // ================================================================
    section('4. 渠道异步回调（脚本扮演"银行"）→ 管理端页面等到终态')
    // ================================================================
    //  ★ 管理端页面点完同意后会自己 `waitForFinal`（最多 30 秒）等终态。
    //    真实世界是渠道回调驱动；这里由脚本调用 Mock 渠道回调来扮演它。
    const refundOrderNo = await waitFor(
      () =>
        sql(`SELECT refund_order_no FROM fm_pay.refund_order WHERE biz_refund_order_no='${applyId}' LIMIT 1`) || null,
      { timeoutMs: 25000, label: '支付侧生成退款单' },
    )
    check('★ 同意后支付侧生成了退款单（trade → pay 同步发起）', !!refundOrderNo, `退款单号=${refundOrderNo}`)

    const cb = await apiCall(admin, 'POST', `/ps/mock/refund/${refundOrderNo}`, null, TOKEN_KEY_ADMIN)
    check('★ 模拟渠道退款成功回调', cb.status === 200, `HTTP ${cb.status} body=${cb.text.slice(0, 60)}`)

    // 管理端页面应当自己等到「退款成功」
    const uiFinal = await waitFor(
      async () => {
        const txt = await admin.locator('.el-message--success').textContent().catch(() => null)
        return txt && txt.includes('退款成功') ? txt : null
      },
      { timeoutMs: 30000, label: '管理端页面出现"退款成功"提示' },
    )
    check(
      '★★ 管理端页面**自己**等到了终态（提示"退款成功"）',
      !!uiFinal,
      `提示=「${(uiFinal ?? '').trim().slice(0, 60)}」`,
    )
    check(
      '★ 直查 DB：refund_apply 状态 = 退款成功(5)',
      sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${applyId}`) === String(REFUND_SUCCESS),
      `status=${sql(`SELECT status FROM fm_trade.refund_apply WHERE id=${applyId}`)}`,
    )
    await admin.screenshot({ path: join(SHOT_DIR, 'm8-2c-04-admin-approved.png') })

    // ================================================================
    section('5. 学员端（UI）：看到退款结果 + 课表消失 + 站内信')
    // ================================================================
    // ---- 5.1 退款列表里状态变了 ----
    await student.goto(`${PORTAL}/refunds`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await student.waitForSelector('[data-testid="refund-card"]', { timeout: 20000 })
    const cardText = (await student.locator('[data-testid="refund-card"]').first().textContent()) ?? ''
    check(
      '★★ 学员端退款列表里**看到了**管理端审批的结果（状态 = 退款成功）',
      cardText.includes('退款成功'),
      `首张卡片=「${cardText.trim().slice(0, 90)}」`,
    )
    await student.screenshot({ path: join(SHOT_DIR, 'm8-2c-05-student-refund-done.png'), fullPage: true })

    // ---- 5.2 该课从「我的课程」消失（MQ: order.refund → fm-learning 撤课表） ----
    const lessonGone = await waitFor(
      () => sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${studentId} AND course_id=${courseId}`) === '0',
      { timeoutMs: 25000, label: '课表撤销（MQ 异步）' },
    )
    check('★★ 直查 DB：课表已撤销（退款 → MQ → 撤课表）', lessonGone)

    await student.goto(`${PORTAL}/my-courses`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await student.waitForSelector('[data-testid="mycourse-total"]', { timeout: 20000 })
    await sleep(800)
    const myCourseText = (await student.locator('body').textContent()) ?? ''
    check(
      '★★ 学员端「我的课程」里该课**已消失**',
      !myCourseText.includes(courseName),
      `页面${myCourseText.includes(courseName) ? '仍包含' : '已不含'}「${courseName}」`,
    )
    await student.screenshot({ path: join(SHOT_DIR, 'm8-2c-06-student-no-course.png'), fullPage: true })

    // ---- 5.3 收到「退款到账」站内信（MQ: order.refund → fm-message） ----
    const inboxOk = await waitFor(
      () => sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=${studentId} AND source_id='${applyId}'`) !== '0',
      { timeoutMs: 25000, label: '退款到账站内信' },
    )
    check('★★ 直查 DB：收到了「退款到账」站内信', inboxOk)
    const inboxTitle = sql(
      `SELECT title FROM fm_message.user_inbox WHERE user_id=${studentId} AND source_id='${applyId}' LIMIT 1`,
    )

    await student.goto(`${PORTAL}/messages`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await student.waitForSelector('[data-testid="message-total"]', { timeout: 20000 })
    await sleep(800)
    const msgText = (await student.locator('body').textContent()) ?? ''
    check(
      '★★ 学员端站内信页面能看到它',
      msgText.includes(inboxTitle) || msgText.includes('退款'),
      `信标题=「${inboxTitle}」`,
    )
    await student.screenshot({ path: join(SHOT_DIR, 'm8-2c-07-student-inbox.png'), fullPage: true })

    // ================================================================
    section('6. 收尾：清理本次造的数据 + 自检')
    // ================================================================
    //  ★ 为什么必须清理：本脚本用的是**演示学员**（UI 登录必须用真手机号取码，
    //    造不了临时账号），所以每跑一轮就会留下一笔"已完成退款的订单"。
    //    不收干净 → 跑 N 轮留 N 套（与 #B7「accept-m1 不清理账号」同一族）。
    //  ★ 明细与订单必须**同一维度**删（先明细后订单）—— #C34① 的教训。
    cleanup({ studentId, orderId, applyId, courseId })

    check(
      '★★ 清理自检：订单 / 退款单 / 站内信都已删净',
      sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE id=${orderId}`) === '0' &&
        sql(`SELECT COUNT(*) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`) === '0' &&
        sql(`SELECT COUNT(*) FROM fm_trade.refund_apply WHERE id=${applyId}`) === '0' &&
        sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE source_id='${applyId}'`) === '0',
      `order=${sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE id=${orderId}`)}` +
        ` detail=${sql(`SELECT COUNT(*) FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`)}` +
        ` refund=${sql(`SELECT COUNT(*) FROM fm_trade.refund_apply WHERE id=${applyId}`)}` +
        ` inbox=${sql(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE source_id='${applyId}'`)}`,
    )
    // ★ 顺带守住 §1①（那条孤儿明细不变量）：清理完不许留下新的孤儿
    check(
      '★★ 清理后全场无孤儿订单明细（与后端脚本 §1① 同一口径）',
      sql(
        `SELECT COUNT(*) FROM fm_trade.trade_order_detail d
          WHERE NOT EXISTS (SELECT 1 FROM fm_trade.trade_order o WHERE o.id = d.order_id)`,
      ) === '0',
      '有孤儿明细 → 说明上面用了两个维度删',
    )

    // ================================================================
    section('7. 浏览器运行时健康度（★ 业务码非 200 不会产生 pageerror，必须单独断言）')
    // ================================================================
    const unexpectedBiz = net.badCodes.filter((b) => !b.expected)
    check(
      '★ 两端的 pageerror 均为 0',
      studentState.pageErrors.length === 0 && adminState.pageErrors.length === 0,
      `学员端=${studentState.pageErrors.length} 管理端=${adminState.pageErrors.length}` +
        (studentState.pageErrors[0] ? ` 例：${studentState.pageErrors[0].slice(0, 80)}` : ''),
    )
    check(
      '★ 两端的 console error 均为 0',
      studentState.consoleErrors.length === 0 && adminState.consoleErrors.length === 0,
      `学员端=${studentState.consoleErrors.length} 管理端=${adminState.consoleErrors.length}` +
        (studentState.consoleErrors[0] ? ` 例：${studentState.consoleErrors[0].slice(0, 80)}` : ''),
    )
    check(
      '★★ 非预期的业务码响应数为 0（这一条才真正防住"验收全绿但用户看到红条"）',
      unexpectedBiz.length === 0,
      unexpectedBiz.map((b) => `${b.code} ${b.path}`).join(' | ') || '无',
    )
  } finally {
    await browser.close()
  }

  await summary()
  process.exitCode = failed === 0 ? 0 : 1
}

main().catch(async (e) => {
  console.error('\n❌ 脚本异常终止：', e)
  process.exitCode = 1
})
