#!/usr/bin/env node
/**
 * ============================================================
 * M6-8 第 1 期前端验收：交易闭环 + 防假成功
 * ------------------------------------------------------------
 * 覆盖：M6-8 #2（支付页把"查不到订单"显示成"支付成功"）
 *
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-auth / fm-user / fm-course / fm-trade(8088) / fm-pay(8089)
 *   · 前端：fitmind-portal dev server (5173)
 *   · 数据：学员 13800010001（id=1）**未购买**课程 541（哑铃手臂线条塑造）
 *
 * 用法：cd frontend && node scripts/accept-web-m6-8.mjs
 *
 * ★★ 为什么这条 bug 值得单独写一个脚本（而不是并进 m7-3）：
 *
 *   m7-3 跑的是**正确路径**（有订单 → 收银台 → 支付成功），它永远绿。
 *   而 #2 是**错误路径**上的假成功 —— 只有在"订单根本不存在"时才会发生。
 *   换句话说：**一条只会走正确路径的脚本，永远发现不了"失败被当成成功"**。
 *   所以这里必须专门构造"订单不存在"这个输入。
 *
 * ★★ 判定"修好了"的标准不只是"不再显示成功"，还包括**正常路径没被顺手搞坏**：
 *   如果有人为了修 #2 把 `settled` 直接删了，B 组会立刻红。
 *   （修 bug 最怕的是"把错误分支修没了，也把正确分支修没了"。）
 * ============================================================
 */

