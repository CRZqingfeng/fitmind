#!/usr/bin/env node
/**
 * ============================================================
 * M7-5 前端验收：社区（问答 / 笔记 / 点赞）+ 站内信
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-auth(8081) / fm-user(8082) /
 *           fm-learning(8086) / fm-remark(8087) / fm-message(8091) /
 *           fm-course(8084) / fm-trade(8088)（布局的购物车角标会调它）
 *   · 数据：已跑过 `node scripts/seed-demo-data.mjs`，且当前账号**已报名至少一门课程**
 *   · 前端：fitmind-portal dev server (5173)
 *
 * 用法：cd frontend && node scripts/accept-web-m7-5.mjs
 *
 * ★★ 本脚本要钉住的四件事（都是"做错了看不出来"的类型）：
 *
 *   1. **列表接口必须带归属上下文**。
 *      `GET /ls/questions/page` 不带 `courseId`/`sectionId` → `400 课程id和训练动作id不能都为空`；
 *      笔记同理，回答则是 `questionId`/`answerId` 二选一。
 *      换句话说这个社区**没有"全部内容"列表**。第一版前端按"直接列全部"做，
 *      结果接口一直 400、页面一直显示 0 —— 而验收脚本写着 `0 == 0` **通过了**。
 *      所以这里专门有一条断言去钉这个约束（假绿过一次的地方必须留一道关卡）。
 *
 *   2. **提问必须绑定「课程 > 训练日 > 动作」三级**（后端 `QuestionFormDTO` 全 `@NotNull`）。
 *
 *   3. **点赞是"切换语义"**（`liked: true/false` 同一个接口，且落在 fm-remark）：
 *      点一次 +1、**再点一次必须回到原值** —— 本地做 `+1` 的实现会在这里露馅。
 *
 *   4. **采集笔记 = 在自己名下复制一条**（不是打标记），"我的笔记"会 **+1**。
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
const GATEWAY = 'http://localhost:10010'
const STUDENT_PHONE = '13800010001'
/** ★ 种子数据里的学员密码，用密码登录可避开验证码 60 秒频控 */
const STUDENT_PASSWORD = '123456'
const TOKEN_KEY = 'fitmind:token'

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
        /* 非 JSON */
      }
      return { status: res.status, body: json }
    },
    { gateway: GATEWAY, key: TOKEN_KEY, m: method, p: path, b: body ?? null },
  )
}

const apiGet = (page, p) => api(page, 'GET', p)
const apiPost = (page, p, b) => api(page, 'POST', p, b)
const apiDelete = (page, p) => api(page, 'DELETE', p)

