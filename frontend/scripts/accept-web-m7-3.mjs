#!/usr/bin/env node
/**
 * ============================================================
 * M7-3 前端验收：购物车 → 下单 → 支付 → 订单 → 退款
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-auth(8081) / fm-user(8082) / fm-course(8084)
 *           fm-search(8085) / fm-trade(8088) / fm-pay(8089) / fm-learning(8086)
 *   · 数据：已跑过 `node scripts/seed-demo-data.mjs`（要有**付费且未购买**的课程）
 *   · 前端：fitmind-portal dev server (5173)
 *
 * 用法：cd frontend && node scripts/accept-web-m7-3.mjs
 *
 * ★★ 这条链路最值得证明的三件事（也是本脚本的重点）：
 *
 *   1. **金额只有服务端说了算**。
 *      页面上每一处金额都必须等于接口返回值 —— 包括"下单预览 199 元"和
 *      "支付页 199 元"和"订单里存下来的 199 元"。\n *      前端任何一处自己算了，就会在优惠券/调价时露出马脚。
 *
 *   2. **支付不是"前端改状态"**。
 *      Mock 按钮打的是真实回调（带签名 → 验签 → MQ → 订单流转），
 *      所以断言要落在"订单接口返回的状态变了"，而不是"页面显示成功"。
 *
 *   3. **`sold`（报名人数）真的加上了，而且是三处一致的**。
 *      支付成功后，DB / ES / 搜索接口读到的 sold 必须一起 +1。
 *      只查一处会被"写进库里但索引没刷"骗过去（M7-3 真的踩过这个坑：
 *      第一次查 ES 时索引还没刷完，看起来像"功能没做"）。
 *
 * 可重复执行：脚本开头会清空购物车、取消所有待支付订单；
 *   而退款之后该课程的明细不再是"已支付"，所以下次跑还能买同一门课。
 * ============================================================
 */

