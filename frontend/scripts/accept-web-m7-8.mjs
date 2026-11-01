/**
 * M7-8 端到端验收：管理端 5 个业务页 + 权限边界 + 用券路径
 *
 * 运行：cd frontend && npm run accept:m7-8
 *
 * 前置：
 *   · 后端 15 个服务全起（`bash scripts/start-group.sh ...`）
 *   · 数据已造：`node scripts/seed-demo-data.mjs --stats`
 *   · 两个 dev server：portal 5173 / admin 5174
 *
 * 本脚本除了"页面渲染出来了"之外，重点验证四类**很容易做成假绿**的事：
 *
 *  ① **页面数字 == 接口数字**（逐项，不是"看起来差不多"）
 *  ② ★★ **权限边界**：学员 token 调管理端接口**必须被拒** ——
 *     这是唯一能证明"权限是后端在管、不是前端藏了菜单"的方式。
 *     M7-9 起 fm-media / fm-promotion / fm-exam 已补上管理端鉴权，一并纳入"必须 403"；
 *     **只剩 fm-course** 仍缺（它的路径学员端与管理端同前缀，需要 SDK 支持方法级匹配），
 *     对它保留**显式的"已知缺口"断言**：断言它"确实通过了"，
 *     把缺口变成一条**可见的**记录，而不是让脚本沉默地跳过它。
 *     ★ 并且做**反向回归**：收权最容易的失误是把学员端自己的接口也圈进去
 *     （播视频 / 结算页试算 / 看自己的券），所以这三条必须仍然**能用**。
 *  ③ ★★ **用券路径**（收掉问题清单 #D1）：建券 → 发放 → 领取 → 结算页用它
 *     → 断言页面"优惠/应付" == 促销服务算出来的值。
 *  ④ ★ **故障注入用完必须关掉**：它是全局开关，脚本开着不管会把环境留成故障态。
 *     所以这一节放在**最后**并且 finally 里无条件复位。
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

import { chromium } from 'playwright'

// ============================================================
// 配置
// ============================================================
const ADMIN = 'http://localhost:5174'
const PORTAL = 'http://localhost:5173'
const GATEWAY = 'http://localhost:10010'
const SHOT_DIR = join(process.cwd(), '..', 'docs', 'screenshots')

/** ★ 两个端的 token 存储 key 是**不同的**（管理端刻意分开，避免同机开发时互相污染） */
const ADMIN_TOKEN_KEY = 'fitmind:admin:token'
const PORTAL_TOKEN_KEY = 'fitmind:token'

const ADMIN_PHONE = '13800010003' // 管理员
const STUDENT_PHONE = '13800010001' // 学员
const SEED_PASSWORD = '123456'

/** 直查库用（与 scripts/accept-m*.mjs 同一条路径，避免多处硬编码不一致） */
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'

// ============================================================
// 断言与工具
// ============================================================

/**
 * 直查库（只读）。
 *
 * ★ 为什么前端验收脚本也要能查库：有些契约**从接口返回是看不出对错的**，
 *   只能拿库里的真相去比对。例："不传 status 会路由到草稿表"——
 *   如果只断言"返回 0 条"，那它测的是**数据状态**（草稿表恰好为空），
 *   不是**路由行为**；草稿表一旦有数据，断言就会误报（实测发生过）。
 * → 凡是要断言"实现走了哪张表 / 落库成了什么"，一律查库比对。
 */
function sql(query) {
  const r = spawnSync(
    MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  if (r.error) throw new Error(`SQL 执行失败（进程无法启动）：${query}\n${r.error.message}`)
  if (r.status !== 0) {
    throw new Error(`SQL 执行失败（退出码 ${r.status}）：${query}\n${(r.stderr || '').trim()}`)
  }
  return (r.stdout || '').trim()
}
let passed = 0
let failed = 0
const failures = []

function ok(name, detail = '') {
  passed += 1
  console.log(`  ✅ ${name}${detail ? `（${detail}）` : ''}`)
}

function check(name, condition, detail = '') {
  if (condition) ok(name, detail)
  else {
    failed += 1
    failures.push(name + (detail ? ` —— ${detail}` : ''))
    console.log(`  ❌ ${name}${detail ? ` —— ${detail}` : ''}`)
  }
}

function section(title) {
  console.log(`\n${'='.repeat(74)}\n${title}\n${'='.repeat(74)}`)
}

const flat = (s) => (s ?? '').replace(/\s+/g, ' ').trim()

async function waitFor(fn, { label = '条件', timeoutMs = 15000, intervalMs = 500 } = {}) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await fn()
    if (last) return last
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  throw new Error(`等待「${label}」超时（${timeoutMs}ms）`)
}

/**
 * 在页面里直接打网关（带该端的 token）。★ 用来做"页面 vs 接口"的交叉验证
 *
 * ★ 为什么带重试：页面一旦发生导航，当前 execution context 会被销毁，
 *   此时 `page.evaluate` 抛 "Execution context was destroyed" ——
 *   那不是接口出错，而是**问得太早**。与 loginByApi 里"注入后等页面稳定"配合，
 *   构成双保险（导航可能在任意一次请求期间发生，只靠等待消除不掉）。
 */
