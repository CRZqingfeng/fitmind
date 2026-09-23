/**
 * ============================================================================
 * M8-2c 验收脚本：异步链路 + 跨端联动（后端 / 中间件侧）
 * ----------------------------------------------------------------------------
 * 运行： node scripts/accept-m8-2c.mjs
 *
 * ★★ 为什么单独有这一节（它与 M8-2b 的分工）：
 *
 *   M8-2a 验「多身份接缝」；M8-2b 验「一条用户旅程跨 11 个服务」——
 *   但 **M8-2b 是结果导向的**：它断言"支付成功后 6 处副作用都到位"。
 *
 *   结果导向回答不了下面三类问题，本节只做这三件事（＋一次对账）：
 *
 *     ① 链路的**机制**是活的吗？—— 声明的队列到底有没有消费者
 *     ② **消费失败**会怎样？—— 重试几次 / 最终去哪（现有脚本从未验证）
 *     ③ **定时任务真的会执行**吗？—— 现有脚本只查"已注册"，**从不触发**
 *     ④ 跨中间件对账（Redis / MySQL / RabbitMQ 三方不变量）
 *
 * 四节：
 *   §1 对账不变量      —— 不需要造数据；其中 ① 在修复前是**红的**
 *   §2 MQ 拓扑守护      —— 队列清单**从 MqConstants 解析**，不硬编码
 *   §3 消费失败行为      —— ★ 故意注入一条毒消息，观测后**立刻清理**
 *   §4 xxl-job 真实执行  —— 造超时订单 → 手动触发 → 断言效果（含清理）
 *
 * ----------------------------------------------------------------------------
 * 前置条件：15 个业务服务 + 网关 + MySQL / Redis / RabbitMQ / xxl-job 已就绪
 * 可直接重跑：§3 会自己收拾现场，§4 用新注册学员并在结束时清理
 * ============================================================================
 */

import { spawnSync } from 'node:child_process'
import fs from 'node:fs'

const GATEWAY = 'http://localhost:10010'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const MQ_API = 'http://localhost:15672/api'
const XXL = 'http://localhost:9090/xxl-job-admin'

/** 常量文件 —— §2 的队列清单从这里**解析**（不是硬编码） */
const MQ_CONST_FILE = 'fm-common/src/main/java/com/fitmind/common/constants/MqConstants.java'

/**
 * §3 的注入目标队列。
 * 选它的理由：消费者（`QaLikeTimesListener`）**没有 catch**，且"回答点赞数同步"
 * **不在业务主链路上** —— 万一清理出问题，影响面最小。
 */
const POISON_QUEUE = 'qa.liked.times.queue'

/** §4 要触发的任务：订单超时取消（cron 每分钟，但我们要的是"**手动触发**"这条通道） */
const JOB_TIMEOUT_CANCEL = 50

/** 延迟队列（ZSet）：下单 ZADD、支付/取消 ZREM —— 见 OrderServiceImpl:225 / :328 */
const ORDER_DELAY_KEY = 'trade:order:delay'

const TAG = 'M8-2C-TMP' + String(Date.now()).slice(-6)
const NEW_PHONE = '139' + String(Date.now()).slice(-8)
const NEW_PASSWORD = 'M8cTemp@123'

let passed = 0
let failed = 0
const failures = []
const infos = []

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

/** 只记录、不断言（用于"已知事实"记录，避免把一次改进误判成失败） */
function info(text) {
  infos.push(text)
  console.log(`  ℹ️  ${text}`)
}

function section(title) {
  console.log(`\n【${title}】`)
}

// ============================================================
// 基础设施：进程 / SQL / Redis / HTTP / RabbitMQ / xxl-job
// ============================================================

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

async function waitUntil(fn, { timeout = 20000, interval = 700 } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) return true
    await sleep(interval)
  }
  return false
}

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
  if (token && result.status === 401 && retryOn401) {
    await sleep(200)
    result = await doFetch()
  }
  return result
}

// ---------------- RabbitMQ 管理 API（凭据从 docker/.env 读，不落控制台） ----------------

function mqAuth() {
  return 'Basic ' + Buffer.from(`${envGet('RABBITMQ_USER')}:${envGet('RABBITMQ_PASSWORD')}`).toString('base64')
}

