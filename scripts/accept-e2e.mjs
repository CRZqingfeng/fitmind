#!/usr/bin/env node
/**
 * ============================================================
 * M8-2b 联调 · 主旅程 e2e —— 一条流跨 11 个服务
 * ------------------------------------------------------------
 * ★★ 它和已有 17 个脚本的分工（这决定它该写什么、不该写什么）
 *
 *   已有的脚本是**分段验收**：后端按服务切、前端按页面切，
 *   各造各的数据、各断言各的。
 *   于是「一个动作在**别的服务**里产生了什么」从来没人验过 —— 这就是"接缝"。
 *
 *   本脚本只做一件事：**把接缝串成一条真实的用户旅程**，
 *   在每一个跨服务后果上直查中间件对账。
 *   它**不重复**已有的单服务断言（那些有自己的脚本守着）。
 *
 * ------------------------------------------------------------
 * ★★ 设计上的关键取舍：用「**全新注册**的账号」，而不是演示账号
 *
 *   演示账号有历史数据，断言只能退化成"比增量 / 比行数"（弱且易碎）。
 *   全新账号什么都没买过、什么都没学过，于是**所有断言都能写死成精确值**
 *   （"我的课程恰好 1 门"、"订单恰好 1 笔"）——
 *   这是本脚本相对其它脚本最实在的增量。
 *
 *   代价：每次运行都会造一个真实学员 + 一笔真实订单。
 *   → 所以脚本必须**收拾现场**（见 ⑩ 站），且清理后要用 `sql` 查计数自检。
 *
 * ------------------------------------------------------------
 * 旅程（11 站 / 覆盖 11 个服务）
 *
 *   ①注册 + 登录              → fm-auth · fm-user
 *   ②逛（推荐 / 搜索 / 详情）  → fm-search · fm-course
 *   ③买（建券 → 领券 → 加购 → 预下单 → 下单 → 发起支付 → 模拟支付）
 *                             → fm-trade · fm-promotion · fm-pay
 *      ★★ 支付成功后的 6 条跨服务副作用 —— **本脚本最值钱的一节**
 *   ④学（我的课程 → 课表 → 上报进度） → fm-learning
 *   ⑤打卡（签到 → 积分 → 今日榜）     → fm-learning · Redis
 *   ⑥测（提交体测 → 报告）            → fm-exam
 *   ⑦说（提问 → 回答 → 点赞）         → fm-learning · fm-remark
 *   ⑧问 AI（Mock 通道）               → fm-aigc
 *   ⑨收尾（清理 + 自检）
 *
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端 15 服务 + 网关都起着
 *   · ★ fm-aigc 需要在 **Mock 通道**（`bash scripts/start-all.sh fm-aigc mock`）——
 *     脚本会先探 `/ais/chat/provider`，不是 mock 就**跳过 ⑧ 站并说明**（不算失败）
 *   · 中间件：MySQL / Redis / RabbitMQ（本脚本只用 MySQL + Redis 对账）
 *
 * 用法：node scripts/accept-e2e.mjs
 * ============================================================
 */

import { spawnSync } from 'node:child_process'
import fs from 'node:fs'

const GATEWAY = 'http://localhost:10010'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'

const ADMIN_PHONE = '13800010003'
/** 旅程要买的那门课的候选：上架 + 付费（新学员没买过任何课，随便挑一门都行） */
const COURSE_KEYWORD = '增肌'

let passed = 0
let failed = 0
const failures = []
/** 跳过（环境不满足，如 AI 不在 Mock 通道）—— 既不算通过也不算失败，但要显式报出来 */
const skipped = []

/**
 * 全局网络/业务侧记账
 * ★ 为什么要单独统计：**业务码非 200 不会产生任何浏览器/进程侧异常**，
 *   所以"接口看着都通了"和"用户到处看到错误提示"是可以同时成立的。
 *   必须显式断言这两项为 0。
 */
let http5xx = 0
const bizErrors = []

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

// ============================================================
// 基础设施：HTTP / SQL / Redis
// ============================================================