import { execFileSync, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { closeBrowser, launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots')

const PORTAL = 'http://localhost:5173'
const GATEWAY = 'http://localhost:10010'
const STUDENT_PHONE = '13800010001'
const STUDENT_PASSWORD = '123456'
const STUDENT_USER_ID = 1
const TOKEN_KEY = 'fitmind:token'

/** ★ 演示账号**未购买**的付费课（541 哑铃手臂线条塑造 / 129 元） */
const COURSE_ID = 541
const COURSE_PRICE = 12900

/** 一个**一定不存在**的订单号（雪花 id 的形状，但库里没有） */
const FAKE_ORDER_ID = '9999999999999999999'

let passed = 0
let failed = 0
const failures = []

function check(name, condition, detail = '') {
  if (condition) {
    passed++
    console.log(`  ✅ ${name}${detail ? `（${detail}）` : ''}`)
  } else {
    failed++
    failures.push(name + (detail ? ` —— ${detail}` : ''))
    console.log(`  ❌ ${name}${detail ? ` —— ${detail}` : ''}`)
  }
}

function section(title) {
  console.log(`\n【${title}】`)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 与前端 utils/format.ts 的 formatPrice 保持同一套输出格式（断言要逐字比对） */
function formatFen(fen) {
  if (fen === null || fen === undefined) return '—'
  if (fen === 0) return '免费'
  return `¥${(fen / 100).toFixed(2)}`
}

/**
 * 查数据库。
 *
 * ⚠️ 两件事都踩过：① Windows 上要写 `mysql.exe`（execFileSync 不做 PATHEXT 补全）；
 *   ② `stdio` 的 **stdin 必须是 ignore** —— 默认的 pipe 在受限环境里会 EBUSY，
 *   而失败形态是 `stdout: undefined`（不是空串），"期望为空"的断言会静默假绿。
 */
function sql(query, { tolerant = false } = {}) {
  try {
    return execFileSync(
      'mysql.exe',
      ['-h127.0.0.1', '-uroot', '-p1234', '-N', '--default-character-set=utf8mb4', '-e', query],
      { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    ).trim()
  } catch (e) {
    if (tolerant) return ''
    const err = e && e.stderr ? String(e.stderr).trim().split(/\r?\n/).filter((l) => !/Warning/i.test(l)).pop() : ''
    // ★ 断言用的查询**失败即抛**：否则"查询挂了"会伪装成"数据为空"
    throw new Error(`mysql 执行失败：${err || e.message}\nSQL: ${query}`)
  }
}

async function requireReachable(url, name) {
  try {
    await fetch(url, { method: 'GET', signal: AbortSignal.timeout(8000) })
  } catch {
    console.error(`\n❌ 前置条件不满足：${name} 不可达（${url}）`)
    process.exit(2)
  }
}

/** 用密码登录（避开验证码频控），并把 token 写进 localStorage */
async function loginByApi(page, phone, password) {
  const res = await fetch(`${GATEWAY}/as/accounts/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 1, cellPhone: phone, password, rememberMe: true }),
  })
  const body = await res.json()
  if (!body?.data) throw new Error(`密码登录失败：code=${body?.code} msg=${body?.msg}`)
  await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 })
  await page.evaluate((t) => localStorage.setItem('fitmind:token', t), body.data)
  return body.data
}

/**
 * 在浏览器里用页面自己的 token 打接口（拿到的就是"页面看到的那份数据"）。
 *
 * ★ GET 不能带 body；`Content-Type: application/json` 是非简单头，
 *   会给 GET 触发 CORS 预检 → 只在真有 body 时才加。
 */
async function api(page, method, path, body) {
  return page.evaluate(
    async ({ gateway, key, m, p, b }) => {
      const token = localStorage.getItem(key)
      const headers = {}
      if (token) headers.Authorization = token
      const init = { method: m, headers }
      if (b !== undefined && b !== null) {
        headers['Content-Type'] = 'application/json'
        init.body = JSON.stringify(b)
      }
      const res = await fetch(gateway + p, init)
      let json = null
      try {
        json = await res.json()
      } catch {
        /* 非 JSON */
      }
      return { status: res.status, body: json }
    },
    { gateway: GATEWAY, key: TOKEN_KEY, m: method, p: path, b: body ?? null },
  )
}

/**
 * 打开支付页并等它"稳定下来"。
 *
 * ★ 不能只等 `domcontentloaded`：Vue 要等 `onMounted` 里的 `loadOrder()` 回来
 *   才决定渲染哪一态 —— 而 Vite dev 首次编译会把主线程堵住几十秒。
 *   所以等的是"三态中任意一个已经渲染"这个**结果**，而不是某个时间点。
 */
async function openPayPage(page, orderId, { screenshot } = {}) {
  await page.goto(`${PORTAL}/pay/${orderId}`, { waitUntil: 'domcontentloaded', timeout: 120000 })
  // ★ 等的是"三态中任意一个**真的**渲染出来"这个结果，而不是某个时间点。
  //   ⚠️ 这里踩过一次：一开始用 Promise.race 分别等三个 selector，
  //   结果在 **loading 遮罩还没撤**时就抢到了 `pay-amount`（那时三态都是 false，
  //   页面落到了 v-else 的收银台分支）—— 于是"不存在的订单"被判成了收银台。
  //   → 用**逗号选择器**一次等齐，再一次性读 DOM 判断是哪一态，就不会抢跑。
  await page.waitForSelector(
    '[data-testid="pay-error"], [data-testid="pay-amount"], [data-testid="pay-success"]',
    { timeout: 120000 },
  )
  const mode = await page.evaluate(() => {
    if (document.querySelector('[data-testid="pay-error"]')) return 'error'
    if (document.querySelector('[data-testid="pay-success"]')) return 'success'
    if (document.querySelector('[data-testid="pay-amount"]')) return 'cashier'
    return null
  })
  await page.waitForTimeout(400)
  if (screenshot) {
    mkdirSync(SHOT_DIR, { recursive: true })
    await page.screenshot({ path: join(SHOT_DIR, screenshot), fullPage: true })
  }
  return mode
}

/** 页面上是否含某段文字（用于"绝不出现"类断言 —— 比查 DOM 更贴近用户所见） */
async function bodyText(page) {
  return (await page.textContent('body').catch(() => '')) || ''
}

/**
 * 操作 Element Plus 的 `el-select`（#6/#16 用例要用）。
 *
 * <p>★ 它**不是原生 `<select>`**，所以 `page.selectOption()` 用不了 ——
 * 必须先点开、再在下拉面板里点那一项。</p>
 *
 * <p>★ 下拉面板是 **append 到 body** 的，而且同一个页面上多个 `el-select` 共用一层
 * 浮层容器 —— 所以选择项时必须带 `:visible`，否则会点到上一个没关掉的下拉。</p>
 */
async function selectOption(page, testid, label) {
  await page.click(`[data-testid="${testid}"]`)
  const item = `.el-select-dropdown__item:visible:has-text("${label}")`
  await page.waitForSelector(item, { timeout: 10000 })
  await page.click(item)
  // 等浮层收起 + Vue 把 v-model 的回显渲染完
  await page.waitForTimeout(400)
}

/** 读输入区那行提示（#6/#16 的断言对象） */
async function composerTip(page) {
  return ((await page.textContent('.composer-tip').catch(() => '')) || '').trim()
}

/**
 * Redis 小 helper（**前端脚本原本够不着它**）。
 *
 * <p>★ 为什么前端验收也要碰 Redis：AI 会话的**记忆 key 是按 session_id 命名的**
 * （`aigc:mem:{sid}` 等）。只删 MySQL 里的会话，这些 key 会留到 TTL 到期 ——
 * 那是"跑完看起来干净、其实没干净"的典型（`accept-m6` §四 第 20 条踩过同款）。</p>
 *
 * <p>★ 密码从 `docker/.env` 读（本脚本在 `frontend/scripts/`，要往上两级）。</p>
 */
function redis(...args) {
  const env = fs.readFileSync(join(HERE, '..', '..', 'docker', '.env'), 'utf8')
  const password = (env.match(/^REDIS_PASSWORD=(.*)$/m) || [])[1]?.trim()
  const r = spawnSync('docker',
    ['exec', 'fm-redis', 'redis-cli', '-a', password, '--no-auth-warning', ...args],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  return (r.stdout || '').trim()
}

/** 当前账号的 AI 会话 id 列表（走页面自己的 token） */
async function listSessionIds(page) {
  const r = await api(page, 'GET', '/ais/chat/sessions?pageNo=1&pageSize=50')
  const list = r.body?.data?.list ?? r.body?.data ?? []
  return (Array.isArray(list) ? list : []).map((s) => String(s.sessionId ?? s.id ?? '')).filter(Boolean)
}

async function main() {
  console.log('============================================================')
  console.log(' M6-8 第 1 期验收（前端）：交易闭环 + 防假成功')
  console.log('============================================================')

  await requireReachable(`${PORTAL}/login`, 'fitmind-portal(5173)')
  await requireReachable(`${GATEWAY}/as/accounts/login`, 'fm-gateway(10010)')

  // ---------- 前置：清理上一轮可能留下的痕迹 ----------
  // ★ 这一步是"可重复执行"的前提：上一次异常退出留下的待支付订单，
  //   会让这一次的 prePlace 直接失败（"这门课有一笔待支付的订单"）。
  section('0. 准备：清掉本脚本涉及的课程残留（保证可重复执行）')
  const staleOrders = sql(
    `SELECT o.id FROM fm_trade.trade_order o
       JOIN fm_trade.trade_order_detail d ON d.order_id = o.id
      WHERE d.course_id = ${COURSE_ID} AND o.user_id = ${STUDENT_USER_ID}`,
    { tolerant: true },
  )
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean)
  for (const oid of staleOrders) {
    sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id=${oid}`, { tolerant: true })
    sql(`DELETE FROM fm_trade.trade_order WHERE id=${oid}`, { tolerant: true })
    sql(`DELETE FROM fm_pay.pay_order WHERE biz_order_no='${oid}'`, { tolerant: true })
  }
  sql(`DELETE FROM fm_learning.learning_lesson WHERE user_id=${STUDENT_USER_ID} AND course_id=${COURSE_ID}`, {
    tolerant: true,
  })
  check('前置清理完成（课程 541 的旧订单 / 权益已清空）', true, `清理 ${staleOrders.length} 张旧订单`)

  const browser = await launchBrowser()
  // ★ CDP 连接下浏览器本来就有一个 context（about:blank 标签），复用它
  const context = browser.contexts()[0] ?? (await browser.newContext())
  const page = await context.newPage()

  /** ★ 全程收集运行时异常 —— 改了核心页面，别把"页面炸了"当成"断言通过" */
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e?.message ?? e)))
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 160))
  })

  let createdOrderId = null

  /**
   * ★ 跑之前记下"已有的 AI 会话 id" —— 本脚本会真的发消息（#17 需要一条历史消息），
   * 所以跑完必须把**自己造的**会话清掉；而"只删新增的"才不会碰用户自己的会话
   * （与媒资清理同一个思路：**"删"要先确认这东西是谁的**）。
   */
  const sessionsBefore = new Set()

  try {
    await loginByApi(page, STUDENT_PHONE, STUDENT_PASSWORD)
    for (const id of await listSessionIds(page)) {
      sessionsBefore.add(id)
    }

    // ================================================================
    section('1. ★★ PayView 三态 —— 订单查不到时**绝不能**显示"支付成功"')
    // ================================================================
    // 这一组就是 M6-8 #2 本体。曾经的实现是"不是待支付 → settled = true"，
    // 于是打开一个不存在的订单号，页面会显示「支付成功 · 已完成支付 ￥0.00（免费）」。
    const ghost = await openPayPage(page, FAKE_ORDER_ID, { screenshot: 'm6-8-01-pay-not-found.png' })

    check(
      '★★★ 打开不存在的订单号 → 渲染**错误空态**（data-testid=pay-error）',
      ghost === 'error',
      `实际渲染：${ghost ?? '（三态都没出现）'}`,
    )
    const hasSuccess = (await page.locator('[data-testid="pay-success"]').count()) > 0
    check('★★★ **不出现**"支付成功"（这是 #2 的核心：失败不再被说成成功）', !hasSuccess)
    const hasAmount = (await page.locator('[data-testid="pay-amount"]').count()) > 0
    check('★ 也不渲染收银台金额区（三态互斥，不存在"同时又成功又待支付"）', !hasAmount)
    const ghostText = await bodyText(page)
    // ⚠️ 断言的是**状态文案**，不是"页面里有没有'支付成功'四个字"——
    //   收银台上的按钮就叫「模拟支付成功」，按整页文字找会把自己绊倒。
    check(
      '★★ 成功页的特征文案「已完成支付」没有出现（避免任何一处文案漏改）',
      !ghostText.includes('已完成支付'),
      ghostText.includes('已完成支付') ? '页面里仍出现"已完成支付"' : '',
    )
    check(
      '★ 加载已完成（遮罩已撤），不是停在"半加载"状态',
      (await page.locator('.el-loading-mask').count()) === 0,
    )
    check(
      '★★ 金额不再被兜底成 0 元的"免费"（读不到金额就该什么都不显示）',
      !ghostText.includes('免费'),
      ghostText.includes('免费') ? '页面里出现"免费"' : '',
    )
    check(
      '★ 错误空态给出**下一步动作**（返回订单列表 / 去选购），而不是让用户卡死',
      ghostText.includes('返回我的订单') && ghostText.includes('去选购课程'),
    )

    // ================================================================
    section('2. ★ 正常路径没被顺手搞坏 —— 真实订单仍然是收银台')
    // ================================================================
    // ★ 这一组是防"过度修复"：如果谁为了修 #2 把 settled / 收银台一起改没了，
    //   上一组会全绿，这一组会立刻红。
    const pre = await api(page, 'GET', `/ts/orders/prePlace?courseIds=${COURSE_ID}`)
    check(
      '准备数据：预下单成功（课程存在 / 已上架 / 未购买 / 无待支付旧单）',
      !!pre.body?.data?.orderId,
      JSON.stringify(pre.body)?.slice(0, 140),
    )
    const plannedAmount = pre.body?.data?.realAmount
    check(
      '★ 预下单金额等于课程价（金额只有一个来源：交易服务从库里算）',
      String(plannedAmount) === String(COURSE_PRICE),
      `预下单=${plannedAmount} / 课程价=${COURSE_PRICE}`,
    )

    const placed = await api(page, 'POST', '/ts/orders', {
      orderId: pre.body?.data?.orderId,
      courseIds: [String(COURSE_ID)],
      userCouponIds: [],
    })
    createdOrderId = placed.body?.data?.orderId
    check('准备数据：提交订单成功（真正落库的那一步）', !!createdOrderId, JSON.stringify(placed.body)?.slice(0, 140))
    check(
      '★★ 订单真的写进了 trade_order（不只看接口返回码）',
      sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE id=${createdOrderId}`) === '1',
      `orderId=${createdOrderId}`,
    )

    const cashier = await openPayPage(page, createdOrderId, { screenshot: 'm6-8-02-pay-cashier.png' })
    check('★★ 待支付订单 → 收银台（不是错误空态，也不是成功页）', cashier === 'cashier', `实际渲染：${cashier}`)
    const amountText = ((await page.textContent('[data-testid="pay-amount"]').catch(() => '')) || '').trim()
    check(
      '★★ 收银台金额 == 订单里的真实金额（逐字比对，格式与 utils/format 一致）',
      amountText === formatFen(plannedAmount),
      `页面="${amountText}" / 期望="${formatFen(plannedAmount)}"`,
    )
    check('★ 收银台不出现错误空态（三态确实是互斥的）', (await page.locator('[data-testid="pay-error"]').count()) === 0)
    const payOrderNo = ((await page.textContent('[data-testid="pay-order-no"]').catch(() => '')) || '').trim()
    check('★ 支付单已创建（收银台能拿到支付单号）', !!payOrderNo, `payOrderNo=${payOrderNo || '（空）'}`)

    // ================================================================
    section('3. ★★ 支付后 → 成功页（三态的第三态，且金额不是"免费"）')
    // ================================================================
    await page.click('[data-testid="pay-mock-submit"]')
    const settled = await page
      .waitForSelector('[data-testid="pay-success"]', { timeout: 40000 })
      .then(() => true)
      .catch(() => false)
    check('★★ 支付成功 → 渲染成功态（而不是错误空态）', settled)
    if (settled) {
      mkdirSync(SHOT_DIR, { recursive: true })
      await page.screenshot({ path: join(SHOT_DIR, 'm6-8-03-pay-success.png'), fullPage: true })
    }
    const successText = await bodyText(page)
    check(
      '★★ 成功页的金额是**订单里的真实金额**，不是被兜底出来的"免费/￥0.00"',
      successText.includes(formatFen(plannedAmount)) && !successText.includes('免费'),
      `期望包含 ${formatFen(plannedAmount)}`,
    )
    check(
      '★★ 库里订单状态真的变成"已支付"（页面成功 = 数据库也成功）',
      sql(`SELECT status FROM fm_trade.trade_order WHERE id=${createdOrderId}`) === '2',
      `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${createdOrderId}`)}（期望 2）`,
    )

    // ================================================================
    section('4. ★★ #6/#16 输入区提示：策略**恒显**，检索提示只在"真会检索"时出现')
    // ================================================================
    // 原来的实现是一个三选一（`v-if="streaming"` / `v-else-if="goal"` / `v-else`）：
    // ① 选了目标 → 再也看不到"当前策略"；
    // ② **只要选了目标就显示"知识库按…过滤"**，哪怕策略是「直答」（一次检索都不会发）；
    // ③ 目标是直接插值的，用户看到的是「muscle_gain」这种枚举值。
    await page.goto(`${PORTAL}/ai`, { waitUntil: 'domcontentloaded', timeout: 120000 })
    await page.waitForSelector('[data-testid="ai-input"]', { timeout: 90000 })

    const tipDefault = await composerTip(page)
    check('★★ 输入区**恒显**「当前策略：智能路由」（默认策略，不再被别的提示顶掉）',
      tipDefault.includes('当前策略：智能路由'), tipDefault)
    check('★ 默认（未选目标）不出现"知识库按…过滤"',
      !tipDefault.includes('知识库按'), tipDefault)

    // ① 「直答」+ 选目标 —— **本组最关键的一条**
    await selectOption(page, 'ai-chattype', '直答')
    await selectOption(page, 'ai-goal', '增肌')
    const tipSimple = await composerTip(page)
    check('★★★ 「直答」+ 选目标 → **不显示**"知识库按…过滤"（以前这里必然显示，是句假话：直答一次检索都不会发）',
      !tipSimple.includes('知识库按'), tipSimple)
    check('★★ 此时「当前策略：直答」**仍然在**（提示不再三选一互斥）',
      tipSimple.includes('当前策略：直答'), tipSimple)

    // ② 「增强检索」+ 目标 —— 这时才该显示检索提示
    await selectOption(page, 'ai-chattype', '增强检索')
    const tipEnhance = await composerTip(page)
    check('★★ 「增强检索」+ 目标 → 显示"知识库按「增肌」过滤"',
      tipEnhance.includes('知识库按「增肌」过滤'), tipEnhance)
    check('★★★ 目标是**中文 label**，不是枚举值（以前渲染的是 `muscle_gain`）',
      !tipEnhance.includes('muscle_gain') && !tipEnhance.includes('fat_loss'), tipEnhance)
    check('★★ 两段**同时**出现（这正是"不再互斥"的直接证据）',
      tipEnhance.includes('当前策略：增强检索') && tipEnhance.includes('知识库按'), tipEnhance)

    // ③ 切回智能路由：策略跟着变，且**不**再谎报检索
    await selectOption(page, 'ai-chattype', '智能路由')
    const tipBack = await composerTip(page)
    check('★ 切回智能路由：提示跟着变，且不再显示检索提示（上一轮没有检索命中）',
      tipBack.includes('当前策略：智能路由') && !tipBack.includes('知识库按'), tipBack)

    // ================================================================
    section('5. ★★ #17 切回历史会话，助手消息**也有指标行**')
    // ================================================================
    // 以前 `toLocalMessage` 把 metrics 恒置为 null → 从历史加载的消息**没有指标行**，
    // 看起来像"历史消息是另一种消息"。
    await page.fill('[data-testid="ai-input"]', '我想增肌，有什么哑铃课程推荐？')
    await page.click('[data-testid="ai-send"]')
    const liveMetrics = await page
      .waitForSelector('[data-testid="ai-metrics-line"]', { timeout: 90000 })
      .then(() => true)
      .catch(() => false)
    check('准备数据：实时回答产生了指标行（这是历史行该有的东西的对照）', liveMetrics)
    const liveLine = ((await page.textContent('[data-testid="ai-metrics-line"]').catch(() => '')) || '').trim()
    check('★ 实时指标行里含首字/耗时（说明这一轮真的落库了）',
      /ms/.test(liveLine), liveLine)

    // 走**历史加载**路径：新对话 → 再点回刚才那个会话
    await page.click('[data-testid="ai-new-session"]')
    await page.waitForTimeout(600)
    check('★ 新对话是空态（前置：确认我们确实离开了那个会话）',
      (await page.locator('[data-testid="ai-metrics-line"]').count()) === 0)
    const sessionCount = await page.locator('[data-testid="ai-session-item"]').count()
    check('准备数据：会话列表里有刚产生的那个会话', sessionCount > 0, `${sessionCount} 个`)
    await page.click('[data-testid="ai-session-item"] >> nth=0')
    const histMetrics = await page
      .waitForSelector('[data-testid="ai-metrics-line"]', { timeout: 60000 })
      .then(() => true)
      .catch(() => false)
    const histLine = ((await page.textContent('[data-testid="ai-metrics-line"]').catch(() => '')) || '').trim()
    check('★★★ 切回历史会话 → 助手消息**有指标行**了（以前这一行整行消失）', histMetrics, histLine)
    check('★★ 历史指标行里**确实有 Agent 名**（能补的 3 项之一，说明不是空白占位）',
      /Agent：/.test(histLine), histLine)
    // ★ 如实：历史行**天然少"策略"一项**（chat_message 表没存 chatType）—— 断言它"确实少"
    check('★★ 历史指标行**不假装**与实时一致：它没有"策略"那一项（表里根本没存 chatType）',
      !/策略：/.test(histLine), histLine)

    // ================================================================
    section('6. ★ #11 快捷项 tip 与真实路由相符')
    // ================================================================
    await page.click('[data-testid="ai-new-session"]')
    await page.waitForTimeout(500)
    const quickText = (await page.locator('[data-testid^="ai-quick-"]').allTextContents()).join(' ｜ ')
    check('★★ 快捷项 tip 不再写"不需要知识库，直答"（默认是智能路由，这条路不会走直答）',
      !quickText.includes('不需要知识库'), quickText.slice(0, 160))
    check('★ 「今天练什么好？」的 tip 与智能路由一致',
      quickText.includes('智能路由'), quickText.slice(0, 160))
    check('★ 另外三条 tip 也都在（没有为了改一条把别的弄丢）',
      quickText.includes('课程卡片') && quickText.includes('订单') && quickText.includes('引用来源'),
      quickText.slice(0, 200))

    // ================================================================
    section('7. ★★ 首轮自带 sessionId + 流式中「新对话」要先停后端（#7 / #8）')
    // ================================================================
    // ★ 这两条是**同一处改动**的两面。前端从"等后端在 STOP 事件里回填 sessionId"
    //   改成"**首轮就自己生成**" —— 后端本来就支持（`getOrCreate` 注释、以及
    //   `chat_session.session_id` 的列注释都写着"前端生成"），只是前端一直没这么做。
    //   而**没有 sessionId 时**：① 首轮点"停止"通知不到后端（#8，因为 stop 只认
    //   sessionId）② 流式中点"新对话"同样停不掉，后端会把整段回答生成完（#7）。
    const streamBodies = []
    const stopBodies = []
    const onReq = (req) => {
      const u = req.url()
      if (u.includes('/ais/chat/stream')) streamBodies.push(req.postData() || '')
      else if (u.includes('/ais/chat/stop')) stopBodies.push(req.postData() || '')
    }
    page.on('request', onReq)

    // 先回到"新对话"状态，保证下面发的**确实是首轮**
    await page.click('[data-testid="ai-new-session"]')
    await page.waitForTimeout(400)

    await page.fill('[data-testid="ai-input"]', '增肌期每天吃多少蛋白质')
    await page.click('[data-testid="ai-send"]')
    await page.waitForTimeout(1500)

    const firstBody = streamBodies[0] || ''
    const sentSid = (/"sessionId"\s*:\s*"([^"]+)"/.exec(firstBody) || [])[1]
    check('★★ 首轮 `/ais/chat/stream` 的请求体里**带了 sessionId**（以前是空的，要等后端回填）',
      !!sentSid, `body=${firstBody.slice(0, 170)}`)
    check('★ 而且这个 id 是**前端生成的**（后端不做格式校验，`session_id` 是 varchar(64)）',
      !!sentSid && sentSid.length >= 16, `sid=${sentSid}`)

    // ---- ② 流式中点「新对话」→ 必须通知后端停止（#7）----
    //   ★ 用 `[MOCK:SLOW]` 把 mock 的分片间隔**放慢 10 倍**，稳定地停在流式中间 ——
    //     否则这里会退化成"比谁手快"（回答几毫秒就跑完，根本测不到），是典型的假绿。
    await page.click('[data-testid="ai-new-session"]')
    await page.waitForTimeout(300)
    const streamBefore = streamBodies.length
    await page.fill('[data-testid="ai-input"]', '[MOCK:SLOW] 给我讲讲增肌期的饮食安排')
    await page.click('[data-testid="ai-send"]')
    await page.waitForSelector('[data-testid="ai-stop"]', { timeout: 20000 }).catch(() => null)
    const streamingNow = await page.isVisible('[data-testid="ai-stop"]')
    check('★ 前置：点「新对话」时**确实还在流式中**（"停止"按钮在场）—— 不在的话这条用例就等于没测',
      streamingNow, `停止按钮=${streamingNow}`)

    // ★ 第二条（要被中断的那一轮）**有它自己的 sessionId**，必须单独取。
    //   ⚠️ 第一版就错在这里：拿**第一条**的 id 去比对 stop 载荷 ——
    //      而中间点过一次「新对话」，前端早就换了新 id → 断言必然失败（**假红**）。
    const slowBody = streamBodies[streamBefore] || ''
    const slowSid = (/"sessionId"\s*:\s*"([^"]+)"/.exec(slowBody) || [])[1]
    check('★ 点过「新对话」后换的是**新** sessionId（证明它真开了新会话，而不是复用上一个）',
      !!slowSid && slowSid !== sentSid, `第一条=${sentSid} 第二条=${slowSid}`)

    const stopBefore = stopBodies.length
    await page.click('[data-testid="ai-new-session"]')
    await page.waitForTimeout(1600)
    check('★★★ 流式中点「新对话」→ 前端**真的通知了后端**（发出了 `/ais/chat/stop`）',
      stopBodies.length > stopBefore, `stop 调用数 ${stopBefore} → ${stopBodies.length}`)
    check('★ 发出去的 stop 里带的是**刚那一轮**的 sessionId（不是上一轮的 —— 这正是后端拒收 requestId 的理由）',
      !!slowSid && stopBodies.some((b) => b.includes(slowSid)),
      `期望=${slowSid} stop body=${stopBodies.join(' | ').slice(0, 170)}`)
    check('★ 点完「新对话」后消息区被清空（界面回到一个新会话）',
      !(await bodyText(page)).includes('增肌期的饮食安排'), '')

    page.off('request', onReq)
    await page.waitForTimeout(400)

    // ================================================================
    section('8. 浏览器运行时健康度')
    // ================================================================
    await page.waitForTimeout(500)
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

    // ================================================================
    section('9. ★★ #10 未登录时**不发**鉴权请求（冷启动不再刷一串 401）')
    // ================================================================
    // ★ 以前 `onMounted` 无条件发 `sessions`/`provider`/`quota` 三个鉴权请求，
    //   未登录（或登录态还没就绪）时**必然 401** → 控制台一串红 + 顶栏闪"未登录"。
    //   ★ 先排除一个容易走偏的猜测：**不是"拦截器读不到 token"** ——
    //     token 是同步从 localStorage 读的，拦截器每次都拿得到。
    //     真正的意思是"这次会话本来就没有登录态"，那就更不该发。
    const unauthorized = []
    const onResp = (res) => {
      if (res.url().includes('/ais/') && res.status() === 401) unauthorized.push(res.url())
    }
    page.on('response', onResp)
    await page.evaluate(() => localStorage.clear())
    await page.goto(`${PORTAL}/ai`, { waitUntil: 'domcontentloaded', timeout: 120000 })
    await page.waitForTimeout(3500)
    check('★★ 未登录访问 `/ai` 时**没有**任何 `/ais` 的 401（首屏鉴权请求被门禁挡住了）',
      unauthorized.length === 0, `401 共 ${unauthorized.length} 条：${unauthorized.slice(0, 3).join(' , ')}`)
    page.off('response', onResp)

    // ★ 清 localStorage 等于**登出**了 —— 后面的清理段还要用页面 token，必须重新登录
    await loginByApi(page, STUDENT_PHONE, STUDENT_PASSWORD)
    await page.goto(`${PORTAL}/ai`, { waitUntil: 'domcontentloaded', timeout: 120000 })
    await page.waitForTimeout(2000)
    check('★ 重新登录后页面正常（清 localStorage 只是本用例的手段，不能把后面的步骤搞挂）',
      (await bodyText(page)).includes('AI'), '')
  } finally {
    // ---------- 清理：本脚本产生的订单 / 支付单 / 权益 ----------
    section('10. 清理与自检')
    if (createdOrderId) {
      sql(`DELETE FROM fm_pay.pay_order WHERE biz_order_no='${createdOrderId}'`, { tolerant: true })
      sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id=${createdOrderId}`, { tolerant: true })
      sql(`DELETE FROM fm_trade.trade_order WHERE id=${createdOrderId}`, { tolerant: true })
    }
    sql(`DELETE FROM fm_learning.learning_lesson WHERE user_id=${STUDENT_USER_ID} AND course_id=${COURSE_ID}`, {
      tolerant: true,
    })
    const leftOrder = createdOrderId ? sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE id=${createdOrderId}`) : '0'
    const leftLesson = sql(
      `SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${STUDENT_USER_ID} AND course_id=${COURSE_ID}`,
    )
    check(
      '★ 订单 / 课程权益无残留（跑完的库和跑之前一样，课程 541 仍可再次购买）',
      leftOrder === '0' && leftLesson === '0',
      `order=${leftOrder} lesson=${leftLesson}`,
    )

    // ---------- AI 会话（#17 用例真的发过消息，必须清）----------
    const newSessions = (await listSessionIds(page)).filter((id) => !sessionsBefore.has(id))
    for (const sid of newSessions) {
      sql(`DELETE FROM fm_aigc.chat_message WHERE session_id='${sid}'`, { tolerant: true })
      sql(`DELETE FROM fm_aigc.chat_session WHERE session_id='${sid}'`, { tolerant: true })
      // ★ 记忆 key 也得清 —— 只删会话表的话，它们会等 7 天 TTL（"看起来干净"的经典陷阱）
      redis('DEL', `aigc:mem:${sid}`, `aigc:mem:summary:${sid}`, `aigc:gen:${sid}`)
    }
    // ★ 自检必须**删完再查一次**，而且**不能**用 `sessionsBefore.size` 去猜
    //   （第一版就是这么错的：跑之前库里恰好一个会话都没有 → `sessionsBefore.size === 0`
    //    → 走了 else 分支、直接拿"新增数"当"剩余数" → **恒为 1，永远假红**）。
    //   判据只有一条：**这些 id 现在还在不在库里**。
    const stillThere = newSessions.filter(
      (id) => sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE session_id='${id}'`) !== '0',
    )
    check('★ 本脚本产生的 AI 会话无残留（只删**本次新增**的，不碰用户自己的会话）',
      stillThere.length === 0, `本次新增 ${newSessions.length} 个，剩余 ${stillThere.length}`)

    await page.close().catch(() => {})
    // ★ 只关"自己起的那一个"浏览器；复用别人开着的就只断开连接（见 _cdp-browser.mjs）
    await closeBrowser()
  }

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
  console.error('脚本异常：', e.message)
  process.exit(2)
})