async function mqGet(path) {
  const res = await fetch(`${MQ_API}${path}`, { headers: { Authorization: mqAuth() } })
  if (!res.ok) throw new Error(`MQ GET ${path} → HTTP ${res.status}`)
  return res.json()
}

async function mqPost(path, body) {
  const res = await fetch(`${MQ_API}${path}`, {
    method: 'POST',
    headers: { Authorization: mqAuth(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 有些接口返回空体 */
  }
  return { status: res.status, json, text }
}

/**
 * ★★ 必须把**响应体**也返回：`purge` 的返回值是 `{"message_count": N}` ——
 *    它是区分"消息被丢弃"与"消息还躺在队列里"的**唯一**硬证据
 *    （只看 `ready` 会被随后的 purge 抹掉痕迹）。
 */
async function mqDelete(path) {
  const res = await fetch(`${MQ_API}${path}`, {
    method: 'DELETE',
    headers: { Authorization: mqAuth() },
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 有的接口返回空体 */
  }
  return { status: res.status, json }
}

/** 查单个队列（用于读 message_stats 与堆积） */
async function mqQueue(name) {
  return mqGet(`/queues/%2F/${name}`)
}

// ---------------- xxl-job ----------------

async function xxlLogin() {
  const res = await fetch(`${XXL}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ userName: 'admin', password: '123456' }).toString(),
  })
  const json = await res.json()
  return json.code === 200 ? (res.headers.get('set-cookie') || '').split(';')[0] : null
}

async function xxlPost(cookie, path, form) {
  const res = await fetch(`${XXL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookie },
    body: new URLSearchParams(form).toString(),
  })
  return res.json()
}

/**
 * 在服务日志里数一个 ASCII 标记出现的次数。
 *
 * ⚠️ 服务日志是 **GBK** 编码，所以按 `latin1` 读 —— 我们只找纯 ASCII 的标记，
 *    中文会变成乱码但**不影响计数**。（若按 utf8 读会 replacement 得满屏 U+FFFD，
 *    中文匹配不上，而 ASCII 标记的位置也会被改变。）
 */
function countInFile(file, needle) {
  try {
    const txt = fs.readFileSync(file, 'latin1')
    let n = 0
    let i = 0
    while ((i = txt.indexOf(needle, i)) !== -1) {
      n++
      i += needle.length
    }
    return n
  } catch {
    return -1
  }
}

/**
 * 扫描所有排了 `@RabbitListener` 的 Java 文件，统计：
 *   · 总数 · 没有 `catch` 的文件 · 配了 MQ 重试的服务
 *
 * ★ 动态扫描而不是硬编码清单 —— 与 §2 从 `MqConstants` 解析队列是同一个道理：
 *   清单一旦写死，将来新增 listener 时它就会**静默过期**。
 */
function walkJava(dir, out = []) {
  // ★ 容错：`fm-auth` 这类**聚合模块**只有子模块，自己没有 `src/main/java`
  if (!fs.existsSync(dir)) return out
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = `${dir}/${e.name}`
    if (e.isDirectory()) walkJava(p, out)
    else if (e.name.endsWith('.java')) out.push(p)
  }
  return out
}

function scanListeners() {
  const files = []
  for (const d of fs.readdirSync('.')) {
    if (!d.startsWith('fm-') || !fs.statSync(d).isDirectory()) continue
    files.push(...walkJava(`${d}/src/main/java`))
  }
  // ★ 判据必须带括号：`MqConstants.java` 的**注释**里也写着 `{@code @RabbitListener}`，
  //   不加括号会把它也算成一个 listener 文件（第一版就这么误报过）。
  const listenerFiles = files.filter((f) => fs.readFileSync(f, 'utf8').includes('@RabbitListener('))
  const noCatch = listenerFiles.filter((f) => !fs.readFileSync(f, 'utf8').includes('catch'))

  const retryServices = []
  for (const d of fs.readdirSync('.')) {
    if (!d.startsWith('fm-') || !fs.statSync(d).isDirectory()) continue
    const yml = `${d}/src/main/resources/application.yml`
    if (fs.existsSync(yml) && /max-attempts/.test(fs.readFileSync(yml, 'utf8'))) retryServices.push(d)
  }

  return {
    total: listenerFiles.length,
    noCatch: noCatch.length,
    noCatchFiles: noCatch.map((f) => f.split('/').pop()),
    retryServices,
  }
}

// ============================================================
// 账号（新注册学员：隔离，不碰演示数据）
// ============================================================

async function registerAndLogin() {
  const c1 = await call('POST', `/us/codes?phone=${NEW_PHONE}`)
  const code1 = c1.json?.data
  if (!code1) return { error: `取注册验证码失败：${c1.text.slice(0, 120)}` }

  const reg = await call('POST', '/us/students/register', {
    body: { cellPhone: NEW_PHONE, code: code1, password: NEW_PASSWORD, name: `${TAG} 学员` },
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

// ============================================================
// 主流程
// ============================================================

async function main() {
  console.log('============================================================')
  console.log(' M8-2c 验收：异步链路 + 跨端联动（后端 / 中间件侧）')
  console.log('============================================================')

  // ================================================================
  section('§1 跨中间件对账不变量（不需要造数据）')
  // ================================================================

  // ---- ① 订单明细不能是孤儿：每一行都必须有父订单 ----
  //  ★ 为什么这条值得守：明细与订单是两张表。删除时必须用**同一个维度** ——
  //    一边按 course_id 删、一边按 user_id 删，就会漏出孤儿。
  //    而**任何接口都不会报错**：列表页是按订单 join 出来的，孤儿根本显示不出来。
  const orphanDetails = sql(
    `SELECT COUNT(*) FROM fm_trade.trade_order_detail d
      WHERE NOT EXISTS (SELECT 1 FROM fm_trade.trade_order o WHERE o.id = d.order_id)`,
  )
  const orphanSample = sql(
    `SELECT GROUP_CONCAT(s) FROM (
        SELECT CONCAT('detail=', d.id, '→order=', d.order_id, ' user=', d.user_id) AS s
        FROM fm_trade.trade_order_detail d
        WHERE NOT EXISTS (SELECT 1 FROM fm_trade.trade_order o WHERE o.id = d.order_id)
        LIMIT 5) x`,
  )
  check(
    '★ ① 订单明细没有孤儿（每行都能找到父订单）',
    orphanDetails === '0',
    `孤儿 ${orphanDetails} 条${orphanSample ? `：${orphanSample}` : ''}`,
  )

  // ---- ② 延迟队列 vs 待支付订单（跨 Redis / MySQL 对账） ----
  //  ★ 机制：下单时 ZADD；「用户取消」和「超时任务抢占」两处 ZREM。
  //
  //  ★★ **方向很重要** —— 第一版我写成"成员数 == 待支付订单数"的**双向等式**，回归时红了：
  //     队列里 6 个成员、待支付 0 个。读完代码才发现那是**实现的有意取舍**：
  //     **支付路径不摘**（`OrderServiceImpl:328` 的 ZREM 只在 `cancelOrder` 里，
  //     注释原话：「不摘也不会出错 —— 条件更新会挡住 —— 但会白跑一次任务」）。
  //     已支付订单会滞留到"到期时刻"，之后被任务顺手摘掉；而**订单被删除后成员永久滞留**（见 #C36）。
  //
  //  ★★ 所以真正会"出事"的只有一个方向：**待支付订单不在队列里**
  //     （那它就**永远不会被自动关闭**）。反方向多出来的成员只是"白跑一次任务"。
  const delayMembers = redis('ZRANGE', ORDER_DELAY_KEY, '0', '-1').split('\n').map((s) => s.trim()).filter(Boolean)
  const pendingList = sql(`SELECT IFNULL(GROUP_CONCAT(id ORDER BY id), '') FROM fm_trade.trade_order WHERE status=1`)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  const missingFromQueue = pendingList.filter((id) => !delayMembers.includes(id))
  const surplusInQueue = delayMembers.filter((id) => !pendingList.includes(id))

  check(
    '★★ ② 每个**待支付**订单都在延迟队列里（漏了它就永远不会被自动关闭）',
    missingFromQueue.length === 0,
    `漏了 ${missingFromQueue.length} 个：${JSON.stringify(missingFromQueue)}` +
      `（待支付 ${pendingList.length} 个 / 队列 ${delayMembers.length} 个）`,
  )
  info(
    `延迟队列里有 ${surplusInQueue.length} 个「非待支付」成员` +
      `（已支付滞留 / 订单被删后的残留）—— **不影响正确性**，但任务会为它们白跑。见 #C36`,
  )

  // ---- ③ 支付单不能是孤儿 ----
  const orphanPays = sql(
    `SELECT COUNT(*) FROM fm_pay.pay_order p
      WHERE NOT EXISTS (SELECT 1 FROM fm_trade.trade_order o WHERE o.id = p.biz_order_no)`,
  )
  check('★ ③ 支付单没有孤儿（每笔都指向存在的订单）', orphanPays === '0', `孤儿 ${orphanPays} 条`)

  // ---- ④ MQ 零堆积 ----
  const queuesAll = await mqGet('/queues')
  const backlog = queuesAll.reduce(
    (s, q) => s + (q.messages_ready || 0) + (q.messages_unacknowledged || 0),
    0,
  )
  check('★ ④ MQ 零堆积（ready + unack 合计 = 0）', backlog === 0, `合计 ${backlog}`)

  // ================================================================
  section('§2 MQ 拓扑守护（队列清单从 MqConstants 解析）')
  // ================================================================

  // ★ 从常量文件解析，**不硬编码** —— 否则将来新增队列时守护会静默失效。
  //   （这正是 M8-2b 那条「清理自检的完备性 = 清单的完备性」的同族教训。）
  const constSrc = fs.readFileSync(MQ_CONST_FILE, 'utf8')
  const declaredQueues = [
    ...new Set([...constSrc.matchAll(/String\s+\w+\s*=\s*"([a-z0-9.-]+\.queue)"/g)].map((m) => m[1])),
  ]
  info(`从 MqConstants 解析出 ${declaredQueues.length} 个队列声明`)

  const mqQueueNames = queuesAll.map((q) => q.name).filter((n) => n.endsWith('.queue'))
  check(
    `★ ⑤ 解析出 ≥25 个队列声明（实际 ${declaredQueues.length} 个）`,
    declaredQueues.length >= 25,
    `解析结果：${declaredQueues.join(', ')}`,
  )

  // ★★ 反向对照：解析出来的集合 vs RabbitMQ 里真实的队列集合 —— 必须**完全一致**。
  //    这条同时守住两个方向：
  //      · 声明了但没建 → 说明**从没人消费过**（队列由消费者声明时自动创建）
  //      · 建了但代码里查不到 → 可能是删剩的僵尸队列，也可能是解析正则漏了
  const missingQueues = declaredQueues.filter((n) => !mqQueueNames.includes(n))
  const extraQueues = mqQueueNames.filter((n) => !declaredQueues.includes(n))
  check(
    '★★ ⑥ 声明的队列与实际队列**完全对应**（两个方向都不许有出入）',
    missingQueues.length === 0 && extraQueues.length === 0,
    `声明了但不存在=${JSON.stringify(missingQueues)}；存在但代码里没声明=${JSON.stringify(extraQueues)}`,
  )

  // ★★ 本节的核心断言
  const noConsumer = queuesAll
    .filter((q) => q.name.endsWith('.queue') && (q.consumers || 0) === 0)
    .map((q) => q.name)
  check(
    '★★ ⑦ 每个队列都有 ≥1 个消费者',
    noConsumer.length === 0,
    `无消费者的队列：${JSON.stringify(noConsumer)}`,
  )

  const exchanges = await mqGet('/exchanges')
  const bizExchanges = exchanges.filter((e) => !e.name.startsWith('amq.') && e.name !== '')
  const neverUsed = bizExchanges.filter((e) => (e.message_stats?.publish_in || 0) === 0).map((e) => e.name)
  check(
    `★ ⑧ 业务交换机共 ${bizExchanges.length} 个，且都**被真实用过**（publish_in > 0）`,
    bizExchanges.length === 6 && neverUsed.length === 0,
    `从未发布过：${JSON.stringify(neverUsed)}`,
  )

  // 已知事实（只记录，不做断言 —— 将来有人加了 DLX 是**改进**，不该被这条判失败）
  const withDlx = queuesAll.filter((q) => q.arguments?.['x-dead-letter-exchange']).map((q) => q.name)
  info(
    withDlx.length === 0
      ? '死信交换机：**没有任何队列配置 DLX** → 消费失败的去向见 §3 实测'
      : `配了 DLX 的队列：${withDlx.join(', ')}`,
  )

  // ================================================================
  section('§3 ★ 消费失败行为（实测）')

  //  ★★ 这一节的存在本身就是个教训：
  //    第一版按「没有 errorHandler + 没有 catch + 没有 retry」**推断**——
  //    「毒消息会被无限重投」。实测**推翻了它**（见下）。差一点就把一条错误的
  //    结论写进文档。★ **「框架里没有显式配置」≠「框架没有默认行为」。**

  // ---------------- 实验 A：格式坏的消息（消息转换失败） ----------------
  //  ★ 预期（已由实测修正）：Spring AMQP 自带的 `ConditionalRejectingErrorHandler`
  //    把 `MessageConversionException` 判为 **fatal** → `AmqpRejectAndDontRequeueException`
  //    → **requeue=false，消息被直接丢弃**（因为也没有配 DLX，就是彻底消失）。

  const qBefore = await mqQueue(POISON_QUEUE)
  const redeliver0 = qBefore.message_stats?.redeliver || 0
  const fatalLog0 = countInFile('logs/fm-learning.log', 'AmqpRejectAndDontRequeueException')
  console.log(
    `     基线：ready=${qBefore.messages_ready} unack=${qBefore.messages_unacknowledged} ` +
      `consumers=${qBefore.consumers} redeliver=${redeliver0} fatal日志=${fatalLog0}`,
  )

  const safeToInject = qBefore.messages_ready === 0 && qBefore.messages_unacknowledged === 0
  check(
    '★ ⑨ 注入前队列是空的（保证随后的 purge 不会丢掉真实消息）',
    safeToInject,
    `ready=${qBefore.messages_ready} unack=${qBefore.messages_unacknowledged}`,
  )

  if (safeToInject) {
    try {
      // ★ 发到**默认交换机** + routing_key = 队列名 → 只落到这一个队列，
      //   不会惊动别的消费者（这是把它做成"单队列实验"的关键）
      const pub = await mqPost('/exchanges/%2F/amq.default/publish', {
        properties: { content_type: 'application/json' },
        routing_key: POISON_QUEUE,
        payload: 'this is definitely { not valid json',
        payload_encoding: 'string',
      })
      check(`★ ⑩ 毒消息已投递到 ${POISON_QUEUE}`, pub.json?.routed === true, `routed=${pub.json?.routed}`)

      await sleep(8000)

      const qAfter = await mqQueue(POISON_QUEUE)
      const redeliverDelta = (qAfter.message_stats?.redeliver || 0) - redeliver0
      const fatalLog1 = countInFile('logs/fm-learning.log', 'AmqpRejectAndDontRequeueException')
      const fatalLogDelta = fatalLog0 >= 0 && fatalLog1 >= 0 ? fatalLog1 - fatalLog0 : -1
      console.log(
        `     8 秒后：redeliver=Δ${redeliverDelta} fatal日志=Δ${fatalLogDelta} ` +
          `ready=${qAfter.messages_ready} unack=${qAfter.messages_unacknowledged}`,
      )

      // ★★ 核心断言：**不重投**
      check(
        '★★ ⑪ 实测：转换失败的消息**没有被重投**（redeliver 增量 = 0）',
        redeliverDelta === 0,
        `redeliver 增量=${redeliverDelta}（>0 说明走了 requeue 分支，结论要重写）`,
      )
      // ★★ 机制证据：日志里出现"转 fatal"这个动作
      check(
        '★★ ⑫ 机制已被证实：`ConditionalRejectingErrorHandler` 把异常转成了 fatal',
        fatalLogDelta > 0,
        `AmqpRejectAndDontRequeueException 新增 ${fatalLogDelta} 条`,
      )
      // ★★ 消息去向：8 秒后队列里**已经没有它了** → 被丢弃（而不是"还没轮到处理"）
      check(
        '★★ ⑬ 消息去向：8 秒后队列里已没有它 → 被**丢弃**（不是排队没处理）',
        qAfter.messages_ready === 0 && qAfter.messages_unacknowledged === 0,
        `ready=${qAfter.messages_ready} unack=${qAfter.messages_unacknowledged}`,
      )
    } finally {
      // ★★ 证据必须在 **purge 之前**取：
      //    `DELETE .../contents` 实测返回 **204 无响应体**（拿不到 message_count），
      //    而且 purge 本身会抹掉"消息还在不在"的痕迹。
      //    → 先读一次队列状态，用这个读数判定"消息已被丢弃"；purge 只是随后的保险动作。
      const qBeforePurge = await mqQueue(POISON_QUEUE)
      const leftover = (qBeforePurge.messages_ready || 0) + (qBeforePurge.messages_unacknowledged || 0)
      console.log(`     purge 前再读一次：ready=${qBeforePurge.messages_ready} unack=${qBeforePurge.messages_unacknowledged}`)

      // ★★ 这是"丢弃"的**铁证**：8 秒 + 再读一次，队列里始终没有它。
      //    若 >0，说明它一直躺在队列里 —— 那就是"没被处理"，而不是"被丢弃"。
      check(
        '★★ ⑭ 铁证：purge 前队列里已是 0 条 → 毒消息确实**被丢弃**了（不是躺着没处理）',
        leftover === 0,
        `purge 前剩余 ${leftover} 条（>0 = 消息还在队列里，结论要重写）`,
      )

      // 保险动作（若上面是 0，这里就是空操作）
      const del = await mqDelete(`/queues/%2F/${POISON_QUEUE}/contents`)
      console.log(`     清理：purge ${POISON_QUEUE} → HTTP ${del.status}`)
      await sleep(1500)

      const qClean = await mqQueue(POISON_QUEUE)
      check(
        '★ ⑮ 清理后队列回到零（现场已收拾）',
        qClean.messages_ready === 0 && qClean.messages_unacknowledged === 0,
        `ready=${qClean.messages_ready} unack=${qClean.messages_unacknowledged}`,
      )
      check(
        '★ ⑯ 清理后队列仍有消费者（没把 listener 搞挂）',
        (qClean.consumers || 0) >= 1,
        `consumers=${qClean.consumers}`,
      )
    }
  } else {
    info(`队列 ${POISON_QUEUE} 不是空的 → **跳过注入**（避免 purge 丢掉真实消息）`)
  }

  // ---------------- 实验 B：业务异常（listener 内部抛出） ----------------
  //  ★★ **明确标注：未实测。**
  //    要构造"能反序列化成功、但让 listener 抛业务异常"的消息，就得往
  //    **业务队列**里灌脏数据（比如伪造一条 order.pay 去写课表），
  //    污染风险 > 这条结论的价值。所以这里只给**静态事实 + 推断**，不冒充实测。

  const listenerStats = scanListeners()
  info(
    `业务异常路径（静态事实）：共 ${listenerStats.total} 个 listener 文件，` +
      `其中 **${listenerStats.noCatch} 个连 catch 都没有**：` +
      `${listenerStats.noCatchFiles.join('、')}`,
  )
  info(
    `配了 MQ 重试的服务：${listenerStats.retryServices.length ? listenerStats.retryServices.join('、') : '无'}` +
      `（其余走 Spring 默认）`,
  )
  info(
    '推断（★ 未实测）：`ConditionalRejectingErrorHandler` 只把 `MessageConversionException`/' +
      '`AmqpRejectAndDontRequeueException` 转 fatal，**业务异常不在其列** → ' +
      '那些没有 catch、又没有 retry 的 listener 会把业务异常交给容器默认处理 → ' +
      '在无 DLX 的前提下**会被反复重投**。→ 已登记为待确认项 #C34④',
  )

  // ================================================================
  section('§4 ★ xxl-job 真实执行（造超时订单 → 手动触发 → 断言效果）')
  // ================================================================

  // ★ 现有脚本只用过 `/jobinfo/pageList` 查"任务是否注册"，
  //   **从来没有触发过任何一个任务**。而 13 个任务里 9 个排在凌晨/每月固定日，
  //   靠"等自然触发"在验收里根本不可行 —— 手动触发是唯一的路。

  // ---- 4.1 新学员注册并登录（隔离） ----
  const acc = await registerAndLogin()
  check('★ ⑰ 新学员注册并登录成功', !!acc.token, acc.error || '')
  if (!acc.token) return summary()
  const token = acc.token
  const userId = sql(`SELECT id FROM fm_user.user WHERE cell_phone='${NEW_PHONE}'`)
  console.log(`     学员 userId=${userId}`)

  // ---- 4.2 选一门**已上架的付费课**，下单（**不支付**） ----
  const courseId = sql(`SELECT id FROM fm_course.course WHERE status=2 AND price > 0 ORDER BY id LIMIT 1`)
  const pre = await call('GET', `/ts/orders/prePlace?courseIds=${courseId}`, { token })
  const orderId = pre.json?.data?.orderId
  check('★ ⑱ 预下单拿到 orderId', !!orderId, `msg=${pre.json?.msg} courseId=${courseId}`)
  if (!orderId) {
    cleanup({ userId })
    return summary()
  }

  //  ★★ 注意：id 一律按**字符串**传 —— 项目契约是 Long→String 全局。
  //     包成 Number() 会因超过 2^53 静默丢精度（M8-2b 踩过，见清单 #C33）。
  const placed = await call('POST', '/ts/orders', {
    token,
    body: { orderId, courseIds: [courseId], userCouponIds: [] },
  })
  check('★ ⑲ 下单成功（**未支付**）', placed.json?.code === 200, `msg=${placed.json?.msg}`)
  check(
    '★ ⑳ 订单状态 = 待支付(1)',
    sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderId}`) === '1',
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderId}`)}`,
  )

  // ---- 4.3 顺带验 §1 的不变量 ②：下单后订单**应当**进了延迟队列 ----
  const score = redis('ZSCORE', ORDER_DELAY_KEY, String(orderId))
  check(
    '★★ ㉑ 该订单已进入延迟队列（这是「下单 ZADD」的实证，也是 §1② 的局部验证）',
    score !== '',
    `ZSCORE=${score}（空 = 没进去）`,
  )
  if (score) {
    const scoreMs = Number(score)
    const nowMs = Date.now()
    check(
      '★ ㉒ 延迟队列的 score 是**未来**时间（即"到期时间"，不是"入队时间"）',
      scoreMs > nowMs,
      `score=${scoreMs} now=${nowMs}（差 ${Math.round((scoreMs - nowMs) / 1000)} 秒）`,
    )
  }

  // ---- 4.4 把 score 改成"过去" → 让它立刻到期（否则要等真实的超时时长） ----
  const past = Date.now() - 60000
  const zadd = spawnSync(
    'docker',
    ['exec', 'fm-redis', 'redis-cli', '-a', envGet('REDIS_PASSWORD'), '--no-auth-warning',
      'ZADD', ORDER_DELAY_KEY, String(past), String(orderId)],
    { encoding: 'utf8' },
  )
  console.log(`     已把该订单的到期时间改到 60 秒前（ZADD 返回 ${(zadd.stdout || '').trim()}）`)

  // ---- 4.5 手动触发任务 ----
  const cookie = await xxlLogin()
  check('★ ㉓ xxl-job admin 登录成功', !!cookie)
  const logIdBefore = Number(
    sql(`SELECT IFNULL(MAX(id), 0) FROM xxl_job.xxl_job_log WHERE job_id=${JOB_TIMEOUT_CANCEL}`),
  )
  console.log(`     触发前该任务最大日志 id = ${logIdBefore}`)
  const trigRes = await xxlPost(cookie, '/jobinfo/trigger', {
    id: String(JOB_TIMEOUT_CANCEL),
    executorParam: '',
  })
  //  ⚠️ 触发接口**对不存在的 id 也返回 200**（已实测）→ 所以它**不能**作为判据，
  //     真正的判据是"订单真的被关闭了" ＋ "日志真的新增了一条"。
  console.log(`     /jobinfo/trigger 返回 code=${trigRes.code}（★ 该返回码不可信，只作记录）`)

  // ---- 4.6 断言：订单被关闭 ----
  //  ★ 用 waitUntil 轮询（任务由 admin 异步派发到执行器，不是同步执行）
  const closed = await waitUntil(
    () => sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderId}`) === '3',
    { timeout: 25000, interval: 1000 },
  )
  check(
    '★★ ㉔ 超时订单被该任务关闭（status → 已关闭(3)）',
    closed,
    `status=${sql(`SELECT status FROM fm_trade.trade_order WHERE id=${orderId}`)}`,
  )

  // ---- 4.7 断言：延迟队列里的成员被摘除 ----
  check(
    '★ ㉕ 该订单已从延迟队列摘除（ZREM 生效）',
    redis('ZSCORE', ORDER_DELAY_KEY, String(orderId)) === '',
    `ZSCORE=${redis('ZSCORE', ORDER_DELAY_KEY, String(orderId))}`,
  )

  // ---- 4.8 断言：xxl_job_log 真的新增了一条成功记录 ----
  const logAppeared = await waitUntil(
    () =>
      Number(sql(`SELECT IFNULL(MAX(id), 0) FROM xxl_job.xxl_job_log WHERE job_id=${JOB_TIMEOUT_CANCEL}`)) >
      logIdBefore,
    { timeout: 20000, interval: 1000 },
  )
  const logRow = sql(
    `SELECT CONCAT(id, ' | handle_code=', handle_code, ' | ', trigger_time)
       FROM xxl_job.xxl_job_log WHERE job_id=${JOB_TIMEOUT_CANCEL} ORDER BY id DESC LIMIT 1`,
  )
  check('★★ ㉖ xxl_job_log 新增了一条执行记录（任务真的被执行了）', logAppeared, `最新记录：${logRow}`)
  check(
    '★ ㉗ 该次执行成功（handle_code=200）',
    /handle_code=200(\s|\|)/.test(logRow),
    `最新记录：${logRow}`,
  )

  // ---- 4.9 清理 ----
  cleanup({ userId, orderId })
  check(
    '★★ ㉘ 清理自检：本次学员与订单已删净（不给环境留垃圾）',
    sql(`SELECT COUNT(*) FROM fm_user.user WHERE id=${userId}`) === '0' &&
      sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE id=${orderId}`) === '0',
    `user=${sql(`SELECT COUNT(*) FROM fm_user.user WHERE id=${userId}`)} order=${sql(`SELECT COUNT(*) FROM fm_trade.trade_order WHERE id=${orderId}`)}`,
  )

  return summary()
}

// ============================================================
// 清理（★ 抽成函数：任何一站提前收场都要走它，见 M8-2b 的 lesson）
// ============================================================

function cleanup({ userId, orderId }) {
  if (orderId) {
    // ★ 明细与订单**必须同一个维度**删 —— 这正是 §1① 那条孤儿断言的成因
    sql(`DELETE FROM fm_trade.trade_order_detail WHERE order_id=${orderId}`)
    sql(`DELETE FROM fm_trade.trade_order WHERE id=${orderId}`)
    sql(`DELETE FROM fm_pay.pay_order WHERE biz_order_no=${orderId}`)
    redis('ZREM', ORDER_DELAY_KEY, String(orderId))
  }
  if (userId) {
    sql(`DELETE FROM fm_trade.trade_cart WHERE user_id=${userId}`)
    sql(`DELETE FROM fm_learning.learning_lesson WHERE user_id=${userId}`)
    sql(`DELETE FROM fm_auth.login_record WHERE user_id=${userId}`)
    sql(`DELETE FROM fm_user.user_detail WHERE id=${userId}`)
    sql(`DELETE FROM fm_user.user WHERE id=${userId}`)
  }
}

function summary() {
  console.log('\n============================================================')
  console.log(' 汇总')
  console.log('============================================================')
  for (const t of infos) console.log(`   ℹ️  ${t}`)
  console.log('  ────────────────────────────────')
  console.log(`  验收结果：通过 ${passed} 项，失败 ${failed} 项`)
  if (failures.length) {
    console.log('  失败明细：')
    for (const f of failures) console.log(`    ❌ ${f}`)
  }
  console.log('============================================================')
  process.exitCode = failed === 0 ? 0 : 1
}

main().catch((e) => {
  console.error('\n❌ 脚本异常终止：', e)
  process.exitCode = 1
})