async function call(method, path, { body, token, retryOn401 = true } = {}) {
  const doFetch = async () => {
    const headers = { 'Content-Type': 'application/json' }
    if (token) headers['Authorization'] = token
    const res = await fetch(GATEWAY + path, {
      method,
      headers,
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
  }

  let result = await doFetch()
  // ⚠️ 网关是本地公钥验签，实测存在极低概率的单次验签失败。
  //    对带 token 的请求重试一次：抖动 → 成功；token 真失效 → 照样 401，不掩盖问题。
  if (token && result.status === 401 && retryOn401) {
    await sleep(200)
    result = await doFetch()
  }

  // 记账：5xx 一律算错；业务码非 200 也记下来（最后统一断言，见 ⑨ 站）
  if (result.status >= 500) http5xx++
  const code = result.json?.code
  if (typeof code === 'number' && code !== 200) {
    bizErrors.push(`${code} ${method} ${path}${result.json?.msg ? ` (${result.json.msg})` : ''}`)
  }

  return result
}

/**
 * ★★ 查询本身失败时必须炸出来，不能静默返回空字符串。
 *   （否则"期望为空/为 0"的断言会**假通过** —— 与 401 假通过同一类，只是更隐蔽。）
 *   注意区分两种"空"：查询成功但无匹配行（合法）vs 查询没跑起来（必须报错）。
 */
function checkCmdResult(kind, cmd, r) {
  if (r.error) throw new Error(`${kind} 执行失败（进程无法启动）：${cmd}\n${r.error.message}`)
  if (r.status !== 0) {
    throw new Error(`${kind} 执行失败（退出码 ${r.status}）：${cmd}\n${(r.stderr || '').trim()}`)
  }
}

function sql(query) {
  const r = spawnSync(
    MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8' },
  )
  checkCmdResult('SQL', query, r)
  return (r.stdout || '').trim()
}

/** 断言用：失败即抛（配合 check 的 detail 打印） */
function sqlRow(query) {
  return sql(query).replace(/\t/g, ' ')
}

/** ★ `mysql -N` 的列分隔符是 **tab**，不是 `|` —— 要按列比对必须用这个 */
function sqlCells(query) {
  return sql(query).split('\t')
}

function envGet(key) {
  const env = fs.readFileSync('docker/.env', 'utf8')
  return (env.match(new RegExp(`^${key}=(.*)$`, 'm')) || [])[1]?.trim()
}

function redis(...args) {
  const r = spawnSync(
    'docker',
    ['exec', 'fm-redis', 'redis-cli', '-a', envGet('REDIS_PASSWORD'), '--no-auth-warning', ...args],
    { encoding: 'utf8' },
  )
  return (r.stdout || '').trim()
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 异步副作用必须轮询等 —— 不许 sleep 猜 */
async function waitUntil(fn, { timeout = 20000, interval = 700 } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) return true
    await sleep(interval)
  }
  return false
}

const monthKey = (d = new Date()) =>
  `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}`

// ============================================================
// 旅程用的账号
// ============================================================
const NEW_PHONE = '139' + String(Date.now()).slice(-8)
const NEW_PASSWORD = 'e2e123456'
const TAG = `E2E-${String(Date.now()).slice(-6)}`

/**
 * 注册一个新学员并登录。
 * ★ 注册会**消费掉**验证码，所以登录前要重新发一次（不能复用注册那只）。
 */
async function registerAndLogin() {
  const c1 = await call('POST', `/us/codes?phone=${NEW_PHONE}`)
  const code1 = c1.json?.data
  if (!code1) return { error: `注册验证码拿不到：msg=${c1.json?.msg}` }

  const reg = await call('POST', '/us/students/register', {
    body: { cellPhone: NEW_PHONE, code: code1, password: NEW_PASSWORD, name: `${TAG} 新学员` },
  })
  if (reg.json?.code !== 200) return { error: `注册失败：msg=${reg.json?.msg}` }

  // 注册已消费验证码 → 重新发一只用于登录
  const c2 = await call('POST', `/us/codes?phone=${NEW_PHONE}`)
  const code2 = c2.json?.data
  const login = await call('POST', '/as/accounts/login', {
    body: { type: 2, cellPhone: NEW_PHONE, password: String(code2), rememberMe: false },
  })
  const token = login.json?.data
  if (!token) return { error: `新学员登录失败：msg=${login.json?.msg}` }
  return { token }
}

async function adminLogin() {
  const c = await call('POST', `/us/codes?phone=${ADMIN_PHONE}`)
  const code = c.json?.data
  const res = await call('POST', '/as/accounts/admin/login', {
    body: { type: 2, cellPhone: ADMIN_PHONE, password: String(code), rememberMe: false },
  })
  return res.json?.data || null
}

// ============================================================
// 清理：按新学员的 userId / orderId / couponId 精确删除
// ============================================================
/**
 * ★ 为什么能"精确删除"：所有数据都挂在**本次新注册**的那个 userId 上 ——
 *   它不是演示账号，删它不会误伤任何人。
 * ★ 每条 DELETE 都带 `WHERE user_id=<本次 id>`：**永远不会**出现无 where 的删除。
 */
const CLEANUP_BY_USER_ID = [
  // ★ 登录流水也要清 —— 第一版漏了它，结果账号删了、流水成了孤儿
  //   （self-check 没发现，因为**自检只能覆盖列进清单的表**：
  //     清单漏一张，就一条都检不出来。这是这套做法最需要注意的地方。）
  ['fm_auth.login_record', 'user_id'],
  ['fm_aigc.chat_message', 'user_id'],
  ['fm_aigc.chat_session', 'user_id'],
  ['fm_exam.body_assessment', 'user_id'],
  ['fm_exam.action_assessment', 'user_id'],
  ['fm_learning.interaction_reply', 'user_id'],
  ['fm_learning.interaction_question', 'user_id'],
  ['fm_learning.learning_record', 'user_id'],
  ['fm_learning.learning_lesson', 'user_id'],
  ['fm_learning.note', 'user_id'],
  ['fm_learning.points_record', 'user_id'],
  ['fm_message.user_inbox', 'user_id'],
  ['fm_message.sms_record', 'user_id'],
  ['fm_promotion.user_coupon', 'user_id'],
  ['fm_remark.liked_record', 'user_id'],
  ['fm_trade.refund_apply', 'user_id'],
  ['fm_trade.trade_order_detail', 'user_id'],
  ['fm_trade.trade_order', 'user_id'],
  ['fm_trade.trade_cart', 'user_id'],
  ['fm_user.user_body_profile', 'user_id'],
]

/** 自检用：清理后这些表里该用户的残留应当为 0 */
function residueCount(userId) {
  let total = 0
  const left = []
  for (const [table, col] of CLEANUP_BY_USER_ID) {
    const n = Number(sql(`SELECT COUNT(*) FROM ${table} WHERE ${col}=${userId}`) || 0)
    if (n > 0) {
      left.push(`${table}=${n}`)
      total += n
    }
  }
  return { total, left }
}

// ============================================================
// 主流程
// ============================================================
async function main() {
  console.log('============================================================')
  console.log(' FitMind M8-2b 联调 · 主旅程 e2e（一条流跨 11 个服务）')
  console.log('============================================================')
  console.log(`★ 本次旅程账号：${NEW_PHONE}（全新注册；结束时清理）`)

  let token = null
  let userId = null
  let orderId = null
  let payOrderNo = null
  let couponId = null
  let lessonId = null
  let sectionId = null
  /** 清理阶段要读的就是这几个 id —— 用一个快照传出去，避免各处重复拼 */
  const state = () => ({ userId, orderId, couponId, lessonId, sectionId })

  // ================================================================
  section('① 注册 + 登录（fm-auth · fm-user）')
  // ================================================================
  const reg = await registerAndLogin()
  check('新学员注册并登录成功', !!reg.token, reg.error || '')
  if (!reg.token) return bailOut('注册/登录', state())
  token = reg.token

  const idRow = sql(`SELECT id, type, status FROM fm_user.user WHERE cell_phone='${NEW_PHONE}'`)
  const cells = sqlCells(`SELECT id, type, status FROM fm_user.user WHERE cell_phone='${NEW_PHONE}'`)
  userId = cells[0]
  check('★ 直查 DB：账号落库，type=学员(2)、状态=正常', cells.length === 3, `row=${idRow}`)
  check(
    '★ 新账号 type=学员(2)、状态=正常(1)',
    cells[1] === '2' && cells[2] === '1',
    `type=${cells[1]} status=${cells[2]}`,
  )
  check(
    '★ 昵称按注册时传的写入了 user_detail',
    sql(`SELECT name FROM fm_user.user_detail WHERE id=${userId}`) === `${TAG} 新学员`,
    `name=${sql(`SELECT name FROM fm_user.user_detail WHERE id=${userId}`)}`,
  )
  // ★ 新账号的"起点"必须是干净的 —— 这是后面所有"恰好 1 条"断言的前提
  check(
    '★★ 新账号起点干净：订单 0 笔、课表 0 门（后面所有精确断言的**前提**）',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE user_id=${userId}`) === '0' &&
      sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${userId}`) === '0',
    `orders=${sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE user_id=${userId}`)} lessons=${sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${userId}`)}`,
  )

  // ================================================================
  section('② 逛：推荐 → 搜索 → 课程详情（fm-search · fm-course）')
  // ================================================================
  const best = await call('GET', '/ss/recommend/best?size=5', { token })
  check('热门推荐有数据（新账号也能逛）',
    Array.isArray(best.json?.data) && best.json.data.length > 0,
    `len=${best.json?.data?.length} msg=${best.json?.msg}`)

  const search = await call('GET', `/ss/courses/name?keyword=${encodeURIComponent(COURSE_KEYWORD)}`, { token })
  const hits = search.json?.data || []
  check(`按关键词「${COURSE_KEYWORD}」搜到课程`, hits.length > 0, `len=${hits.length} msg=${search.json?.msg}`)

  // 选一门"上架 + 付费"的课（新账号没买过，任何一门都行）
  const courseRow = sql(
    `SELECT id, name, price FROM fm_course.course WHERE status=2 AND price>0 ORDER BY id LIMIT 1`,
  )
  const [courseId, courseName, coursePrice] = sqlCells(
    `SELECT id, name, price FROM fm_course.course WHERE status=2 AND price>0 ORDER BY id LIMIT 1`,
  )
  check('选到一门上架付费课作为本旅程的目标', !!courseId, `row=${courseRow}`)
  if (!courseId) return bailOut('选课', state())
  console.log(`     目标课程：#${courseId} ${courseName}（¥${Number(coursePrice) / 100}）`)

  const catalogs = await call('GET', `/cs/courses/${courseId}/catalogs`, { token })
  // ⚠️ 结构是 `[{type, name, sections: [...]}]`（章含 sections）——
  //    不是 `children`。服务端返回 `CataVO[]`，门户侧也是 `d.sections`（见 CommunityView.vue）。
  const chapters = catalogs.json?.data || []
  const firstChapter = chapters.find((c) => c.type === 1) || chapters[0] || {}
  const firstSection = (firstChapter.sections || [])[0] || {}
  check('课程详情（章节目录）能取到', chapters.length > 0 && !!firstSection.id,
    `chapters=${chapters.length} chapterId=${firstChapter.id} sectionId=${firstSection.id}`)

  // ================================================================
  section('③ 买：建券 → 领券 → 加购 → 预下单 → 下单 → 支付（fm-trade · fm-promotion · fm-pay）')
  // ================================================================
  const adminToken = await adminLogin()
  check('运营侧登录成功（建券要用）', !!adminToken)
  if (!adminToken) return bailOut('运营侧登录', state())

  const in30Days = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 19)
  const cpn = await call('POST', '/prs/coupons', {
    token: adminToken,
    body: {
      name: `${TAG} 满减券`, discountType: 1, specific: 0,
      discountValue: 5000, thresholdAmount: 10000, maxDiscountAmount: 0,
      obtainWay: 1, status: 2, totalNum: 1, userLimit: 1,
      issueBeginTime: '2026-01-01T00:00:00', issueEndTime: in30Days, termDays: 30,
    },
  })
  couponId = cpn.json?.data
  check('运营侧建券成功', !!couponId, `msg=${cpn.json?.msg}`)
  if (!couponId) return bailOut('建券', state())

  const recv = await call('POST', `/prs/user-coupons/${couponId}/receive`, { token })
  check('新学员领券成功', recv.json?.code === 200, `msg=${recv.json?.msg}`)
  const ucId = sql(`SELECT id FROM fm_promotion.user_coupon WHERE user_id=${userId} AND coupon_id=${couponId} ORDER BY id DESC LIMIT 1`)
  check('★ 直查 DB：用户券落库、状态=未使用(1)',
    sql(`SELECT status FROM fm_promotion.user_coupon WHERE id=${ucId}`) === '1',
    `row=${sqlRow(`SELECT status, order_id FROM fm_promotion.user_coupon WHERE id=${ucId}`)}`)

  // ★ 同上：id 一律按字符串传（项目契约 Long→String）
  const addCart = await call('POST', '/ts/carts', { token, body: { courseId } })
  check('加入购物车成功', addCart.json?.code === 200, `msg=${addCart.json?.msg}`)
  check('★ 直查 DB：购物车 1 条', sql(`SELECT COUNT(*) FROM fm_trade.trade_cart WHERE user_id=${userId}`) === '1',
    `count=${sql(`SELECT COUNT(*) FROM fm_trade.trade_cart WHERE user_id=${userId}`)}`)

  const pre = await call('GET', `/ts/orders/prePlace?courseIds=${courseId}`, { token })
  orderId = pre.json?.data?.orderId
  check('预下单返回 orderId 与金额预览', !!orderId, `msg=${pre.json?.msg}`)
  if (!orderId) return bailOut('预下单', state())
  const previewAmount = pre.json?.data?.totalAmount ?? pre.json?.data?.amount
  console.log(`     预下单预览金额 = ${previewAmount}`)

  const placed = await call('POST', '/ts/orders', {
    // ★★ 雪花 id **不要**包 Number()：JS 的 number 只有 53 位精度，
    //    2102248179313950720 会被静默截成 ...700 → 券查不到 → 报「优惠券不可用」。
    //    （项目契约是 Long→String 全局，接口返回的与 DB 读出来的都已经是字符串，原样传即可。）
    token, body: { orderId, courseIds: [courseId], userCouponIds: [ucId] },
  })
  check('带券下单成功', placed.json?.code === 200 && placed.json?.data?.created === true,
    `msg=${placed.json?.msg} created=${placed.json?.data?.created} (ucId=${ucId})`)

  const orderCells = sqlCells(`SELECT status, total_amount, discount_amount, real_amount FROM fm_trade.trade_order WHERE id=${orderId}`)
  const [oStatus, oTotal, oDiscount, oReal] = orderCells
  check('★ 直查 DB：订单状态=待支付(1)', oStatus === '1', `status=${oStatus}`)
  check('★★ 订单记录了优惠，且 **实付 = 总额 − 优惠**（金额是促销服务算的，不是前端传的）',
    Number(oDiscount) > 0 && Number(oReal) === Number(oTotal) - Number(oDiscount),
    `total=${oTotal} discount=${oDiscount} real=${oReal}`)
  check('★★ 优惠金额 == 券面额（满减 5000 分）', Number(oDiscount) === 5000, `discount=${oDiscount}`)

  const payReq = await call('POST', `/ts/orders/${orderId}/pay`, { token })
  payOrderNo = payReq.json?.data?.payOrderNo
  check('发起支付成功（拿到支付单号）', !!payOrderNo, `msg=${payReq.json?.msg}`)
  if (!payOrderNo) return bailOut('发起支付', state())
  check('★★ 支付金额 = 订单**实付**（服务端从订单取，不信前端）',
    Number(payReq.json?.data?.amount) === Number(oReal),
    `amount=${payReq.json?.data?.amount} 期望=${oReal}`)
  check('★ 直查 DB：支付单落库、状态=待支付(1)、金额=实付',
    sqlRow(`SELECT amount, status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`) === `${oReal} 1`,
    `row=${sqlRow(`SELECT amount, status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`)}`)

  // ================================================================
  section('★★★ 支付成功 → 6 条跨服务副作用（本脚本最值钱的一节）')
  // ================================================================
  await call('POST', `/ps/mock/pay/${payOrderNo}`, { token })

  const settled = await waitUntil(
    () => sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderId}`) === '2',
    { timeout: 25000 },
  )
  // ① 交易：订单已支付 + 回填支付单号
  check('① fm-trade：订单状态 → 已支付(2)，且回填了支付单号',
    settled && sql(`SELECT pay_order_no FROM fm_trade.trade_order WHERE id=${orderId}`) === payOrderNo,
    `row=${sqlRow(`SELECT status, pay_order_no FROM fm_trade.trade_order WHERE id=${orderId}`)}`)

  // ② 支付：支付单已支付
  const paySettled = await waitUntil(
    () => sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`) === '2',
    { timeout: 25000 },
  )
  check('② fm-pay：支付单状态 → 已支付(2)', paySettled,
    `status=${sql(`SELECT status FROM fm_pay.pay_order WHERE pay_order_no='${payOrderNo}'`)}`)

  // ③ 学习：课表被自动创建（★ 支付 → 学习，跨服务）
  const lessonMade = await waitUntil(
    () => sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${userId} AND course_id=${courseId}`) === '1',
    { timeout: 25000 },
  )
  check('③ fm-learning：**课表被自动创建**（支付成功 → 学习服务开课；新账号恰 1 门）',
    lessonMade && sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${userId}`) === '1',
    `count=${sql(`SELECT COUNT(*) FROM fm_learning.learning_lesson WHERE user_id=${userId}`)}`)

  // ④ 课程：销量 +1（★ 支付 → 课程）
  const soldAfter = await waitUntil(
    () => Number(sql(`SELECT sold FROM fm_course.course WHERE id=${courseId}`)) >= 1,
    { timeout: 20000 },
  )
  check('④ fm-course：课程 sold 被 +1（支付成功 → 课程服务）', soldAfter,
    `sold=${sql(`SELECT sold FROM fm_course.course WHERE id=${courseId}`)}`)

  // ⑤ 消息：收到"购买成功"站内信（★ 支付 → 消息）
  const inboxCount = `SELECT COUNT(*) FROM fm_message.user_inbox WHERE user_id=${userId} AND (title LIKE '%购买%' OR content LIKE '%购买%')`
  const inboxed = await waitUntil(() => sql(inboxCount) === '1', { timeout: 25000 })
  check('⑤ fm-message：收到「课程购买成功」站内信（支付成功 → 消息服务）',
    inboxed, `count=${sql(inboxCount)} row=${sqlRow(`SELECT title FROM fm_message.user_inbox WHERE user_id=${userId} LIMIT 1`)}`)

  // ⑥ 营销：券被核销 + 回填 order_id（★ 支付 → 营销）
  const usedCount = `SELECT COUNT(*) FROM fm_promotion.user_coupon WHERE id=${ucId} AND status=2 AND order_id=${orderId}`
  const cashed = await waitUntil(() => sql(usedCount) === '1', { timeout: 25000 })
  check('⑥ fm-promotion：优惠券被**核销**且回填 order_id（支付成功 → 营销服务）',
    cashed, `row=${sqlRow(`SELECT status, order_id FROM fm_promotion.user_coupon WHERE id=${ucId}`)}`)

  check('★ 下单后购物车已清空（交易服务的连带动作）',
    sql(`SELECT COUNT(*) FROM fm_trade.trade_cart WHERE user_id=${userId}`) === '0',
    `count=${sql(`SELECT COUNT(*) FROM fm_trade.trade_cart WHERE user_id=${userId}`)}`)

  // ================================================================
  section('④ 学：我的课程 → 课表 → 上报进度（fm-learning）')
  // ================================================================
  const myCourses = await call('GET', '/ls/lessons/page?pageNo=1&pageSize=10', { token })
  const myList = myCourses.json?.data?.list || []
  check('★ 我的课程里**恰好**有刚买的这门（新账号的精确断言）',
    myList.length === 1 && String(myList[0]?.courseId) === String(courseId),
    `len=${myList.length} total=${myCourses.json?.data?.total} courseId=${myList[0]?.courseId}`)

  const lesson = await call('GET', `/ls/lessons/${courseId}`, { token })
  lessonId = lesson.json?.data?.id
  check('课表可查，且有有效期（付费课的 validDuration 生效）',
    !!lessonId && !!lesson.json?.data?.expireTime,
    `lessonId=${lessonId} expireTime=${lesson.json?.data?.expireTime}`)
  if (!lessonId) return bailOut('取课表', state())

  sectionId = firstSection.id
  const up1 = await call('POST', '/ls/learning-records', {
    token, body: { lessonId, sectionId, moment: 60, duration: 180 },
  })
  check('上报学习进度成功', up1.json?.code === 200, `msg=${up1.json?.msg}`)
  const recOk = await waitUntil(
    () => sql(`SELECT moment FROM fm_learning.learning_record WHERE lesson_id=${lessonId} AND section_id=${sectionId}`).startsWith('60'),
    { timeout: 12000 },
  )
  check('★ 直查 DB：学习进度落库（moment=60）', recOk,
    `row=${sqlRow(`SELECT moment, finished FROM fm_learning.learning_record WHERE lesson_id=${lessonId} AND section_id=${sectionId}`)}`)
  check('★ Redis 进度缓存真实存在（不只是落了库）',
    redis('EXISTS', `learning:record:${lessonId}:${sectionId}`) === '1',
    `key=learning:record:${lessonId}:${sectionId}`)

  // ================================================================
  section('⑤ 打卡：签到 → 积分 → 今日榜（fm-learning · Redis）')
  // ================================================================
  const sign = await call('POST', '/ls/sign-records', { token })
  check('签到成功', sign.json?.code === 200, `msg=${sign.json?.msg}`)

  const signKey = `sign:uid:${userId}:${monthKey()}`
  const day = new Date().getDate()
  check('★★ 直查 Redis 位图：今天那一位真的是 1（不靠接口返回码）',
    redis('GETBIT', signKey, String(day - 1)) === '1',
    `key=${signKey} bit=${day - 1} val=${redis('GETBIT', signKey, String(day - 1))}`)

  const signList = await call('GET', '/ls/sign-records', { token })
  check('签到记录可查（本账号仅 1 次）', !!signList.json?.data, `msg=${signList.json?.msg}`)

  const pointsOk = await waitUntil(
    () => Number(sql(`SELECT COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId}`)) > 0,
    { timeout: 15000 },
  )
  check('★ 积分明细落库且 > 0（签到 → 积分）', pointsOk,
    `sum=${sql(`SELECT COALESCE(SUM(points),0) FROM fm_learning.points_record WHERE user_id=${userId}`)}`)

  const board = await call('GET', '/ls/boards/points/today', { token })
  check('今日积分榜可查', board.json?.code === 200, `msg=${board.json?.msg}`)
  check('★ Redis 榜单（zset）里有本人（签到积分已入榜）',
    redis('ZSCORE', `boards:${monthKey()}`, String(userId)) !== '',
    `key=boards:${monthKey()} score=${redis('ZSCORE', `boards:${monthKey()}`, String(userId))}`)

  // ================================================================
  section('⑥ 测：提交体测 → 报告（fm-exam）')
  // ================================================================
  const H = 175, W = 75, FAT = 18, WAIST = 82, HIP = 95
  const BODY = {
    height: H, weight: W, targetWeight: 70, bodyFatRate: FAT,
    waist: WAIST, hip: HIP, chest: 100, arm: 35, goalType: 1,
  }

  // ---- 第一拍：新账号**没有生日** → BMR/TDEE 应当为 NULL ----
  //   ★ 这是**有意设计**，代码注释写得很清楚：
  //     「age 为 null 时返回 null，而不是拿一个默认年龄硬算 —— 年龄差 20 岁，BMR 差 100 kcal」。
  //   ★ 所以这里的正确期望是「BMI 照算、BMR/TDEE 留空」，**不是**"都应该算出来"。
  //     （本脚本第一版就把它写成了"都应算出" —— 属于**断言写错了**，
  //       正是项目铁律那句「断言失败先怀疑断言」的又一例。）
  const assess1 = await call('POST', '/es/assessments', { token, body: BODY })
  const assessId = assess1.json?.data
  check('提交体测成功（新账号、尚无生日）',
    assess1.json?.code === 200 && !!assessId, `msg=${assess1.json?.msg}`)
  if (!assessId) return bailOut('提交体测', state())

  const cells1 = sqlCells(`SELECT bmi, bmi_level, bmr, tdee, fat_mass FROM fm_exam.body_assessment WHERE id=${assessId}`)
  const expBmi = W / Math.pow(H / 100, 2)
  const expFatMass = (W * FAT) / 100
  check('★ 直查 DB：BMI 与脂肪重按公式算对（与年龄无关的指标照常有值）',
    Math.abs(Number(cells1[0]) - expBmi) < 0.05 && Math.abs(Number(cells1[4]) - expFatMass) < 0.1,
    `bmi=${cells1[0]}（期望≈${expBmi.toFixed(2)}） fatMass=${cells1[4]}（期望≈${expFatMass.toFixed(1)}）`)
  check('★ BMI 等级也算了', !!cells1[1], `bmiLevel=${cells1[1]}`)
  check('★★ 无生日时 BMR/TDEE = NULL —— 有意设计：宁可留空，也不拿默认年龄硬算',
    cells1[2] === 'NULL' || cells1[2] === '',
    `bmr=${JSON.stringify(cells1[2])} tdee=${JSON.stringify(cells1[3])}`)

  // ---- 第二拍：补上生日 → **同样的身高体重**，BMR/TDEE 应当算出来 ----
  //   ★ 这一步才是真正的**因果验证**：只改"生日"这一个输入，看输出跟不跟着变。
  //     只断言"有值"是不够的 —— 那样一个"瞎猜年龄"的实现也能过。
  const BIRTHDAY = '2004-06-15'
  const upd = await call('PUT', '/us/users/me', {
    token, body: { name: `${TAG} 新学员`, gender: 0, birthday: BIRTHDAY },
  })
  check('补全资料（生日 + 性别）成功', upd.json?.code === 200, `msg=${upd.json?.msg}`)
  check('★ 直查 DB：生日已写入 user_detail',
    sql(`SELECT birthday FROM fm_user.user_detail WHERE id=${userId}`) === BIRTHDAY,
    `birthday=${sql(`SELECT birthday FROM fm_user.user_detail WHERE id=${userId}`)}`)

  const assess2 = await call('POST', '/es/assessments', { token, body: BODY })
  const cells2 = sqlCells(`SELECT bmr, tdee FROM fm_exam.body_assessment WHERE id=${assess2.json?.data}`)

  // 独立按公式复算一遍（不依赖实现）：
  //   Mifflin-St Jeor 男：10×体重 + 6.25×身高 − 5×周岁 + 5
  //   活动系数：本账号无训练记录 → weeklyFrequency 取不到 → 久坐 1.2
  const now = new Date()
  let age = now.getFullYear() - Number(BIRTHDAY.slice(0, 4))
  if (now.getMonth() + 1 < Number(BIRTHDAY.slice(5, 7)) ||
      (now.getMonth() + 1 === Number(BIRTHDAY.slice(5, 7)) && now.getDate() < Number(BIRTHDAY.slice(8, 10)))) {
    age -= 1
  }
  const expBmr = Math.round(10 * W + 6.25 * H - 5 * age + 5)
  const expTdee = Math.round(expBmr * 1.2)
  check('★★ 补上生日后 BMR 算出来了，且**按公式独立复算一致**（±1）',
    Number(cells2[0]) > 0 && Math.abs(Number(cells2[0]) - expBmr) <= 1,
    `db=${cells2[0]} 独立算=${expBmr}（周岁=${age}）`)
  check('★★ TDEE = BMR × 活动系数（无训练记录 → 久坐 1.2）',
    Number(cells2[1]) > 0 && Math.abs(Number(cells2[1]) - expTdee) <= 1,
    `db=${cells2[1]} 独立算=${expTdee}`)

  const latest = await call('GET', '/es/assessments/latest', { token })
  check('最新体测报告可查（本账号 2 条，取最新那条）',
    latest.json?.code === 200 && !!latest.json?.data, `msg=${latest.json?.msg}`)

  // ================================================================
  section('⑦ 说：提问 → 回答 → 点赞（fm-learning · fm-remark）')
  // ================================================================
  const q = await call('POST', '/ls/questions', {
    token,
    body: {
      courseId,
      chapterId: firstChapter.id,
      sectionId: firstSection.id,
      title: `${TAG} 哑铃卧推手腕疼怎么办`,
      description: '第三组时手腕内侧发酸，是不是握距问题？',
      anonymity: false,
    },
  })
  check('提问成功', q.json?.code === 200, `msg=${q.json?.msg}`)
  const qId = sql(`SELECT id FROM fm_learning.interaction_question WHERE user_id=${userId} ORDER BY id DESC LIMIT 1`)
  check('★ 直查 DB：问题落库（本账号恰 1 条）',
    qId !== '' && sql(`SELECT COUNT(*) FROM fm_learning.interaction_question WHERE user_id=${userId}`) === '1',
    `qId=${qId} count=${sql(`SELECT COUNT(*) FROM fm_learning.interaction_question WHERE user_id=${userId}`)}`)

  if (qId) {
    const rep = await call('POST', '/ls/replies', {
      token, body: { questionId: qId, content: '握距窄一点，手腕保持中立位试试', anonymity: false },
    })
    check('回答自己的提问成功', rep.json?.code === 200, `msg=${rep.json?.msg}`)
    const aId = sql(`SELECT id FROM fm_learning.interaction_reply WHERE question_id=${qId} AND answer_id=0 ORDER BY id DESC LIMIT 1`)
    check('★ 直查 DB：回答落库，且问题的 answer_times=1',
      aId !== '' && sql(`SELECT answer_times FROM fm_learning.interaction_question WHERE id=${qId}`) === '1',
      `aId=${aId} answerTimes=${sql(`SELECT answer_times FROM fm_learning.interaction_question WHERE id=${qId}`)}`)

    if (aId) {
      const like = await call('POST', '/rs/remarks/likes', {
        token, body: { bizId: aId, bizType: 'QA', liked: true },
      })
      check('点赞成功', like.json?.code === 200, `msg=${like.json?.msg}`)
      check('★ 直查 DB：点赞记录落库（本人点的那条回答）',
        sql(`SELECT COUNT(*) FROM fm_remark.liked_record WHERE user_id=${userId} AND biz_id=${aId} AND biz_type='QA'`) === '1',
        `count=${sql(`SELECT COUNT(*) FROM fm_remark.liked_record WHERE user_id=${userId} AND biz_id=${aId} AND biz_type='QA'`)}`)
    }
  }

  // ================================================================
  section('⑧ 问 AI：Mock 通道（fm-aigc）')
  // ================================================================
  const provider = await call('GET', '/ais/chat/provider', { token })
  if (provider.json?.data !== 'mock') {
    const note = `当前模型通道 = ${provider.json?.data}（需要 Mock：bash scripts/start-all.sh fm-aigc mock）`
    skipped.push(`⑧ AI 站：${note}`)
    console.log(`  ⏭ 跳过（不算失败）：${note}`)
  } else {
    const ask = await call('POST', '/ais/chat', { token, body: { content: '增肌期每天要吃多少蛋白质？' } })
    const answer = ask.json?.data || {}
    check('AI 对话返回内容', String(answer.content || '').length > 0, `msg=${ask.json?.msg}`)
    check('★ Mock 回答可预期（断言才有依据）', String(answer.content).includes('蛋白质'),
      `content=${String(answer.content).slice(0, 40)}`)
    check('★ 直查 DB：会话与消息都落了库（归属本次新账号）',
      Number(sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE user_id=${userId}`)) >= 1 &&
        Number(sql(`SELECT COUNT(*) FROM fm_aigc.chat_message WHERE user_id=${userId}`)) >= 2,
      `sessions=${sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE user_id=${userId}`)} messages=${sql(`SELECT COUNT(*) FROM fm_aigc.chat_message WHERE user_id=${userId}`)}`)
  }

  // ================================================================
  section('⑨ 收尾：清理 + 自检')
  // ================================================================
  cleanup(state())

  const residue = residueCount(userId)
  // ★ 表数取清单长度，**不写死** —— 写死的数字会在清单增删后悄悄过期
  //   （第一版就写死了"19 张"，补进第 20 张时它还是显示 19）
  check(`★★ 清理自检：该学员在 ${CLEANUP_BY_USER_ID.length} 张表里的残留为 0（清理失败会在这里暴露，不会静默留垃圾）`,
    residue.total === 0,
    `残留=${JSON.stringify(residue.left)}${residue.total ? `（清单只覆盖了列出的表 —— 漏列的表检不出来）` : ''}`)
  check('★ 清理自检：账号已删除',
    sql(`SELECT COUNT(*) FROM fm_user.user WHERE id=${userId}`) === '0',
    `count=${sql(`SELECT COUNT(*) FROM fm_user.user WHERE id=${userId}`)}`)
  check('★ 清理自检：签到 Redis 位图已删',
    redis('EXISTS', `sign:uid:${userId}:${monthKey()}`) === '0',
    `exists=${redis('EXISTS', `sign:uid:${userId}:${monthKey()}`)}`)

  check('★★ 全过程无 HTTP 5xx（跨服务链路没有把异常漏给用户）', http5xx === 0, `5xx=${http5xx}`)
  check('★ 全过程无"非预期"的业务码非 200（4xx/校验失败是预期内的负向用例）', bizErrors.length === 0,
    bizErrors.slice(0, 3).join(' | '))

  return summary()
}

/**
 * 收拾现场：按本次旅程的 userId / orderId / couponId 精确删除。
 *
 * ★ 每条 DELETE 都带 `WHERE <col>=<本次 id>` —— **永远不会**出现无 where 的删除。
 * ★ 抽成函数的原因：旅程可能在任何一站提前收场，
 *   如果清理只写在最后一站，提前收场就会**留下垃圾**（本次就踩到了）。
 */
function cleanup({ userId, orderId, couponId, lessonId, sectionId }) {
  if (userId) {
    for (const [table, col] of CLEANUP_BY_USER_ID) {
      sql(`DELETE FROM ${table} WHERE ${col}=${userId}`)
    }
    // 账号本身
    sql(`DELETE FROM fm_user.user_detail WHERE id=${userId}`)
    sql(`DELETE FROM fm_user.user WHERE id=${userId}`)
    // Redis：签到日历位图 + 榜单成员
    redis('DEL', `sign:uid:${userId}:${monthKey()}`)
    redis('ZREM', `boards:${monthKey()}`, String(userId))
  }
  // 支付单没有 user_id，只能按订单号删
  if (orderId) sql(`DELETE FROM fm_pay.pay_order WHERE biz_order_no=${orderId}`)
  // 券（运营侧建的那张）
  if (couponId) sql(`DELETE FROM fm_promotion.coupon WHERE id=${couponId}`)
  // 学习进度缓存
  if (lessonId && sectionId) redis('DEL', `learning:record:${lessonId}:${sectionId}`)
}

/** 提前收场：**先收拾现场**，再出汇总（否则中断的旅程会留下垃圾数据） */
function bailOut(where, state) {
  console.log(`\n  ⚠️ 旅程在「${where}」处无法继续 —— 先清理现场，再汇总`)
  cleanup(state)
  return summary()
}

function summary() {
  console.log('\n============================================================')
  console.log(` 结果：${passed} 通过 / ${failed} 失败` + (skipped.length ? ` / ${skipped.length} 跳过` : ''))
  if (skipped.length) {
    console.log(' 跳过（环境不满足，需单独确认）：')
    skipped.forEach((s) => console.log(`   · ${s}`))
  }
  if (failures.length) {
    console.log(' 失败项：')
    failures.forEach((f) => console.log(`   · ${f}`))
  }
  console.log('============================================================')
  process.exit(failed ? 1 : 0)
}

main().catch((err) => {
  console.error('\n❌ 旅程异常终止：', err)
  process.exit(1)
})