async function waitFor(fn, { timeoutMs = 15000, intervalMs = 700, label = '' } = {}) {
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
 * 登录（★ 走密码登录接口，不走 UI 的验证码登录）
 *
 * 理由：验证码有 60 秒/手机号 的频控，脚本连着跑第二次必定撞上、只能干等 62 秒；
 * 而"登录页 → 取验证码 → 登录"这条 UI 链路 M7-1 的验收已经完整覆盖。
 * ★ 有意的取舍，而且顺带覆盖了 `type=1`（密码登录）这条入参形态。
 */
async function loginByApi(page, phone, password) {
  const res = await fetch(`${GATEWAY}/as/accounts/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 1, cellPhone: phone, password, rememberMe: true }),
  })
  const body = await res.json()
  if (!body?.data) throw new Error(`密码登录失败：code=${body?.code} msg=${body?.msg}`)
  await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await page.evaluate((t) => localStorage.setItem('fitmind:token', t), body.data)
  return body.data
}

async function switchTab(page, label) {
  await page.locator('.el-tabs__item', { hasText: label }).first().click()
  await page.waitForTimeout(900)
}

async function main() {
  console.log('============================================================')
  console.log(' FitMind M7-5 前端验收（社区：问答 / 笔记 / 点赞 + 站内信）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(`${GATEWAY}/ls/lessons/page`, 'fm-learning')
  await requireReachable(`${GATEWAY}/sms/messages/unread-count`, 'fm-message')

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
  /** ★ "HTTP 200 但业务码非 200"——只弹红条，不留任何日志（M7-4 踩过的盲区） */
  const businessErrors = []
  page.on('response', async (res) => {
    if (!res.url().includes(':10010')) return
    try {
      const body = await res.json()
      if (body && typeof body.code === 'number' && body.code !== 200) {
        const u = new URL(res.url())
        businessErrors.push(`${res.request().method()} ${u.pathname}${u.search} → ${body.code} ${body.msg ?? ''}`)
      }
    } catch {
      /* 非 JSON */
    }
  })

  const stamp = Date.now().toString().slice(-6)

  /**
   * 本次运行**自己创建**的内容 id（第 10 节要用它们"收拾现场"）。
   *
   * ★ 只记 id，绝不做"按标题模糊匹配"的删除 ——
   *   那样迟早会删到别人的数据（本项目在 M7-1 已经因为"删错东西"付过代价）。
   */
  const createdIds = { question: null, note: null, gathered: null, gatheredSource: null }

  /**
   * 点开某个 el-select、并选中它的第一项（提问与笔记弹窗的三级联动都要用）。
   *
   * ★★ 这个函数前后改了三版，前两版都卡在同一类问题上。
   *    这里把三个坑都写清楚，因为它们的**症状完全一样**：
   *    "等一个永远不出现的下拉项，直到 30 秒超时"，而原因各不相同。
   *
   * ① ★★ **判据不能用"数可见项"，要用 `aria-expanded`。**
   *    Element Plus 的下拉有展开动画：刚点完那一瞬 popper 还是 `0x0`，
   *    这时 `filter({visible:true}).count()` 是 0 —— 脚本以为"没点开"，于是**又点了一下**，
   *    而**第二下恰恰把已经打开的下拉关掉了**（Playwright 的 click 是 toggle 语义）。
   *    三次重试跑完，最终状态是"关着的"，接着去点选项就会一直等到超时。
   *    诊断记录（对同一触发器连点）：
   *      第 1 次 → aria-expanded=true，popper 586x46，可见项 1 个
   *      第 2 次 → aria-expanded=false，可见项 0 个   ← 就是把①的打开关掉了
   *    → 改用 `aria-expanded`：它是**状态**，不受渲染/动画影响。
   *
   * ② **下拉是 teleport 到 body 的，多个 select 的 popper 同时留在 DOM 里。**
   *    实测页面上同时有 4 个 popper（课程 7 项 / 动作 3 项 / 训练日 1 项 / 空 1 个），
   *    所以"全局取第一个可见 item"很可能取到**别的下拉**的选项。
   *    → 用 input 的 `aria-controls` 拿到**这个 select 自己的 popper id**，
   *      再在它内部找选项，完全不依赖可见性过滤（也就没有①的歧义）。
   *
   * ③ **选项本身用原生 DOM click**：Playwright 的 click 要求元素"稳定"
   *    （连续两帧位置一致），而 popper 是 `transform` 定位 + 展开动画，
   *    动画期间会被判成 `element is not stable` 然后无限重试到超时。
   *    选项响应的是原生 click，直接派发即可。
   *
   * 返回 { popperId, text }，便于调用方打印"到底选中了什么"。
   */
  const selectFirstOption = async (triggerTestId) => {
    const trigger = page.locator(`[data-testid="${triggerTestId}"]`).first()
    const input = trigger.locator('input').first()

    // ① 打开：最多点 5 次，直到 aria-expanded 变 true
    let expanded = false
    for (let i = 0; i < 5 && !expanded; i++) {
      if ((await input.getAttribute('aria-expanded')) === 'true') {
        expanded = true
        break
      }
      await trigger.click({ timeout: 5000 }).catch(() => {})
      await page.waitForTimeout(400)
    }
    if (!expanded) throw new Error(`下拉打不开：${triggerTestId}`)

    // ② 在"这个 select 自己的" popper 里点第一项
    const popperId = await input.getAttribute('aria-controls')
    const item = page.locator(`#${popperId} .el-select-dropdown__item`).first()
    await item.waitFor({ state: 'visible', timeout: 8000 })
    const text = (await item.textContent())?.trim()
    await item.evaluate((el) => el.click())
    await page.waitForTimeout(500)
    return { popperId, text }
  }

  try {
    // ================================================================
    section('0. 登录')
    // ================================================================
    const token = await loginByApi(page, STUDENT_PHONE, STUDENT_PASSWORD)
    check('登录成功（密码登录，避开验证码频控）', Boolean(token))

    // ================================================================
    section('1. ★ 列表接口的"归属上下文"约束（假绿过一次的地方）')
    // ================================================================
    const noCtx = await apiGet(page, '/ls/questions/page?pageNo=1&pageSize=5')
    check('★★ 问题列表不带 courseId/sectionId 时被后端拒绝（400 + 明确原因）',
      noCtx.body?.code === 400 && /不能都为空/.test(noCtx.body?.msg ?? ''),
      `code=${noCtx.body?.code} msg=${noCtx.body?.msg}`)

    const noCtxNote = await apiGet(page, '/ls/notes/page?pageNo=1&pageSize=5')
    check('★★ 笔记列表同样要求上下文', noCtxNote.body?.code === 400,
      `code=${noCtxNote.body?.code} msg=${noCtxNote.body?.msg}`)

    const noCtxReply = await apiGet(page, '/ls/replies/page?pageNo=1&pageSize=5')
    check('★★ 回答列表要求 questionId 或 answerId', noCtxReply.body?.code === 400,
      `code=${noCtxReply.body?.code} msg=${noCtxReply.body?.msg}`)

    // 取一门我的课程作为后面所有断言的上下文
    const myLessons = (await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100')).body?.data?.list ?? []
    if (!myLessons.length) {
      throw new Error('当前账号没有已报名的课程 —— 社区内容挂在课程下，请先报名（跑 M7-4 或 seed）')
    }
    const ctxCourse = String(myLessons[0].courseId)
    const ctxCourseName = myLessons[0].courseName
    console.log(`     上下文课程：${ctxCourse} ${ctxCourseName}`)

    // ================================================================
    section('2. 社区首页（★ 页面总数 == 接口 total，且都带同一上下文）')
    // ================================================================
    const q0 = (await apiGet(page, `/ls/questions/page?pageNo=1&pageSize=10&courseId=${ctxCourse}`)).body?.data

    /**
     * ★★ 钉住一条契约：分页的 `total` 是**字符串**（不是数字）。
     *
     * `fm-common` 的 `JacksonConfig` 全局把 Long 序列化成 String（为了防止前端
     * 大整数精度丢失）。于是 `total` / 各种 `id` 到前端都是字符串，
     * 但**业务计数字段**（如 `answerTimes`、`likedTimes`）是 Integer，仍是数字。
     *
     * 这个混合形态本身没问题，问题是**很容易写出静默出错的算术**：
     *   `q0.total + 1`  → `"7" + 1` → `"71"`（字符串拼接，不报错）
     * 本脚本第一版就踩了这个：断言写成 `共 ${q0.total + 1} 个问题`，
     * 期望页面显示"共 8 个问题"，实际拿"共 71 个问题"去比 —— 永远不相等、超时失败，
     * 而**失败信息里看不出是类型问题**（只看到「页面「null」」）。
     *
     * → 所以这里既做断言（防止后端哪天改了序列化策略而没人知道），
     *   也在后面所有用到 `total` 的地方显式 `Number(...)`。
     */
    check('★ 契约：分页 total 是字符串（Long→String 全局序列化）',
      typeof q0?.total === 'string',
      `total=${JSON.stringify(q0?.total)} 类型=${typeof q0?.total}（★ 后面所有算术都必须先 Number()）`)

    await page.goto(`${PORTAL}/community?courseId=${ctxCourse}`, {
      waitUntil: 'domcontentloaded',
      timeout: 30000,
    })
    await page.waitForTimeout(1500)

    const ctxText = (await page.textContent('[data-testid="ctx-course"]'))?.trim()
    check('★ 页面顶部选中的课程就是 URL 指定的那门', ctxText === ctxCourseName,
      `页面「${ctxText}」/ 期望「${ctxCourseName}」`)

    const totalText = (await page.textContent('[data-testid="question-total"]'))?.trim()
    check('★ 页面「共 N 个问题」== 接口同 courseId 的 total',
      totalText === `共 ${q0?.total ?? 0} 个问题`,
      `页面「${totalText}」/ 接口 ${q0?.total ?? 0}`)

    const items = await page.locator('[data-testid="question-item"]').count()
    check('★ 问题卡片数 == 接口第 1 页条数（同 pageSize 口径）',
      items === (q0?.list ?? []).length,
      `页面 ${items} 条 / 接口 ${(q0?.list ?? []).length} 条`)

    /**
     * ★★ 内容为空时，上面两条"页面数 == 接口数"退化成 `0 == 0` —— 过了但什么都没验。
     *
     * 这正是 M7-2 学到的教训（"没数据时交叉验证会退化"）在 M7-5 上的翻版：
     * 清空演示内容之后，这一节就再也测不到"页面真的把后端数据渲染出来了"。
     * 这里**不隐藏**它 —— 明确打一条告警，让看日志的人知道这一节的分量。
     * （后面的第 3/6/7 节会自己造内容再断言，写入链路仍然是被覆盖的。）
     */
    if ((q0?.list ?? []).length === 0) {
      console.log('     ⚠️ 当前课程下没有他人内容 → 本节是 `0 == 0` 的弱断言（写入链路由第 3/6/7 节覆盖）')
    }

    await page.screenshot({ path: join(SHOT_DIR, 'm7-5-01-community-qa.png'), fullPage: true })

    // ================================================================
    section('3. 提问（三级上下文 + 写入后列表跟着变）')
    // ================================================================
    const questionTitle = `验收提问 ${stamp}：深蹲膝盖内扣怎么纠正`
    await page.click('[data-testid="question-ask-btn"]')
    await page.waitForSelector('[data-testid="question-chapter"]', { timeout: 10000 })
    await page.waitForTimeout(800) // ★ 等弹窗打开动画结束（动画期间的点击会被吞）

    await selectFirstOption('question-chapter')
    await selectFirstOption('question-section')

    await page.fill('[data-testid="question-title"]', questionTitle)
    await page.fill(
      '[data-testid="question-description"]',
      `验收描述 ${stamp}：下蹲到底时膝盖会往内夹，站距再宽一点会好些吗？`,
    )
    await page.click('[data-testid="question-submit"]')

    const created = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/questions/page?pageNo=1&pageSize=10&courseId=${ctxCourse}`)
        return (r.body?.data?.list ?? []).find((q) => q.title === questionTitle) ?? null
      },
      { label: '提问落库', timeoutMs: 15000 },
    )
    check('★ 提问成功（接口里能查到这条）', Boolean(created), created ? `id=${created.id}` : '未查到')
    createdIds.question = created?.id ?? null

    // 提交后弹窗关闭 + 列表重新拉，给一点时间
    await page.waitForTimeout(1500)

    /**
     * ★★ 期望值必须**在断言时从接口现取**，不能拿测试开头抓的 `q0` 去 `+1`。
     *
     * `q0` 是十几秒前的快照，中间任何一次额外写入（上一次失败的验收、诊断脚本、
     * 手工操作）都会让它算错 —— 而失败信息只会说「页面 null」，
     * **完全看不出是"期望值算错了"还是"页面没刷新"**（第一版就是这么卡住的）。
     * 所以这里拆成两步：先确认"接口确实 +1 了"，再确认"页面跟上了接口"。
     */
    const apiAfterAsk = Number(
      (await apiGet(page, `/ls/questions/page?pageNo=1&pageSize=1&courseId=${ctxCourse}`)).body?.data?.total ?? 0,
    )
    check('★ 接口 total 确实 +1（写入真的落库了）',
      apiAfterAsk === Number(q0?.total ?? 0) + 1,
      `${q0?.total} → ${apiAfterAsk}`)

    const expectTotalText = `共 ${apiAfterAsk} 个问题`
    let pageTotalTextSeen = ''
    const totalAfter = await waitFor(
      async () => {
        const el = page.locator('[data-testid="question-total"]')
        if ((await el.count()) === 0) {
          pageTotalTextSeen = '(元素不存在)'
          return null
        }
        const t = (await el.first().textContent())?.trim() ?? ''
        pageTotalTextSeen = t
        return t === expectTotalText ? t : null
      },
      { label: `页面总数变成「${expectTotalText}」`, timeoutMs: 15000 },
    )
    check('★ 页面问题总数 == 接口最新 total（是重新拉的接口，不是本地 +1）',
      Boolean(totalAfter), `页面「${pageTotalTextSeen}」/ 期望「${expectTotalText}」`)

    // ================================================================
    section('4. 问题详情：写回答（★ 回答数与接口一致）')
    // ================================================================
    await page.goto(`${PORTAL}/community/question/${created.id}`, {
      waitUntil: 'domcontentloaded',
      timeout: 30000,
    })
    await page.waitForSelector('[data-testid="question-detail-title"]', { timeout: 20000 })

    const detailTitle = (await page.textContent('[data-testid="question-detail-title"]'))?.trim()
    check('详情页标题 == 刚提问的标题', detailTitle === questionTitle, `页面「${detailTitle}」`)

    // ★ `total` 是字符串（见第 2 节的契约断言）→ 一律先 Number()
    const beforeAnswers = Number(
      (await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=10&questionId=${created.id}`))
        .body?.data?.total ?? 0,
    )

    await page.click('[data-testid="answer-open-btn"]')
    await page.waitForSelector('[data-testid="answer-content-input"]', { timeout: 10000 })
    await page.fill(
      '[data-testid="answer-content-input"]',
      `验收回答 ${stamp}：先把脚尖略外八、膝盖顺着脚尖方向推。`,
    )
    await page.click('[data-testid="answer-submit"]')

    const afterAnswers = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=10&questionId=${created.id}`)
        const t = Number(r.body?.data?.total ?? 0)
        return t === beforeAnswers + 1 ? t : null
      },
      { label: '回答落库', timeoutMs: 15000 },
    )
    check('★ 回答提交后接口回答数 +1', afterAnswers === beforeAnswers + 1, `${beforeAnswers} → ${afterAnswers}`)

    const qDetail = (await apiGet(page, `/ls/questions/${created.id}`)).body?.data
    // ★ 注意这两个量的类型不同：`answerTimes` 是 Integer（数字），`total` 是 String —— 必须先转换再比
    check('★ 问题接口的 answerTimes 与回答列表总数一致',
      Number(qDetail?.answerTimes) === afterAnswers,
      `answerTimes=${qDetail?.answerTimes} / 列表 total=${afterAnswers}`)

    // ================================================================
    section('5. ★★ 点赞是"切换语义"（点两次必须回到原值）')
    // ================================================================
    const replyId = (await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=1&questionId=${created.id}`))
      .body?.data?.list?.[0]?.id

    const likeBefore = (await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=10&questionId=${created.id}`))
      .body?.data?.list?.find((r) => String(r.id) === String(replyId))
    check('回答接口带回了 liked / likedTimes 两个字段（由后端实时查，非冗余）',
      typeof likeBefore?.liked === 'boolean' && typeof likeBefore?.likedTimes === 'number',
      `liked=${likeBefore?.liked} likedTimes=${likeBefore?.likedTimes}`)

    const likeTimesBefore = likeBefore?.likedTimes ?? 0

    // ★★ 断言顺序很重要：**先查页面、再查接口**。
    //   页面是"乐观更新"（点击即 +1），接口的计数要等 20 秒一轮的定时同步才追上 ——
    //   如果先等接口（最长 40 秒）再去查页面，就已经证明不了"页面是立刻响应的"了。
    await page.locator('[data-testid="answer-like-btn"]').first().click()

    let likeTextSeen = ''
    const likeTextOnPage = await waitFor(
      async () => {
        const t =
          (await page.locator('[data-testid="answer-like-times"]').first().textContent())?.trim() ?? ''
        likeTextSeen = t
        return t === String(likeTimesBefore + 1) ? t : null
      },
      { label: '页面点赞数（乐观更新）', timeoutMs: 3000, intervalMs: 300 },
    )
    check('★★ 点完立刻显示新计数（乐观更新 —— 服务端计数最长 20 秒后才同步）',
      Boolean(likeTextOnPage), `页面「${likeTextSeen}」/ 期望「${likeTimesBefore + 1}」`)

    /**
     * ★★ 点赞这条链路有**两套一致性**，断言必须分开写 —— 混在一起就会得出错误的结论。
     *
     *  ① `liked`（我赞没赞过）：**立刻一致**。
     *     它由 fm-learning 在返回列表时**实时**向 fm-remark 查（Redis 的 SET），
     *     所以点完下一次拉列表就一定是对的。→ 短超时（3 秒）足够，
     *     超时了就说明真的坏了，而不是"还没同步"。
     *
     *  ② `likedTimes`（点赞总数）：**最终一致，最长 20 秒**。
     *     fm-remark 的 `addLikeRecord` 只做两件事：写 Redis SET + **标脏**；
     *     真正的计数由 xxl-job `likedTimesSyncJob` 定时任务
     *     （`schedule_conf = 0/20 * * * * ?`，**每 20 秒一轮**）弹出脏标再推给 fm-learning。
     *     ★ 所以"点完立刻断言 likedTimes +1"**一定会失败**（第一版就是 15 秒超时），
     *       而失败信息看起来像"点赞功能坏了"。
     *     → 这里给 40 秒（两轮调度 + 余量），并把这条契约写进断言说明。
     */
    const likedImmediate = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=10&questionId=${created.id}`)
        const one = (r.body?.data?.list ?? []).find((x) => String(x.id) === String(replyId))
        return one?.liked === true ? one : null
      },
      { label: '点赞状态（毫秒级）', timeoutMs: 3000, intervalMs: 400 },
    )
    check('★ 点赞后 `liked` 立刻变 true（实时查 fm-remark，不是等定时任务）',
      Boolean(likedImmediate), likedImmediate ? 'liked=true' : '3 秒内仍为 false')

    const afterLike = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=10&questionId=${created.id}`)
        const one = (r.body?.data?.list ?? []).find((x) => String(x.id) === String(replyId))
        return one?.likedTimes === likeTimesBefore + 1 ? one : null
      },
      { label: '点赞数同步（★ 依赖 20 秒一轮的 xxl-job）', timeoutMs: 40000 },
    )
    check('★★ 点赞后 `likedTimes` +1（★ 最终一致：靠 20 秒一轮的定时同步，不是即时）',
      Boolean(afterLike), afterLike ? `likedTimes=${afterLike.likedTimes}` : `期望 ${likeTimesBefore + 1}（40 秒内未同步）`)

    // 两端最终必须收敛到同一个值：页面领先（乐观值）、接口追平（真值）
    check('★ 页面的乐观值与接口收敛后的真值一致（乐观更新不会算出错数）',
      likeTextOnPage === String(afterLike?.likedTimes),
      `页面「${likeTextOnPage}」/ 接口「${afterLike?.likedTimes}」`)

    // ---- 再点一次：验证「切换语义」----
    // ★ 断言只要求 `liked` 回到 false（即时一致的量），
    //   计数的回归同样要等下一轮同步，所以单独给它更长的窗口
    await page.locator('[data-testid="answer-like-btn"]').first().click()

    // 同样先看页面（乐观回退），再看接口
    let unlikeTextSeen = ''
    const unlikeTextOnPage = await waitFor(
      async () => {
        const t =
          (await page.locator('[data-testid="answer-like-times"]').first().textContent())?.trim() ?? ''
        unlikeTextSeen = t
        return t === String(likeTimesBefore) ? t : null
      },
      { label: '页面点赞数回退（乐观更新）', timeoutMs: 3000, intervalMs: 300 },
    )
    check('★★ 取消赞后页面计数立刻回退到原值（乐观更新）',
      Boolean(unlikeTextOnPage), `页面「${unlikeTextSeen}」/ 期望「${likeTimesBefore}」`)

    const unlikeImmediate = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=10&questionId=${created.id}`)
        const one = (r.body?.data?.list ?? []).find((x) => String(x.id) === String(replyId))
        return one?.liked === false ? one : null
      },
      { label: '取消赞状态（毫秒级）', timeoutMs: 3000, intervalMs: 400 },
    )
    check('★★ 再点一次后 `liked` 立刻回到 false（切换语义，不是只增不减）',
      Boolean(unlikeImmediate), unlikeImmediate ? 'liked=false' : '3 秒内仍为 true')

    const afterUnlike = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=10&questionId=${created.id}`)
        const one = (r.body?.data?.list ?? []).find((x) => String(x.id) === String(replyId))
        return one?.likedTimes === likeTimesBefore ? one : null
      },
      { label: '取消赞后计数回退（同样等定时同步）', timeoutMs: 40000 },
    )
    check('★★ 取消赞后 `likedTimes` 回到原值（计数是"按集合基数全量同步"，不是自减）',
      Boolean(afterUnlike),
      afterUnlike ? `likedTimes=${afterUnlike.likedTimes}（原值 ${likeTimesBefore}）` : `期望回到 ${likeTimesBefore}（40 秒内未同步）`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-5-02-question-detail.png'), fullPage: true })

    // ================================================================
    section('6. 笔记：写笔记（★ 页面条数 == 接口 total）')
    // ================================================================
    await page.goto(`${PORTAL}/community?courseId=${ctxCourse}`, {
      waitUntil: 'domcontentloaded',
      timeout: 30000,
    })
    await page.waitForTimeout(1200)
    await switchTab(page, '笔记')

    const n0 = (await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=10&courseId=${ctxCourse}`)).body?.data
    /**
     * ★ 记一个"含采集副本"的基线，供第 10 节自检用。
     *
     * 为什么不能只用上面那个 `n0`：**公共列表默认会过滤掉采集来的副本**
     * （实测：courseId=544 的普通列表 `isGathered` 有 0 条，而 `onlyMine=true` 里有 5 条）。
     * 所以拿普通列表做"删干净了"的自检，会**漏掉没删掉的副本** ——
     * 第一版就是这样：副本留在库里，自检却是「5 → 5」通过（典型的假绿）。
     */
    const mineNotesBaseline = Number(
      (await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=1&courseId=${ctxCourse}&onlyMine=true`))
        .body?.data?.total ?? 0,
    )
    const noteTotalText = (await page.textContent('[data-testid="note-total"]'))?.trim()
    check('★ 笔记页「共 N 条笔记」== 接口同 courseId 的 total',
      noteTotalText === `共 ${n0?.total ?? 0} 条笔记`,
      `页面「${noteTotalText}」/ 接口 ${n0?.total ?? 0}`)

    const noteContent = `验收笔记 ${stamp}：下蹲到底时保持脚踝灵活，必要时垫一片杠铃片。`
    await page.click('[data-testid="note-write-btn"]')
    await page.waitForSelector('[data-testid="note-chapter"]', { timeout: 10000 })
    await page.waitForTimeout(800) // 等弹窗动画
    // ★ 笔记也要求「训练日 + 动作」（NoteFormDTO 的三个 id 全是 @NotNull，漏了就是 400）
    await selectFirstOption('note-chapter')
    await selectFirstOption('note-section')
    await page.fill('[data-testid="note-content"]', noteContent)
    await page.click('[data-testid="note-submit"]')

    const noteCreated = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=50&courseId=${ctxCourse}&onlyMine=true`)
        return (r.body?.data?.list ?? []).find((n) => n.content === noteContent) ?? null
      },
      { label: '笔记落库', timeoutMs: 15000 },
    )
    check('★ 笔记写入成功（同一课程下"只看我的"能查到）',
      Boolean(noteCreated), noteCreated ? `id=${noteCreated.id}` : '未查到')
    createdIds.note = noteCreated?.id ?? null

    // ================================================================
    section('7. ★★ 采集笔记 = 在自己名下"复制一条"（不是打标记）')
    // ================================================================
    const allNotes = (await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=50&courseId=${ctxCourse}`))
      .body?.data?.list ?? []
    // 优先找**别人的**、还没采集过的笔记
    const target =
      allNotes.find((n) => String(n.userId) !== String(noteCreated?.userId) && !n.gatheredByMe) ??
      allNotes.find((n) => !n.gatheredByMe)

    if (!target) {
      console.log('     （这门课下没有可采集的笔记，跳过采集断言）')
    } else {
      // ★ `total` 是字符串 → Number() 之后再算数（否则 `mineBefore + 1` 会变成 `"4" + 1 === "41"`）
      const mineBefore = Number(
        (await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=50&courseId=${ctxCourse}&onlyMine=true`))
          .body?.data?.total ?? 0,
      )

      const gatherRes = await apiPost(page, `/ls/notes/gathers/${target.id}`)
      check('采集接口调用成功', gatherRes.body?.code === 200,
        `code=${gatherRes.body?.code} msg=${gatherRes.body?.msg}`)

      const mineAfter = await waitFor(
        async () => {
          const r = await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=50&courseId=${ctxCourse}&onlyMine=true`)
          const t = Number(r.body?.data?.total ?? 0)
          return t > mineBefore ? t : null
        },
        { label: '我的笔记 +1', timeoutMs: 15000 },
      )
      check('★★ 采集后「我的笔记」多了一条（采集是复制，不是打标记）',
        mineAfter === mineBefore + 1,
        `${mineBefore} → ${mineAfter}`)

      const gathered = await waitFor(
        async () => {
          const r = await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=50&courseId=${ctxCourse}&onlyMine=true`)
          return (r.body?.data?.list ?? []).find((n) => n.isGathered === true) ?? null
        },
        { label: '采集标记可见', timeoutMs: 10000 },
      )
      check('★ 复制出来的那条标了 isGathered=true（原作者保留在 authorId）',
        Boolean(gathered),
        gathered ? `id=${gathered.id} authorId=${gathered.authorId} userId=${gathered.userId}` : '未找到')
      createdIds.gathered = gathered?.id ?? null
      // ★ 取消采集要传**原笔记 id**（后端 gathers/{id} 的入参语义），所以要单独记下来
      createdIds.gatheredSource = target?.id ?? null
    }

    await page.waitForTimeout(800)
    await switchTab(page, '笔记')
    await page.screenshot({ path: join(SHOT_DIR, 'm7-5-03-community-note.png'), fullPage: true })

    // ================================================================
    section('8. 站内信（★ 未读数与接口一致 + 标记已读生效）')
    // ================================================================
    const unreadApi = (await apiGet(page, '/sms/messages/unread-count')).body?.data ?? 0

    await page.goto(`${PORTAL}/messages`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1200)

    const msgTotalText = (await page.textContent('[data-testid="message-total"]'))?.trim()
    const msgApi = (await apiGet(page, '/sms/messages/page?pageNo=1&pageSize=10')).body?.data
    check('★ 消息页「共 N 条」== 接口 total',
      msgTotalText === `共 ${msgApi?.total ?? 0} 条`,
      `页面「${msgTotalText}」/ 接口 ${msgApi?.total ?? 0}`)

    const badgeOnPage = await page.locator('[data-testid="header-message"] .el-badge__content').count()
    check('★ 顶栏消息角标的显示与否与接口未读数一致',
      unreadApi > 0 ? badgeOnPage > 0 : badgeOnPage === 0,
      `接口未读 ${unreadApi}，角标元素 ${badgeOnPage} 个`)

    const unreadItems = await page.locator('[data-testid="message-unread-dot"]').count()
    const unreadFirstPage = (msgApi?.list ?? []).filter((m) => m.isRead === 0).length
    check('★ 页面未读标记数 == 接口第 1 页的未读条数',
      unreadItems === unreadFirstPage,
      `页面 ${unreadItems} 个 / 接口 ${unreadFirstPage} 条`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-5-04-messages.png'), fullPage: true })

    if (unreadApi > 0) {
      await page.locator('[data-testid="message-mark-read"]').first().click()
      const unreadAfter = await waitFor(
        async () => {
          const r = await apiGet(page, '/sms/messages/unread-count')
          const n = r.body?.data ?? 0
          return n < unreadApi ? n : null
        },
        { label: '未读数减少', timeoutMs: 12000 },
      )
      check('★ 标记一条已读后，接口未读数减少',
        unreadAfter === unreadApi - 1,
        `${unreadApi} → ${unreadAfter}`)
    } else {
      console.log('     （当前没有未读消息，跳过标记已读断言）')
    }

    // ================================================================
    section('9. 浏览器运行时健康度')
    // ================================================================
    await page.waitForTimeout(800)
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
    // ★ 注意：第 1 节故意制造了 3 个 400（用来钉住"列表必须有上下文"这条契约），
    //   所以这里把它们排除后再断言"其余请求没有业务码错误"。
    const unexpected = businessErrors.filter(
      (e) => !/\/ls\/(questions|notes|replies)\/page\?pageNo=1&pageSize=5 → 400/.test(e),
    )
    check('★★ 除刻意构造的那 3 个 400 之外，全程没有「业务码非 200」的响应',
      unexpected.length === 0,
      unexpected.slice(0, 5).join(' | ') || `共 ${unexpected.length} 条`)

    // ================================================================
    section('10. 收拾现场（脚本自己写的内容自己删掉）')
    // ================================================================
    /**
     * ★★ 为什么必须做：M7-5 是本项目**唯一会往库里写内容**的验收脚本
     *    （提问 / 回答 / 笔记 / 采集副本）。不清理的后果不是"看着脏"，而是
     *    **断言会慢慢失去意义** —— 列表页第一页会被历次运行的
     *    「验收提问 221309」占满，"页面条数 == 接口 total"就测不到真实内容了
     *    （实测跑了 5 次后，库里 11 个问题、9 条笔记**全部**来自脚本）。
     *
     * ★ 清理原则（沿用项目既有约定）：
     *   ① **只删本次运行创建的 id**（`createdIds`），不做任何"按标题模糊匹配"的删除；
     *   ② 删完**必须查计数自检**，确认真的回到基线 —— 而不是"删了就当成功"。
     *
     * ⚠️ 已知残留：回答没有"学员自删"的接口（详情页的删除按钮也是如实说明的），
     *    所以本次写入的那条回答会留下 —— 但它只挂在被删掉的问题下，页面看不到。
     */
    if (createdIds.gatheredSource) {
      // ★ 必须传**原笔记 id**：传副本自己的 id 时接口会返回 200 但什么都不删（实测）
      await apiDelete(page, `/ls/notes/gathers/${createdIds.gatheredSource}`)
    }
    if (createdIds.note) {
      await apiDelete(page, `/ls/notes/${createdIds.note}`)
    }
    if (createdIds.question) {
      await apiDelete(page, `/ls/questions/${createdIds.question}`)
    }

    // ★ 自检口径必须是 `onlyMine`（含采集副本）—— 普通列表会漏掉副本，导致"假绿"
    const mineNotesBack = await waitFor(
      async () => {
        const t = Number(
          (await apiGet(page, `/ls/notes/page?pageNo=1&pageSize=1&courseId=${ctxCourse}&onlyMine=true`))
            .body?.data?.total ?? -1,
        )
        return t === mineNotesBaseline ? t : null
      },
      { label: '我的笔记回到基线', timeoutMs: 10000 },
    )
    check('★「我的笔记」回到运行前基线（含采集副本；用普通列表自检会漏掉副本 → 假绿）',
      mineNotesBack === mineNotesBaseline, `${mineNotesBaseline} → ${mineNotesBack}`)

    const questionsBack = await waitFor(
      async () => {
        const t = Number(
          (await apiGet(page, `/ls/questions/page?pageNo=1&pageSize=1&courseId=${ctxCourse}`)).body?.data?.total ?? -1,
        )
        return t === Number(q0?.total ?? 0) ? t : null
      },
      { label: '问题数回到基线', timeoutMs: 10000 },
    )
    check('★ 问题数回到运行前的基线（删干净了）',
      questionsBack === Number(q0?.total ?? 0), `${q0?.total} → ${questionsBack}`)
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