async function api(page, method, path, body, tokenKey, retries = 2) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await page.evaluate(
        async ([m, p, b, key]) => {
          const token = localStorage.getItem(key)
          const headers = {}
          // ★ GET 不带 body 就不要加 Content-Type：它是非简单头，会让 GET 触发 CORS 预检
          if (b !== undefined && b !== null) headers['Content-Type'] = 'application/json'
          if (token) headers.Authorization = token
          const init = { method: m, headers }
          if (b !== undefined && b !== null) init.body = JSON.stringify(b)
          try {
            const res = await fetch(`http://localhost:10010${p}`, init)
            const text = await res.text()
            let json = null
            try {
              json = JSON.parse(text)
            } catch {
              json = null
            }
            return { status: res.status, body: json, raw: text.slice(0, 200) }
          } catch (e) {
            return { status: -1, body: null, raw: String(e) }
          }
        },
        [method, path, body ?? null, tokenKey],
      )
    } catch (e) {
      const msg = String(e?.message ?? e)
      const transient = /Execution context was destroyed|Target closed|detached from document/i.test(msg)
      if (transient && attempt < retries) {
        await page.waitForLoadState('domcontentloaded').catch(() => undefined)
        await page.waitForTimeout(500)
        continue
      }
      throw e
    }
  }
}

const adminApi = (page, m, p, b) => api(page, m, p, b, ADMIN_TOKEN_KEY)
const portalApi = (page, m, p, b) => api(page, m, p, b, PORTAL_TOKEN_KEY)

/**
 * 用密码登录，并把 token 注入到该端的 localStorage
 *
 * ★ 为什么不驱动登录页表单：
 *   ① 用户端默认是**验证码模式**，切到密码模式要额外操作，且验证码有 60 秒频控
 *     （M7-5 为此等过一整分钟）；
 *   ② "登录页能不能用" 已由 **M7-1 的验收**覆盖（26 项），这里重复只是浪费。
 *   → 与 M7-5/M7-6/M7-7 的脚本保持同一套做法。
 */
async function loginByApi(page, base, path, phone, password, tokenKey) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 1, cellPhone: phone, password, rememberMe: true }),
  })
  const body = await res.json()
  if (!body?.data) throw new Error(`登录失败：code=${body?.code} msg=${body?.msg}`)
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await page.evaluate(([k, t]) => localStorage.setItem(k, t), [tokenKey, body.data])
  /**
   * ★★ 注入 token 之后**必须等页面稳定**再往下走。
   *
   *   登录页挂载时会检查 localStorage，发现已有 token 就 `router.push` 首页 ——
   *   这个导航会**销毁当前 execution context**，于是紧接着的
   *   `page.evaluate(fetch)` 会报：
   *     "Execution context was destroyed, most likely because of a navigation"
   *
   *   这不是脚本写错了流程，而是**时序竞态**：
   *   `domcontentloaded` 时 Vue 可能还没挂载完 —— 快的时候（模块已缓存/页面预热过）
   *   注入发生在"检查之后"，就没事；慢的时候（本次 dev server 刚重启、依赖刚重新优化）
   *   注入发生在"检查之前"，就会跳转。
   *   ★ 与本项目"异步断言必须轮询"是同一条教训：**别赌时序**。
   */
  await page.waitForLoadState('domcontentloaded').catch(() => undefined)
  await page.waitForTimeout(800)
  return body.data
}