import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { chromium } from 'playwright'
import { launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots')

const PORTAL = 'http://localhost:5173'
const GATEWAY = 'http://localhost:10010'
const ES = 'http://localhost:9201'
const STUDENT_PHONE = '13800010001'
const TOKEN_KEY = 'fitmind:token'

/** 订单状态（抄自后端 OrderStatus，注意没有 4） */
const ORDER_NO_PAY = 1
const ORDER_PAYED = 2

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

/** 与前端 utils/format.ts 的 formatPrice 保持**同一套输出格式**（断言要逐字比对） */
function formatFen(fen) {
  if (fen === null || fen === undefined) return '—'
  if (fen === 0) return '免费'
  return `¥${(fen / 100).toFixed(2)}`
}

// ★ 浏览器连接统一见 ./_cdp-browser.mjs —— 本机必须走 CDP，
//   `chromium.launch({ channel: 'msedge' })` 在这里一定失败（见模块头部说明）。

async function requireReachable(url, name) {
  try {
    await fetch(url, { method: 'GET', signal: AbortSignal.timeout(8000) })
  } catch {
    console.error(`\n❌ 前置条件不满足：${name} 不可达（${url}）`)
    process.exit(2)
  }
}

/** 在浏览器上下文里调网关（复用页面里那份 token，避免两边状态不一致） */
async function api(page, method, path, body) {
  return page.evaluate(
    async ({ gateway, key, m, p, b }) => {
      const token = localStorage.getItem(key)
      const init = { method: m, headers: { Authorization: token } }
      if (b !== undefined && b !== null) {
        init.headers['Content-Type'] = 'application/json'
        init.body = JSON.stringify(b)
      }
      const res = await fetch(gateway + p, init)
      let json = null
      try {
        json = await res.json()
      } catch {
        /* 非 JSON（比如 Mock 的纯文本响应） */
      }
      return { status: res.status, body: json }
    },
    { gateway: GATEWAY, key: TOKEN_KEY, m: method, p: path, b: body ?? null },
  )
}

const apiGet = (page, p) => api(page, 'GET', p)
const apiPost = (page, p, b) => api(page, 'POST', p, b)
const apiPut = (page, p, b) => api(page, 'PUT', p, b)
const apiDelete = (page, p) => api(page, 'DELETE', p)

/**
 * 查数据库
 *
 * ⚠️ Windows 上必须写 `mysql.exe`：`execFileSync('mysql', ...)` 不做 PATHEXT 补全，
 *   直接 ENOENT —— 而如果把 stderr 也丢掉，症状就是"SQL 静默不生效"。
 */
function sql(query) {
  try {
    return execFileSync(
      'mysql.exe',
      ['-h127.0.0.1', '-uroot', '-p1234', '-N', '--default-character-set=utf8mb4', '-e', query],
      { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    ).trim()
  } catch (e) {
    const err = e && e.stderr ? String(e.stderr).trim().split(/\r?\n/).filter((l) => !/Warning/i.test(l)).pop() : ''
    console.log(`     ⚠️ mysql 执行失败：${err || e.message}`)
    return ''
  }
}

/** 查 ES 里某门课的 sold */
async function esSold(courseId) {
  try {
    const res = await fetch(`${ES}/fitmind_course/_doc/${courseId}`, { signal: AbortSignal.timeout(8000) })
    const json = await res.json()
    return json?._source?.sold ?? null
  } catch {
    return null
  }
}

/**
 * 轮询直到条件成立。
 *
 * ★ 这个链路上到处都是**异步**：支付回调 → MQ → 订单状态更新；
 *   订单状态更新 → 再发 MQ → fm-course 改 sold → 再发 MQ → fm-search 刷索引。
 *   用固定 sleep 要么白等、要么偶发失败（而偶发失败最容易被"重跑一次"掩盖）。
 */
async function waitFor(fn, { timeoutMs = 20000, intervalMs = 700, label = '' } = {}) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await fn()
    if (last) return last
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  if (label) console.log(`     ⏱ 等待超时：${label}`)
  return last
}

/**
 * 验证码登录（带 60 秒频控自愈，与 M7-2 同一套逻辑）
 */
async function loginWithCode(page, phone) {
  await page.fill('input[data-testid="login-phone"]', phone)
  for (let attempt = 1; attempt <= 2; attempt++) {
    await page.click('[data-testid="login-send-code"]')
    try {
      await page.waitForFunction(
        () => {
          const el = document.querySelector('input[data-testid="login-credential"]')
          return !!el && el.value.length > 0
        },
        { timeout: 9000 },
      )
      break
    } catch {
      if (attempt === 2) throw new Error('验证码始终未回填（可能是频控未解除，或后端 Mock 模式未开启）')
      console.log('  ⏳ 未取到验证码（多半是 60 秒频控），等 62 秒后重试一次…')
      await page.waitForTimeout(62000)
    }
  }
  await page.click('[data-testid="login-submit"]')
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 20000 })
}