// ============================================================
// main
// ============================================================
async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await chromium.launch({ channel: 'msedge' })
  const adminCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const portalCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const admin = await adminCtx.newPage()
  const portal = await portalCtx.newPage()

  /**
   * 健康度探针：pageerror / console error / 业务码非 200
   *
   * ★★ `collectErrors=false` 期间不收集业务错误 —— 用于**刻意的负向测试**。
   *
   *   第 7 节会**故意**用学员 token 去打管理端接口，那必然产生一批 `code=403`。
   *   第一版没做区分，于是"业务码非 200 响应为 0"这条健康度断言
   *   **把刻意的探测一起算了进去**，报了一屏 403 ——
   *   而它想抓的是"**非预期**的业务错误"。
   *
   *   这类"断言把测试自己的动作算成失败"的坑，本质和 M7-5 的
   *   "期望值拿开头快照算"是同一个：**断言的口径没把"我自己的行为"排除掉**。
   */
  let collectErrors = true
  const pageErrors = []
  const consoleErrors = []
  const businessErrors = []
  const attach = (page, tag) => {
    page.on('pageerror', (e) => pageErrors.push(`[${tag}] ${e.message}`))
    page.on('console', (m) => {
      if (m.type() === 'error' && !/Failed to load resource/i.test(m.text())) {
        consoleErrors.push(`[${tag}] ${m.text()}`)
      }
    })
    page.on('response', async (res) => {
      const url = res.url()
      if (!url.includes('localhost:10010')) return
      try {
        const json = await res.json()
        if (json && typeof json.code === 'number' && json.code !== 200 && collectErrors) {
          businessErrors.push(`[${tag}] ${res.request().method()} ${url.split('10010')[1]} → code=${json.code} ${json.msg ?? ''}`)
        }
      } catch {
        /* 非 JSON 响应忽略 */
      }
    })
  }
  attach(admin, 'admin')
  attach(portal, 'portal')

  /** ★ 故障注入的复位标记（finally 里无条件复位） */
  let failModeTurnedOn = false
  /** ★ 本次运行创建的券 id（用于"已知缺口"说明，不删——券没有删除接口） */
  const created = { couponId: null }

  try {
    // ================================================================
    section('1. 前置检查 + 管理员登录')
    // ================================================================
    for (const [label, url] of [
      ['gateway', `${GATEWAY}/us/codes?phone=1`],
      ['admin dev server', `${ADMIN}/`],
      ['portal dev server', `${PORTAL}/`],
    ]) {
      try {
        const res = await fetch(url, { method: url.includes('codes') ? 'POST' : 'GET' })
        check(`${label} 可达`, res.status > 0, `HTTP ${res.status}`)
      } catch (e) {
        check(`${label} 可达`, false, String(e))
      }
    }

    const adminToken = await loginByApi(
      admin,
      ADMIN,
      '/as/accounts/admin/login',
      ADMIN_PHONE,
      SEED_PASSWORD,
      ADMIN_TOKEN_KEY,
    )
    check('★ 管理员登录成功（token 落 localStorage）', !!adminToken, `长度 ${adminToken?.length ?? 0}`)

    // ================================================================
    section('2. 数据看板：卡片数字 == 接口 / source 必须展示')
    // ================================================================
    await admin.goto(`${ADMIN}/`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('[data-testid="dash-source"]', { timeout: 30000 })

    const ov = (await adminApi(admin, 'GET', '/ds/admin/data/overview')).body?.data
    check('概览接口返回成功', !!ov, JSON.stringify(ov)?.slice(0, 90))

    const sourceText = flat(await admin.textContent('[data-testid="dash-source"]'))
    /**
     * ★★ 这条是本页最重要的断言：`source` 决定"这个数还会不会变"。
     *   今天的数据在 Redis 里（realtime，随业务变化），落库后才固定（archived）。
     *   不展示它，用户会经历"上午 1200、下午 2600"，然后怀疑系统。
     */
    const expectSource =
      ov?.source === 'realtime' ? '实时计数（今天，还会变）' : ov?.source === 'archived' ? '已落库（不再变）' : '无数据'
    check('★★ 看板明确标出数据来源（realtime / archived / empty）', sourceText === expectSource, `页面「${sourceText}」/ 接口「${expectSource}」`)

    // 逐项比对（有值才比，空数据时接口是 null）
    const pairs = [
      ['ov-order-count', ov?.orderCount],
      ['ov-refund-count', ov?.refundCount],
      ['ov-signin', ov?.signInCount],
      ['ov-learn', ov?.learnCount],
    ]
    let priceMatched = 0
    for (const [testid, val] of pairs) {
      const shown = flat(await admin.textContent(`[data-testid="${testid}"]`))
      const expect = val === null || val === undefined ? '—' : Number(val).toLocaleString('zh-CN')
      if (shown === expect) priceMatched += 1
      else console.log(`     [diff] ${testid}: 页面「${shown}」/ 接口「${expect}」`)
    }
    check('★ 计数类卡片与接口逐项一致', priceMatched === pairs.length, `${priceMatched}/${pairs.length}`)

    // ★ 这条断言原来的口径是"必须有 canvas" —— 但看板在**该区间没有数据**时会（刻意）
    //   显示空态说明，而不是画一个空坐标系（ECharts 的空图看起来像"坏了"）。
    //   而"最近 N 天没有成交/签到"在新环境里是**正常状态**，不是故障。
    //   → 口径改成"**图表区域不是一片空白**"：要么有 canvas，要么有空态。
    //   ★ 这是"我自己改了实现却没同步断言" —— 断言要跟着实现的口径走，
    //     而不是让实现去迁就一句写死了的断言（那是把正确实现当 bug 修）。
    const chartCount = await admin.locator('[data-testid="dash-chart"] canvas').count()
    const chartEmpty = await admin.locator('[data-testid="dash-chart-empty"]').count()
    check(
      '★ 趋势图区域不是空白（有数据→canvas / 无数据→空态说明）',
      chartCount > 0 || chartEmpty > 0,
      `canvas=${chartCount} empty=${chartEmpty}`,
    )

    const topRows = await admin.locator('[data-testid="dash-top-table"] .el-table__body tbody tr').count()
    const topApi = (await adminApi(admin, 'GET', '/ds/admin/data/course-top?limit=10')).body?.data ?? []
    check('★ 销量榜行数 == 接口条数', topRows === topApi.length, `页面 ${topRows} / 接口 ${topApi.length}`)

    await admin.screenshot({ path: join(SHOT_DIR, 'm7-8-01-dashboard.png'), fullPage: true })

    // ================================================================
    section('3. 退款审批：列表 == 接口 / 详情金额 == 接口')
    // ================================================================
    await admin.goto(`${ADMIN}/refunds`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('[data-testid="refund-table"]', { timeout: 30000 })
    await admin.waitForTimeout(1200)

    const refundApi = (await adminApi(admin, 'GET', '/ts/admin/refund-apply/page?pageNo=1&pageSize=10&refundStatus=1')).body?.data
    const refundRows = await admin.locator('[data-testid="refund-table"] .el-table__body tbody tr').count()
    check(
      '★ 退款列表行数 == 接口条数（★ refundStatus=1 待审批）',
      refundRows === (refundApi?.list ?? []).length,
      `页面 ${refundRows} / 接口 ${refundApi?.list?.length ?? 0}`,
    )
    check('★ 页面「共 N 条」== 接口 total', flat(await admin.textContent('[data-testid="refund-total"]')) === `共 ${refundApi?.total ?? 0} 条`, `页面「${flat(await admin.textContent('[data-testid="refund-total"]'))}」`)

    if (refundRows > 0) {
      await admin.click('[data-testid="refund-detail"]')
      await admin.waitForSelector('[data-testid="detail-real-pay"]', { timeout: 15000 })
      const firstId = refundApi?.list?.[0]?.id
      const detailApi = (await adminApi(admin, 'GET', `/ts/admin/refund-apply/${firstId}`)).body?.data
      const shownPay = flat(await admin.textContent('[data-testid="detail-real-pay"]'))
      // ★ 详情里的"实付"就是后端认定的退款金额（口径见 RefundApplyVO.realPayAmount）
      const expectPay = `¥${(Number(detailApi?.realPayAmount ?? 0) / 100).toFixed(2)}`
      check('★★ 详情「实付（= 退款金额）」== 接口 realPayAmount', shownPay === expectPay, `页面「${shownPay}」/ 接口「${expectPay}」`)
      await admin.keyboard.press('Escape')
      await admin.waitForTimeout(500)
    } else {
      console.log('     ⚠️ 当前没有待审批的退款 → 详情断言跳过（可先在用户端申请一笔退款）')
    }

    await admin.screenshot({ path: join(SHOT_DIR, 'm7-8-02-refund.png'), fullPage: true })

    // ================================================================
    section('4. 课程管理：状态必选（含"不传 status → 0 条"这条契约）')
    // ================================================================
    const q = '?pageNo=1&pageSize=10'
    const withStatus = (await adminApi(admin, 'GET', `/cs/courses/page${q}&status=2`)).body?.data
    const withoutStatus = (await adminApi(admin, 'GET', `/cs/courses/page${q}`)).body?.data

    /**
     * ★★ 判据是**直查库比对行数**，不是"恒为 0"。
     *
     *   这条契约要证明的是「`CourseController.queryForPage` 按 status 把查询
     *   **路由到两张表**」：已上架(2)/已完结(4) → `course`，其它（含 null）→ `course_draft`。
     *
     *   而"草稿表里有没有数据"是**数据状态**，不是这条契约的一部分 ——
     *   实测就翻过车：探测权限矩阵时用管理员 token 建了一门草稿课（name='x'），
     *   草稿表从 0 变 1，于是这条断言从绿变红，**而接口行为完全正确**。
     *   （同族：m3-1 把"本周计划总数=4"写死，实际它是所有进行中计划之和。）
     *
     *   → 改成"和草稿表行数相等、同时和正式表 status=2 的行数相等"：
     *     草稿表空不空都成立，而且真正验证了**走的是哪张表**。
     */
    const draftRows = sql('SELECT COUNT(*) FROM fm_course.course_draft')
    const shelfRows = sql('SELECT COUNT(*) FROM fm_course.course WHERE status = 2')
    check(
      '★★ 契约：不传 status 走**草稿表**、传 status=2 走**正式表**（接口按状态路由到两张表）',
      Number(withoutStatus?.total ?? -1) === Number(draftRows) &&
        Number(withStatus?.total ?? -1) === Number(shelfRows),
      `不传 total=${withoutStatus?.total}（草稿表 ${draftRows} 行）· ` +
        `status=2 total=${withStatus?.total}（正式表 status=2 共 ${shelfRows} 行）`,
    )

    await admin.goto(`${ADMIN}/courses`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('[data-testid="course-table"]', { timeout: 30000 })
    await admin.waitForTimeout(1500)

    const courseRows = await admin.locator('[data-testid="course-table"] .el-table__body tbody tr').count()
    check('★ 课程列表默认（已上架）有数据且行数 == 接口条数', courseRows === (withStatus?.list ?? []).length, `页面 ${courseRows} / 接口 ${withStatus?.list?.length ?? 0}`)
    check('★ 页面「共 N 门」== 接口 total', flat(await admin.textContent('[data-testid="course-total"]')) === `共 ${withStatus?.total ?? 0} 门`, `页面「${flat(await admin.textContent('[data-testid="course-total"]'))}」`)
    const firstName = flat(await admin.textContent('[data-testid="course-name"]'))
    check('★ 课程名渲染正确（与接口首条一致）', firstName === (withStatus?.list?.[0]?.name ?? ''), `页面「${firstName}」`)
    await admin.screenshot({ path: join(SHOT_DIR, 'm7-8-03-courses.png'), fullPage: true })

    // ================================================================
    section('5. 媒资管理：列表 == 接口 / 大小与时长已格式化')
    // ================================================================
    await admin.goto(`${ADMIN}/media`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('[data-testid="media-table"]', { timeout: 30000 })
    await admin.waitForTimeout(1500)

    const mediaApi = (await adminApi(admin, 'GET', '/ms/medias?pageNo=1&pageSize=10')).body?.data
    const mediaRows = await admin.locator('[data-testid="media-table"] .el-table__body tbody tr').count()
    check('★ 媒资列表行数 == 接口条数', mediaRows === (mediaApi?.list ?? []).length, `页面 ${mediaRows} / 接口 ${mediaApi?.list?.length ?? 0}`)
    const mediaName = flat(await admin.textContent('[data-testid="media-name"]'))
    check('★ 媒资名渲染正确（与接口首条一致）', mediaName === (mediaApi?.list?.[0]?.filename ?? ''), `页面「${mediaName}」`)
    check('上传控件存在（三步直传的入口）', (await admin.locator('[data-testid="media-upload-btn"]').count()) > 0)
    await admin.screenshot({ path: join(SHOT_DIR, 'm7-8-04-media.png'), fullPage: true })

    // ================================================================
    section('6. AI 运营：知识库/限流数字 == 接口')
    // ================================================================
    await admin.goto(`${ADMIN}/ai-ops`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('[data-testid="aiops-vector-count"]', { timeout: 30000 })
    await admin.waitForTimeout(800)

    const ks = (await adminApi(admin, 'GET', '/ais/admin/knowledge/status')).body?.data
    const shownVec = flat(await admin.textContent('[data-testid="aiops-vector-count"]'))
    check(
      '★★ 页面「向量数」== 接口 vectorCount（★ 它是 Long→字符串，页面必须能正确格式化）',
      shownVec === Number(ks?.vectorCount).toLocaleString('zh-CN'),
      `页面「${shownVec}」/ 接口「${ks?.vectorCount}」`,
    )
    const shownIndex = flat(await admin.textContent('[data-testid="aiops-index-name"]'))
    check('索引名 == 接口', shownIndex === ks?.indexName, `页面「${shownIndex}」`)

    const limitView = (await adminApi(admin, 'GET', '/ais/admin/limit?userId=1')).body?.data
    // 页面上「今日对话」的格式是 `今日对话 X / Y`
    const limitLine = flat(await admin.textContent('.limit-row'))
    check(
      '★ 限流视图数字 == 接口（今日对话 / 并发）',
      limitLine.includes(`今日对话 ${limitView?.usedChats} / ${limitView?.dailyChats}`),
      `页面「${limitLine.slice(0, 80)}」/ 接口 usedChats=${limitView?.usedChats}`,
    )

    const promptsApi = (await adminApi(admin, 'GET', '/ais/chat/prompts')).body?.data ?? {}
    const promptRows = await admin.locator('[data-testid="aiops-prompts"] .el-table__body tbody tr').count()
    check('★ 提示词条数 == 接口', promptRows === Object.keys(promptsApi).length, `页面 ${promptRows} / 接口 ${Object.keys(promptsApi).length}`)

    await admin.screenshot({ path: join(SHOT_DIR, 'm7-8-05-ai-ops.png'), fullPage: true })

    // ================================================================
    section('7. ★★ 权限边界：学员 token 调管理端接口必须被拒')
    // ================================================================
    const portalToken = await loginByApi(
      portal,
      PORTAL,
      '/as/accounts/login',
      STUDENT_PHONE,
      SEED_PASSWORD,
      PORTAL_TOKEN_KEY,
    )
    ok('学员登录成功（用户端）', `token 长度 ${portalToken?.length ?? 0}`)

    const protectedPaths = [
      ['退款审批', 'GET', '/ts/admin/refund-apply/page?pageNo=1&pageSize=1'],
      ['数据概览', 'GET', '/ds/admin/data/overview'],
      ['知识库状态', 'GET', '/ais/admin/knowledge/status'],
      ['AI 限流', 'GET', '/ais/admin/limit?userId=1'],
      ['站内信模板', 'GET', '/sms/admin/notice-templates/page?pageNo=1&pageSize=1'],
      ['问答管理', 'GET', '/ls/admin/questions/page?pageNo=1&pageSize=1&courseId=544'],
      // ---- M7-9 补的四条（#A9 部分修复：fm-media / fm-promotion / fm-exam 已开鉴权）----
      // ★ 挑的都是**没有副作用**的请求：读接口，或"操作一个不存在的 id"
      //   （万一拦截失效，也不会真的删掉/建出东西）。
      //   断言不该改变被测系统的状态 —— 这条约束在这个项目里踩过不止一次。
      ['媒资分页', 'GET', '/ms/medias?pageNo=1&pageSize=1'],
      ['媒资删除（不存在的 id）', 'DELETE', '/ms/medias/999999999'],
      ['券发放（不存在的券）', 'PUT', '/prs/coupons/999999999/issue'],
      ['考核待评分', 'GET', '/es/admin/action-assessments/pending'],
      // ---- M7-11 补的 5 条（#A9 收尾：fm-course 也开鉴权了，这是最后一块）----
      ['课程分页（含**未上架**课程）', 'GET', '/cs/courses/page?pageNo=1&pageSize=1&status=2'],
      ['题目分页（**含答案与解析**）', 'GET', '/cs/subjects/page?pageNo=1&pageSize=1'],
      ['草稿训练目录', 'GET', '/cs/courses/catas/544'],
      ['删课（不存在的 id）', 'DELETE', '/cs/courses/delete/999999999'],
      // ★ 空体 POST：万一拦截失效，也只会因"缺 body"报 400，不会真的建出东西
      ['保存课程（空体）', 'POST', '/cs/courses/baseInfo/save'],
    ]
    let denied = 0
    // ★ 关掉业务错误收集：接下来这批 403 是**刻意**打出来的
    collectErrors = false
    for (const [label, m, p] of protectedPaths) {
      const r = await portalApi(portal, m, p)
      const blocked = r.status === 403 || r.status === 401
      if (blocked) denied += 1
      else console.log(`     [泄漏] ${label} → HTTP ${r.status} code=${r.body?.code}`)
    }
    check(
      // ★ 条数写进模板而**不是硬编码**：改清单时不会再忘记同步这句（曾经就漏过）
      `★★ ${protectedPaths.length} 个管理端接口对学员全部 403（权限由后端把关，不是前端藏菜单）`,
      denied === protectedPaths.length,
      `${denied}/${protectedPaths.length}`,
    )

    /**
     * ★★ #A9 **已全部收口**（M7-11）。
     *
     *   回顾：fm-media / fm-promotion / fm-exam 在 M7-9 补上；
     *   **fm-course 是最后一块**，也是唯一需要**改 SDK** 的一块 ——
     *   它的路径**同形不同权**：
     *       GET  /courses/baseInfo/{id}    ← 学员端课程详情（必须放行）
     *       POST /courses/baseInfo/save    ← 管理端保存（必须拦）
     *   两者同前缀，而 SDK 原来的 pattern 是**路径级**的、区分不了方法。
     *   → M7-10 给 SDK 加了 `METHOD:/path` 支持（fm-media 用来区分
     *     "学员可上传 / 只有运营能删"），这里正好用上。
     *
     *   ★ 上次这里挂的是一条"**已知缺口**"断言（断言它确实能被学员调通）。
     *     那条断言的作用就是"到期提醒"：缺口修好它会失败并提示改成预期 403 ——
     *     现在它派上用场了，于是被替换成上面那 5 条正经的负向断言。
     */

    // ----------------------------------------------------------------
    // ★★ 反向回归：收权最容易犯的错是"把学员端自己的接口也圈进去"
    // ----------------------------------------------------------------
    /**
     * admin 的 pathPatterns 是**路径前缀**匹配，而这三个服务的路径里
     * **混着学员端在用的接口**。划错一条，后果不是"多了个 403 提示"，
     * 而是**学员看不了视频 / 结算页算不出优惠 / 券列表空掉** ——
     * 而这类破坏在**管理端页面上完全看不出来**（管理端全绿，用户端全废）。
     *
     *   1. `/ms/medias/{id}/play-url`      —— portal 播视频要先拿播放地址
     *      （若被圈进 `/medias/**` 就会 403，视频全挂）
     *   2. `/prs/internal/discount/detail` —— 结算页选券后**直接**调它拿优惠额
     *      （它不是纯内部接口，前端在用；若被圈进 `/internal/**` 结算页就废了）
     *   3. `/prs/user-coupons`             —— 学员看自己的券；★ admin 页面的「我的券」也调它
     *      （若被圈进 `/user-coupons/**` 会让管理员自己的券列表 403）
     */
    const mediaList = await adminApi(admin, 'GET', '/ms/medias?pageNo=1&pageSize=1')
    // ★ 不用 flat()：它是 `(s ?? '').replace(...)`，喂 number 会直接抛
    //   （本项目 id 是 Long→String，但"契约上是字符串"不该变成"崩了就怪契约"）
    const firstMedia = mediaList.body?.data?.list?.[0]
    const anyMediaId = firstMedia?.id ? String(firstMedia.id) : ''

    const stillUsable = [
      ['播视频取地址（学员）', 'GET', `/ms/medias/${anyMediaId || 1}/play-url`],
      ['结算页算优惠（学员）', 'GET', '/prs/internal/discount/detail?courseIds=544'],
      ['看自己的券（学员）', 'GET', '/prs/user-coupons'],
      // ---- M7-11 补：fm-course 收权时**刻意不拦**的 4 条学员端读接口 ----
      //   它们和"管理端接口"同前缀，是最容易划错的地方：
      //     · baseInfo/{id}  —— 学员端课程详情（拦了课程详情页直接白屏）
      //     · {id}/catalogs  —— 学员端训练目录
      //     · teachers/{id}  —— 学员端看教练
      //     · categorys/**   —— 分类筛选（数据本身不敏感，读接口一律不拦）
      ['课程详情（学员）', 'GET', '/cs/courses/baseInfo/544'],
      ['训练目录（学员）', 'GET', '/cs/courses/544/catalogs'],
      ['教练信息（学员）', 'GET', '/cs/courses/teachers/544?see=true'],
      ['分类列表（学员）', 'GET', '/cs/categorys/list?level=1&tree=false'],
    ]
    let usable = 0
    for (const [label, m, p] of stillUsable) {
      const r = await portalApi(portal, m, p)
      // ★ 判据只认"**没有被权限拦住**"：这些接口可能因参数不全返回业务错，
      //   但越权拦截一定表现为 401/403 —— 用"HTTP 200"做判据反而会误报。
      const notBlocked = r.status !== 401 && r.status !== 403
      if (notBlocked) usable += 1
      else console.log(`     [收权划错] ${label} → HTTP ${r.status}（学员端功能被打断！）`)
    }
    check(
      `★★ 反向回归：收权后学员端 ${stillUsable.length} 个接口仍然可用（没被 admin pattern 误伤）`,
      usable === stillUsable.length,
      `${usable}/${stillUsable.length} —— 任何一条 403 都说明 pathPatterns 划宽了`,
    )

    // ★ 恢复收集：刻意的负向探测到此结束
    collectErrors = true

    // ================================================================
    section('8. ★★ 用券路径（收掉 #D1）：建券 → 发放 → 领取 → 结算页')
    // ================================================================
    await admin.goto(`${ADMIN}/coupons`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('[data-testid="coupon-name"]', { timeout: 30000 })

    // 用页面默认值（满 100 减 20 / 30 天有效），只改名以便辨认
    const couponName = `M7-8 验收券 ${Date.now().toString().slice(-6)}`
    await admin.fill('[data-testid="coupon-name"]', couponName)
    await admin.click('[data-testid="coupon-submit"]')
    await admin.waitForSelector('[data-testid="coupon-created-card"]', { timeout: 20000 })

    created.couponId = flat(await admin.textContent('[data-testid="coupon-created-card"] .el-tag'))
    check('★ 建券成功（页面出现"刚创建的券"卡片）', !!created.couponId, created.couponId)

    // 发放
    if ((await admin.locator('[data-testid="coupon-issue"]').count()) > 0) {
      await admin.click('[data-testid="coupon-issue"]')
      await admin.waitForSelector('[data-testid="coupon-receive"]', { timeout: 20000 })
      ok('★ 已发放（按钮从"开始发放"变成"自己领一张"）')
    }

    // 学员领券（用接口，避免两个浏览器上下文来回切）
    const couponId = (created.couponId ?? '').replace(/[^0-9]/g, '')
    const recv = await portalApi(portal, 'POST', `/prs/user-coupons/${couponId}/receive`)
    check('★ 学员领取该券成功', recv.status === 200 && recv.body?.code === 200, `HTTP ${recv.status} code=${recv.body?.code} ${recv.body?.msg ?? ''}`)

    const myCoupons = (await portalApi(portal, 'GET', '/prs/user-coupons')).body?.data ?? []
    const mine = myCoupons.find((c) => String(c.couponId) === String(couponId))
    check('★ 券出现在"我的券"里且有规则文案', !!mine, mine ? `${mine.name}｜${mine.rule}` : '未找到')

    // ---- 去结算页用它 ----
    // 找一门**够门槛**的付费课（满 100 元 = 10000 分）
    const portalCourses = (await portalApi(portal, 'GET', '/ss/courses/portal?pageNo=1&pageSize=50')).body?.data?.list ?? []
    const paid = portalCourses.find((c) => Number(c.price) >= 10000)
    check('★ 找到一门够券门槛的付费课（用于验证券真的能用）', !!paid, paid ? `${paid.name}（${Number(paid.price) / 100} 元）` : '未找到 ≥100 元的课')

    if (paid && mine) {
      // 加购 → 进结算页
      await portalApi(portal, 'DELETE', '/ts/carts')
      await portalApi(portal, 'POST', '/ts/carts', { courseId: paid.id })
      await portal.goto(`${PORTAL}/checkout?courseIds=${paid.id}`, { waitUntil: 'domcontentloaded', timeout: 60000 })

      /**
       * ★★ 必须先等页面**真正渲染出内容**再去数元素。
       *
       *   第一版在 `goto` 之后**立刻就数**，得到 `group=0 empty=0` ——
       *   看起来像"券区没渲染"，于是我去查了结算页的模板、查了接口……
       *   最后写诊断脚本打开同一个 URL 一看：券区好好地在那儿（2 张券）。
       *   根因只是**时机**：`domcontentloaded` 时 Vue 还没挂载完，
       *   而整页内容都在 `v-if="!loading"` 之后。
       *
       *   教训与 M7-3 的"异步断言必须轮询"完全同源 ——
       *   只不过这次的"异步"不是后端，而是**前端自己**。
       */
      await waitFor(
        async () => {
          const n =
            (await portal.locator('[data-testid="checkout-coupon-group"]').count()) +
            (await portal.locator('[data-testid="checkout-coupon-empty"]').count())
          return n > 0 ? true : null
        },
        { label: '结算页券区渲染', timeoutMs: 30000 },
      )

      // ★ 券区要么有复选框组，要么空态；这里必须**不是空态**（我们刚领了券）
      const groupCount = await portal.locator('[data-testid="checkout-coupon-group"]').count()
      const emptyCount = await portal.locator('[data-testid="checkout-coupon-empty"]').count()
      check('★★ 结算页出现了可用券（不再是"暂无可用优惠券"）', groupCount > 0 && emptyCount === 0, `group=${groupCount} empty=${emptyCount}`)

      if (groupCount > 0) {
        // 勾选第一张券 → 页面会去调促销服务算优惠
        await portal.locator('[data-testid="checkout-coupon-group"] .el-checkbox').first().click()
        await waitFor(
          async () => ((await portal.locator('[data-testid="checkout-coupon-rules"]').count()) > 0 ? true : null),
          { label: '优惠规则回显', timeoutMs: 20000 },
        )
        const discountText = flat(await portal.textContent('[data-testid="checkout-discount"]'))
        const payableText = flat(await portal.textContent('[data-testid="checkout-payable"]'))
        const totalText = flat(await portal.textContent('[data-testid="checkout-total"]'))

        // 与促销服务算出来的值对照（★ 页面不自己算钱，这里验的就是这件事）
        const detail = (
          await portalApi(
            portal,
            'GET',
            `/prs/internal/discount/detail?userId=1&courseIds=${paid.id}&prices=${paid.price}&userCouponIds=${mine.id}`,
          )
        ).body?.data
        const expectDiscount = detail?.discountAmount ?? 0

        check(
          '★★ 页面「优惠」金额 == 促销服务算出的 discountAmount',
          discountText === `-¥${(Number(expectDiscount) / 100).toFixed(2)}` || discountText === `¥${(Number(expectDiscount) / 100).toFixed(2)}`,
          `页面「${discountText}」/ 接口 ${expectDiscount} 分`,
        )
        check('★★ 优惠额 > 0（券真的生效了，不是"选了但没减"）', Number(expectDiscount) > 0, `${expectDiscount} 分`)
        check('★ 应付 = 合计 - 优惠（页面口径自洽）', payableText === `¥${(Number(paid.price) - Number(expectDiscount)) / 100}`.replace(/\.0$/, '.00') || payableText.includes((( Number(paid.price) - Number(expectDiscount)) / 100).toFixed(2)), `合计「${totalText}」优惠「${discountText}」应付「${payableText}」`)
        await portal.screenshot({ path: join(SHOT_DIR, 'm7-8-06-portal-coupon.png'), fullPage: true })
      }
      // ★ 收拾现场：把购物车清掉，别给后面的脚本留"购物车里已经有东西"
      await portalApi(portal, 'DELETE', '/ts/carts')
    }

    // ================================================================
    section('9. ★ 故障注入：开关生效 + 立即复位')
    // ================================================================
    // ★ 必须先导航回 AI 运营页 —— 第 8 节把 admin 页面停在了优惠券页，
    //   不回来就会"找不到按钮"（第一版就是这样：报"可能已在开启态"，
    //   实际是**根本没在这一页**）
    await admin.goto(`${ADMIN}/ai-ops`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await admin.waitForSelector('[data-testid="aiops-vector-count"]', { timeout: 30000 })
    collectErrors = false
    if ((await admin.locator('[data-testid="aiops-failmode-on"]').count()) > 0) {
      // 页面上的"开启"按钮会弹二次确认
      await admin.click('[data-testid="aiops-failmode-on"]')
      await admin.waitForTimeout(800)
      const confirmBtn = admin.locator('.el-message-box__btns .el-button--primary')
      if ((await confirmBtn.count()) > 0) await confirmBtn.first().click()
      await waitFor(async () => ((await admin.locator('[data-testid="aiops-failmode-alert"]').count()) > 0 ? true : null), {
        label: '故障注入告警条出现',
        timeoutMs: 15000,
      })
      failModeTurnedOn = true
      ok('★ 开启故障注入后页面顶部出现常驻红色告警')

      // 用接口确认后端真的开了（页面状态不可查，所以这里直接打一次已知的"开"）
      const off = await adminApi(admin, 'POST', '/ais/admin/knowledge/fail-mode?enabled=false')
      check('★ 后端"恢复正常"接口可用', off.status === 200 && off.body?.data?.simulateFailure === false, JSON.stringify(off.body?.data))
      failModeTurnedOn = false
      ok('★ 已立即复位为正常（不留故障态给后续）')
    } else {
      console.log('     ⚠️ 页面上找不到"开启故障注入"按钮（可能已在开启态）→ 直接复位')
      await adminApi(admin, 'POST', '/ais/admin/knowledge/fail-mode?enabled=false')
      ok('已执行复位')
    }
    collectErrors = true

    // ================================================================
    section('10. 浏览器运行时健康度')
    // ================================================================
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
    /**
     * ★★ 这条是 M7-4 建立、之后每期都在用的：业务码非 200 **既不产生 pageerror
     *   也不产生 console error**，只弹一条 ElMessage ——
     *   所以"页面没报错"完全不能说明"用户没看到红条"。
     */
    check(
      '★★ 全程没有「业务码非 200」的响应（这类错误不会进 pageerror/console）',
      businessErrors.length === 0,
      businessErrors.slice(0, 5).join(' | ') || `共 ${businessErrors.length} 条`,
    )
  } finally {
    // ★ 无条件复位：故障注入是全局开关，脚本异常退出也必须关掉
    if (failModeTurnedOn) {
      try {
        await adminApi(admin, 'POST', '/ais/admin/knowledge/fail-mode?enabled=false')
        console.log('\n  🔧 finally：已强制复位故障注入')
      } catch {
        console.log('\n  ⚠️ finally：复位失败，请手动调 POST /ais/admin/knowledge/fail-mode?enabled=false')
      }
    }
    await browser.close()
  }

  // ================================================================
  console.log(`\n${'='.repeat(74)}`)
  console.log(` M7-8 验收结果：${passed} 通过 / ${failed} 失败`)
  if (created.couponId) console.log(` （本次创建了券 ${created.couponId} —— 券没有删除接口，会留在库里）`)
  if (failures.length) {
    console.log('\n 失败项：')
    for (const f of failures) console.log(`   · ${f}`)
  }
  console.log('='.repeat(74))

  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('\n验收脚本异常终止：', e)
  process.exit(2)
})