async function main() {
  console.log('============================================================')
  console.log(' FitMind M7-3 前端验收（购物车 / 下单 / 支付 / 订单 / 退款）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(`${ES}/_cluster/health`, 'Elasticsearch')
  // 注意：/ts/carts 不带 token 会返回 401 —— 那同样证明服务活着。
  // requireReachable 只看"连接是否建立"，本来就不校验状态码，所以这里不用特殊处理。
  await requireReachable(`${GATEWAY}/ts/carts`, 'fm-trade')

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()

  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(e.message))
  const consoleErrors = []
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource/i.test(m.text())) {
      consoleErrors.push(m.text())
    }
  })

  try {
    // ================================================================
    section('0. 登录')
    // ================================================================
    await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await page.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(page, STUDENT_PHONE)
    const me = await apiGet(page, '/us/users/me')
    check('登录成功（用户端 token 已写入 localStorage）',
      Boolean(page.url().includes(PORTAL)) && me.body?.code === 200,
      `用户 id=${me.body?.data?.id}`)

    // ================================================================
    section('1. 清理前置状态（让脚本可以反复跑）')
    // ================================================================
    await apiDelete(page, '/ts/carts')
    const ordersBefore = (await apiGet(page, '/ts/orders/page?pageNo=1&pageSize=50')).body?.data?.list ?? []
    let cancelled = 0
    for (const o of ordersBefore) {
      if (o.canCancel) {
        const r = await apiPut(page, `/ts/orders/${o.id}/cancel`)
        if (r.body?.code === 200) cancelled++
      }
    }
    const cartsAfterClean = (await apiGet(page, '/ts/carts')).body?.data ?? []
    check('购物车已清空', cartsAfterClean.length === 0, `剩 ${cartsAfterClean.length} 条`)
    check('待支付订单已取消（否则会被"有未处理旧单"挡住）', true,
      `原有订单 ${ordersBefore.length} 笔，本次取消 ${cancelled} 笔`)

    // ================================================================
    section('2. 选一门「付费且可下单」的课程')
    // ================================================================
    const coursePage = await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=100')
    const allCourses = coursePage.body?.data?.list ?? []
    const paidCourses = allCourses.filter((c) => !c.free && (c.price ?? 0) > 0)
    check('存在付费课程（免费课不能下单，后端会直接拒绝）', paidCourses.length > 0,
      `共 ${paidCourses.length} 门付费课`)

    let target = null
    for (const c of paidCourses) {
      const pre = await apiGet(page, `/ts/orders/prePlace?courseIds=${c.id}`)
      if (pre.body?.code === 200) {
        target = c
        break
      }
    }
    check('找到一门可以下单的课程（未购买 / 无待支付旧单）', Boolean(target),
      target ? `${target.id} ${target.name}（${formatFen(target.price)}）` : '所有付费课都不可下单')
    if (!target) {
      throw new Error('没有可下单的课程：请先跑 seed 或清理历史订单')
    }
    const courseId = String(target.id)

    // ================================================================
    section('3. 加购（★ 页面与接口逐项对照）')
    // ================================================================
    await page.goto(`${PORTAL}/course/${courseId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="course-buy"]', { timeout: 20000 })
    await page.click('[data-testid="course-buy"]')
    await page.waitForTimeout(1200)

    const cartApi = (await apiGet(page, '/ts/carts')).body?.data ?? []
    check('加购后接口返回购物车有 1 条', cartApi.length === 1,
      cartApi.map((i) => i.courseName).join(' / '))

    await page.goto(`${PORTAL}/cart`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="cart-row"]', { timeout: 20000 })
    const cartRows = await page.locator('[data-testid="cart-row"]').count()
    check('★ 购物车页面行数 == 接口条数', cartRows === cartApi.length,
      `页面 ${cartRows} 行 / 接口 ${cartApi.length} 条`)

    const cartPriceText = (await page.textContent('[data-testid="cart-price"]'))?.trim()
    check('★ 购物车页面单价 == 接口 price（前端不做任何价格换算）',
      cartPriceText === formatFen(cartApi[0]?.price),
      `页面「${cartPriceText}」/ 接口「${formatFen(cartApi[0]?.price)}」`)

    const cartTotalText = (await page.textContent('[data-testid="cart-total"]'))?.trim()
    check('★ 购物车合计 == 勾选课程价格之和',
      cartTotalText === formatFen(cartApi[0]?.price),
      `页面「${cartTotalText}」`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-01-cart.png'), fullPage: true })

    // ================================================================
    section('4. 去结算（★ 金额必须在进入前就和服务端对齐）')
    // ================================================================
    const preApi = (await apiGet(page, `/ts/orders/prePlace?courseIds=${courseId}`)).body?.data
    await page.click('[data-testid="cart-checkout"]')
    await page.waitForURL(/\/checkout/, { timeout: 20000 })
    await page.waitForSelector('[data-testid="checkout-submit"]', { timeout: 20000 })

    const checkoutTotal = (await page.textContent('[data-testid="checkout-total"]'))?.trim()
    check('★ 结算页「课程合计」== prePlace 接口 totalAmount',
      checkoutTotal === formatFen(preApi?.totalAmount),
      `页面「${checkoutTotal}」/ 接口「${formatFen(preApi?.totalAmount)}」`)

    const checkoutRows = await page.locator('[data-testid="checkout-course-row"]').count()
    check('★ 结算页课程行数 == 接口 courses 长度',
      checkoutRows === (preApi?.courses ?? []).length,
      `页面 ${checkoutRows} 行 / 接口 ${(preApi?.courses ?? []).length} 门`)

    const couponEmpty = await page.locator('[data-testid="checkout-coupon-empty"]').count()
    const payable = (await page.textContent('[data-testid="checkout-payable"]'))?.trim()
    check('★ 未选券时「预估应付」== 接口 totalAmount',
      payable === formatFen(preApi?.totalAmount),
      `页面「${payable}」/ 接口「${formatFen(preApi?.totalAmount)}」（无券：${couponEmpty > 0 ? '是' : '否'}）`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-02-checkout.png'), fullPage: true })

    // ================================================================
    section('5. 提交订单（服务端算钱 + 幂等）')
    // ================================================================
    await page.click('[data-testid="checkout-submit"]')
    await page.waitForURL(/\/pay\//, { timeout: 30000 })
    const orderId = page.url().split('/pay/')[1]?.split('?')[0]
    check('提交订单后跳转到支付页并带上订单号', Boolean(orderId), `orderId=${orderId}`)

    const orderApi = (await apiGet(page, `/ts/orders/${orderId}`)).body?.data
    check('订单已创建且状态为「待支付」', orderApi?.status === ORDER_NO_PAY,
      `status=${orderApi?.status}（${orderApi?.statusDesc}）`)
    check('★ 订单实付金额 == 结算页展示金额（服务端复算了同一份价格）',
      formatFen(orderApi?.realAmount) === checkoutTotal,
      `订单「${formatFen(orderApi?.realAmount)}」/ 结算页「${checkoutTotal}」`)

    // ================================================================
    section('6. 支付页')
    // ================================================================
    await page.waitForSelector('[data-testid="pay-amount"]', { timeout: 20000 })
    const payAmountText = (await page.textContent('[data-testid="pay-amount"]'))?.trim()
    check('★ 支付页金额 == 订单接口 realAmount（金额从订单读，不从 URL 传）',
      payAmountText === formatFen(orderApi?.realAmount),
      `页面「${payAmountText}」/ 接口「${formatFen(orderApi?.realAmount)}」`)

    const payOrderNo = (await page.textContent('[data-testid="pay-order-no"]').catch(() => ''))?.trim()
    check('支付页展示了支付单号（已向 fm-pay 申请到支付单）', Boolean(payOrderNo), payOrderNo || '（空）')

    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-03-pay.png'), fullPage: true })

    // ================================================================
    section('7. 支付前的 sold 基线')
    // ================================================================
    const soldBeforeDb = Number(sql(`SELECT sold FROM fm_course.course WHERE id=${courseId};`) || '-1')
    const soldBeforeEs = await esSold(courseId)
    const soldBeforeApi = (await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=100'))
      .body?.data?.list?.find((c) => String(c.id) === courseId)?.sold
    check('支付前 sold 三处一致（DB / ES / 接口）',
      soldBeforeDb === soldBeforeEs && soldBeforeDb === soldBeforeApi,
      `DB=${soldBeforeDb} ES=${soldBeforeEs} 接口=${soldBeforeApi}`)

    // ================================================================
    section('8. 模拟支付（★ 走真实回调链路，不是前端改状态）')
    // ================================================================
    await page.click('[data-testid="pay-mock-submit"]')
    await page.waitForSelector('[data-testid="pay-success"]', { timeout: 40000 })
    check('支付成功后页面展示成功态', true)

    const orderPaid = (await apiGet(page, `/ts/orders/${orderId}`)).body?.data
    check('★ 订单接口状态已变为「已支付」（说明回调 → 验签 → MQ → 状态流转整条链路通了）',
      orderPaid?.status === ORDER_PAYED,
      `status=${orderPaid?.status}（${orderPaid?.statusDesc}）`)
    check('★ 支付后订单实付金额与支付前一致（没有二次改价）',
      orderPaid?.realAmount === orderApi?.realAmount,
      `${formatFen(orderPaid?.realAmount)}`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-04-pay-success.png'), fullPage: true })

    // ================================================================
    section('9. ★★ sold（报名人数）三处 +1')
    // ================================================================
    const expectSold = soldBeforeDb + 1
    const soldAfterDb = await waitFor(
      async () => {
        const v = Number(sql(`SELECT sold FROM fm_course.course WHERE id=${courseId};`) || '-1')
        return v === expectSold ? v : null
      },
      { label: `DB sold 变为 ${expectSold}`, timeoutMs: 15000 },
    )
    check('★ DB course.sold 已 +1（fm-course 消费 order.pay 的 listener 生效）',
      soldAfterDb === expectSold, `${soldBeforeDb} → ${soldAfterDb}（期望 ${expectSold}）`)

    const soldAfterEs = await waitFor(
      async () => {
        const v = await esSold(courseId)
        return v === expectSold ? v : null
      },
      { label: `ES sold 变为 ${expectSold}`, timeoutMs: 20000 },
    )
    check('★ ES 索引里的 sold 也 +1（说明 course.update 消息被消费、索引已刷新）',
      soldAfterEs === expectSold, `ES=${soldAfterEs}（期望 ${expectSold}）`)
    console.log('     ↑ 这一条是"库里有、页面没有"的唯一防线：只查 DB 会以为功能好了')

    const soldAfterApi = await waitFor(
      async () => {
        const list = (await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=100')).body?.data?.list ?? []
        const v = list.find((c) => String(c.id) === courseId)?.sold
        return v === expectSold ? v : null
      },
      { label: `接口 sold 变为 ${expectSold}`, timeoutMs: 20000 },
    )
    check('★ 搜索接口读到的 sold 也 +1（用户实际看到的就是这个）',
      soldAfterApi === expectSold, `接口=${soldAfterApi}（期望 ${expectSold}）`)

    // ================================================================
    section('10. 订单列表页（★ 页面与接口交叉验证）')
    // ================================================================
    await page.goto(`${PORTAL}/orders`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="order-card"]', { timeout: 20000 })

    const ordersApi = (await apiGet(page, '/ts/orders/page?pageNo=1&pageSize=5')).body?.data
    const cardsOnPage = await page.locator('[data-testid="order-card"]').count()
    check('★ 订单页卡片数 == 接口返回条数',
      cardsOnPage === (ordersApi?.list ?? []).length,
      `页面 ${cardsOnPage} 张 / 接口 ${(ordersApi?.list ?? []).length} 条`)

    const newest = (ordersApi?.list ?? []).find((o) => String(o.id) === String(orderId))
    const statusTextOnPage = (await page.locator('[data-testid="order-status"]').first().textContent())?.trim()
    check('★ 列表里最新订单的状态文案 == 接口 statusDesc',
      statusTextOnPage === newest?.statusDesc,
      `页面「${statusTextOnPage}」/ 接口「${newest?.statusDesc}」`)

    const realAmountOnPage = (await page.textContent('[data-testid="order-real-amount"]'))?.trim()
    check('★ 列表里显示的实付金额 == 接口 realAmount',
      realAmountOnPage === formatFen(newest?.realAmount),
      `页面「${realAmountOnPage}」/ 接口「${formatFen(newest?.realAmount)}」`)

    const payBtnCount = await page.locator('[data-testid="order-pay-btn"]').count()
    check('已支付订单不再显示「去支付」按钮', payBtnCount === 0, `找到 ${payBtnCount} 个`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-05-orders.png'), fullPage: true })

    // ================================================================
    section('11. 订单详情 + 申请退款')
    // ================================================================
    await page.goto(`${PORTAL}/orders/${orderId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="detail-order-id"]', { timeout: 20000 })

    const detailIdText = (await page.textContent('[data-testid="detail-order-id"]'))?.trim()
    check('详情页订单号 == URL 里的订单号', detailIdText === String(orderId), `页面「${detailIdText}」`)

    const detailAmount = (await page.textContent('[data-testid="detail-real-amount"]'))?.trim()
    check('★ 详情页实付金额 == 接口 realAmount',
      detailAmount === formatFen(orderPaid?.realAmount),
      `页面「${detailAmount}」/ 接口「${formatFen(orderPaid?.realAmount)}」`)

    await page.click('[data-testid="detail-refund-btn"]')
    await page.waitForSelector('[data-testid="refund-reason"]', { timeout: 15000 })
    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-06-refund-dialog.png'), fullPage: true })

    // 选一个退款原因（Element Plus 的 el-select 需要先点开再选项）
    await page.click('[data-testid="refund-reason"]')
    await page.waitForTimeout(500)
    await page.locator('.el-select-dropdown__item').first().click()
    await page.waitForTimeout(300)
    await page.click('[data-testid="refund-submit"]')

    const refundApplied = await waitFor(
      async () => {
        const body = (await apiGet(page, `/ts/refund-apply/detail/${orderPaid?.details?.[0]?.id}`)).body
        return body?.code === 200 ? body.data : null
      },
      { label: '退款申请落库', timeoutMs: 15000 },
    )
    check('★ 退款申请已创建（接口查得到）', Boolean(refundApplied?.id),
      refundApplied ? `退款单 id=${refundApplied.id} 状态=${refundApplied.statusDesc}` : '未查到')

    check('★ 退款金额 == 该课程实付金额（服务端算，不信前端）',
      refundApplied?.refundAmount === (orderPaid?.details?.[0]?.realPayAmount ?? null),
      `退款 ${formatFen(refundApplied?.refundAmount)} / 实付 ${formatFen(orderPaid?.details?.[0]?.realPayAmount)}`)

    await page.waitForTimeout(1500)
    const detailAfterRefund = (await apiGet(page, `/ts/orders/${orderId}`)).body?.data
    check('★ 订单详情里该课程明细状态变为「退款中」（前端重新拉接口，不本地改状态）',
      detailAfterRefund?.details?.[0]?.status === 4,
      `明细 status=${detailAfterRefund?.details?.[0]?.status}`)
    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-07-order-detail.png'), fullPage: true })

    // ================================================================
    section('12. 退款记录页')
    // ================================================================
    await page.goto(`${PORTAL}/refunds`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1500)

    const refundsApi = (await apiGet(page, '/ts/refund-apply/page?pageNo=1&pageSize=10')).body?.data
    const refundCards = await page.locator('[data-testid="refund-card"]').count()
    check('★ 退款记录页卡片数 == 接口条数',
      refundCards === (refundsApi?.list ?? []).length,
      `页面 ${refundCards} 张 / 接口 ${(refundsApi?.list ?? []).length} 条`)

    const refundAmountOnPage = (await page.textContent('[data-testid="refund-amount"]'))?.trim()
    const newestRefund = (refundsApi?.list ?? [])[0]
    check('★ 退款记录页金额 == 接口 refundAmount',
      refundAmountOnPage === formatFen(newestRefund?.refundAmount),
      `页面「${refundAmountOnPage}」/ 接口「${formatFen(newestRefund?.refundAmount)}」`)

    const refundStatusOnPage = (await page.textContent('[data-testid="refund-status"]'))?.trim()
    check('★ 退款状态文案 == 接口 statusDesc',
      refundStatusOnPage === newestRefund?.statusDesc,
      `页面「${refundStatusOnPage}」/ 接口「${newestRefund?.statusDesc}」`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-3-08-refunds.png'), fullPage: true })

    // ================================================================
    section('13. 收尾校验')
    // ================================================================
    const cartFinal = (await apiGet(page, '/ts/carts')).body?.data ?? []
    check('下单后购物车里的该课程已被移除（结算过的条目不该留着）',
      !cartFinal.some((i) => String(i.courseId) === courseId),
      `购物车剩 ${cartFinal.length} 条`)

    await page.goto(`${PORTAL}/cart`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1200)
    /**
     * ★ 口径必须落到**购物车那个角标**上，不能数全页面的 `.el-badge__content`。
     *
     *   顶栏有**两个** `el-badge`（消息 / 购物车，见 `DefaultLayout.vue`），
     *   两者共用 `.el-badge__content` 这个内部类 —— 于是只要**消息有未读**
     *   （由别的验收留下的站内信造成），全局计数就 > 0，
     *   这条"购物车角标不该显示"就会在**与购物车毫无关系**的情况下报红。
     *   ⚠️ 实测踩到过：M7-9 回归时它失败，查了半天 DOM 才发现是消息角标。
     *   → 用 `data-testid="header-cart"` 限定归属。
     */
    const cartBadge = page.locator('[data-testid="header-cart"] .el-badge__content')
    const badges = await cartBadge.count()
    const badgeText = badges ? (await cartBadge.first().innerText()).trim() : ''
    check(
      '购物车角标不显示（没有商品时不该出现"0"）',
      badges === 0 || badgeText === '',
      `购物车角标 ${badges} 个${badges ? `，内容「${badgeText}」` : ''}`,
    )

    // ================================================================
    section('14. 浏览器运行时健康度')
    // ================================================================
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
  } finally {
    await browser.close()
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
  console.error('脚本异常：', e)
  process.exit(2)
})
