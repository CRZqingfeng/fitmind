#!/usr/bin/env node
/**
 * ============================================================
 * M10-1 第 1 期前端验收：播放页「边看边记」
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-learning / fm-media(8083)
 *   · 前端：fitmind-portal dev server (5173)
 *   · 数据：演示学员（13800010001）已报名至少一门**有视频**的课
 *
 * 用法：cd frontend && node scripts/accept-web-m10-1.mjs
 *
 * ============================================================
 * ★★ 本脚本要证明的三件事（按重要性排序）
 * ============================================================
 *
 * 1. **★ 时间戳真的来自播放器，不是编的**。
 *    先把 video 的 `currentTime` 拨到 5 秒，再记笔记，然后断言
 *    **落库的 `noteMoment` 就是 5**（接口级），**页面上显示的就是 `00:05`**（界面级）。
 *    ★ 两条都要，因为它们是不同的失败模式：
 *      只看页面 → 前端写死一个假时间也能过；只看接口 → 页面显示错也看不出来。
 *
 * 2. **★★ 点时间戳能跳回那一秒**。
 *    这是整套笔记的**唯一价值**：没有它，`note_moment` 只是一串没人看的数字。
 *    做法：先把视频拨到 60 秒（远离目标），再点 `00:05` 的时间戳，
 *    断言 `currentTime` 真的回到了 5 附近。
 *    ★ 必须先挪远再点 —— 如果原地不动，"跳转没生效"和"本来就停在那"看起来一模一样。
 *
 * 3. **布局真的调换了（播放器在左、侧栏在右）**。
 *    用**几何断言**（比较 boundingBox 的 x）而不是读 class 名 ——
 *    class 名改了不影响布局，而布局错了 class 名照样对。
 *    这条同时是"12b 那类回归"的守门员：目录的 `data-testid` 必须还在。
 *
 * ============================================================
 * ★ 会留下痕迹（如实记录，不假装无副作用）
 * ============================================================
 * · `POST /ls/notes` **会发积分事件**（M3-3：写笔记送积分，每日有上限）——
 *   脚本会删掉自己造的笔记，**但积分不会退**。这是既有设计，不是本脚本的 bug。
 * · 因此脚本**使用固定内容**（含 `__m10_1_probe__` 标记），跑完即删；
 *   万一中断，按这个标记也查得到残留。
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
const STUDENT_PASSWORD = '123456'
/**
 * ★ 教练账号（`13800010002`，库里唯一的 `type=3`）。
 *
 * ⚠️ **当前用不了**：实测用户端密码登录对它稳定返回 500（`id=3` 管理员同样 500，
 *    只有 `id=1` 学员能登）。根因**未定位**，已登记在 `docs/待修复问题清单.md`。
 *    → 所以第 2 期的身份徽章断言改成了"学员"（见 §4 的注释）。
 *    一旦这个登录问题修好，把这里换回教练身份，断言就能升级成 `[教练]`。
 */
const COACH_PHONE = '13800010002'
const TOKEN_KEY = 'fitmind:token'

/** 记笔记时用的固定内容（跑完即删；带标记便于人工兜底清理） */
const PROBE_TEXT = 'M10-1 验收：这个动作肩胛骨要收紧（__m10_1_probe__）'
/** 记这条笔记时把视频拨到的秒数 */
const PROBE_MOMENT = 5

/** 第 2 期：提问用的描述（★ 故意不传 title，验证服务端自动兜底） */
const QA_TEXT = '手腕内侧发酸，是握法问题吗（__m10_1_qa_probe__）'
const QA_PROBE = '__m10_1_qa_probe__'
/** 第 2 期：教练的回答内容 */
const REPLY_TEXT = '握太窄了，加宽一指试试（__m10_1_reply_probe__）'
const REPLY_PROBE = '__m10_1_reply_probe__'

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

/**
 * 登录并**把 token 写进页面 localStorage**（让后续 UI 操作以这个身份进行）。
 */
async function loginByApi(page, phone, password) {
  const token = await loginPhone(phone, password)
  await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await page.evaluate((t) => localStorage.setItem('fitmind:token', t), token)
  return token
}

/**
 * 只登录拿 token，**不碰页面**。
 *
 * ★ 第 2 期需要"换个身份做事"（学员提问、教练回答），但**不能**用 `loginByApi` ——
 *   那个会把 localStorage 里的 token 换掉，于是后面的 UI 操作全变成教练视角，
 *   学员的断言就会莫名其妙地失败。**"切换身份"和"保持当前身份"必须是两条路。**
 */
async function loginPhone(phone, password) {
  const res = await fetch(`${GATEWAY}/as/accounts/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 1, cellPhone: phone, password, rememberMe: true }),
  })
  const body = await res.json()
  if (!body?.data) throw new Error(`登录失败(${phone})：code=${body?.code} msg=${body?.msg}`)
  return body.data
}

/** 轮询直到条件成立（点赞数走 MQ 异步刷新，是必须等的） */
async function waitFor(fn, { timeoutMs = 12000, intervalMs = 800, label = '' } = {}) {
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

/** 读播放器当前秒数 */
const readVideoTime = (page) =>
  page.evaluate(() => {
    const v = document.querySelector('[data-testid="learn-video"]')
    return v ? v.currentTime : -1
  })

/**
 * 把播放器拨到指定秒。
 *
 * ★ 手动派发一次 `timeupdate`：真实浏览器里设置 `currentTime` 会异步派发它，
 *   但"等异步"在验收脚本里就是 flaky 的来源。手动补一次能让 Vue 的
 *   `@timeupdate` 处理器**立刻**执行 —— 断言的时序才是确定的。
 */
async function seekVideo(page, seconds) {
  await page.evaluate((s) => {
    const v = document.querySelector('[data-testid="learn-video"]')
    if (!v) return
    v.currentTime = s
    v.dispatchEvent(new Event('timeupdate'))
  }, seconds)
  await page.waitForTimeout(300)
}

async function main() {
  console.log('============================================================')
  console.log(' FitMind M10-1 第 1 期验收（播放页：边看边记）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/ls/lessons/page`, 'fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const page = await browser.newPage()

  /** 页面运行时异常（健康度） */
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e?.message ?? e)))

  let createdNoteId = null
  /** 第 2 期：本次造的问题 id（跑完即删，删问题会级联删掉它的回答） */
  let createdQuestionId = null

  try {
    // ================================================================
    section('0. 登录')
    // ================================================================
    await loginByApi(page, STUDENT_PHONE, STUDENT_PASSWORD)
    check('登录成功（密码登录，避开验证码频控）', true)

    // 取一门已报名的课
    const lessonsResp = await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100')
    const myLessons = lessonsResp.body?.data?.list ?? []
    check('学员至少报名了一门课', myLessons.length > 0, `共 ${myLessons.length} 门`)
    if (!myLessons.length) {
      throw new Error('演示学员没有已报名的课程，无法验收播放页')
    }
    const courseId = String(myLessons[0].courseId)

    // ================================================================
    section('1. 播放页新布局（★ 播放器在左、侧栏在右）')
    // ================================================================
    await page.goto(`${PORTAL}/learn/${courseId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="learn-section"]', { timeout: 25000 })
    await page.waitForTimeout(1200)

    // ★ 目录的 testid 必须还在（它是 m7-4 / m7-6 的接口，被搬进 Tab 不能弄丢）
    const catasResp = await apiGet(page, `/cs/courses/${courseId}/catalogs`)
    const apiSections = (catasResp.body?.data ?? []).flatMap((d) => d.sections ?? [])
    const pageSections = await page.locator('[data-testid="learn-section"]').count()
    check('★ 目录搬进右侧栏后，动作数仍 == 接口 catalogs',
      pageSections === apiSections.length && pageSections > 0,
      `页面 ${pageSections} 个 / 接口 ${apiSections.length} 个`)

    // 右侧栏页签
    const tabs = page.locator('[data-testid="learn-side-tabs"]')
    check('右侧栏出现「目录 / 笔记」页签', (await tabs.count()) > 0)
    const tabTexts = await tabs.locator('.el-tabs__item').allTextContents()
    check('★ 页签就是「目录」和「笔记」两个',
      tabTexts.length === 2 && tabTexts.some((t) => t.includes('目录')) && tabTexts.some((t) => t.includes('笔记')),
      `实际 [${tabTexts.join(' / ')}]`)

    // 快捷笔记条
    check('播放器下方出现快捷笔记条',
      (await page.locator('[data-testid="note-composer"]').count()) > 0)

    // ★★ 几何断言：播放器在左、侧栏在右
    const videoBox = await page.locator('[data-testid="learn-video"]').boundingBox()
    const sideBox = await page.locator('[data-testid="learn-side-tabs"]').boundingBox()
    if (videoBox && sideBox) {
      check('★★ 视频在左、侧栏在右（布局真的调换了）',
        videoBox.x + videoBox.width <= sideBox.x + 4,
        `视频右边缘 x=${Math.round(videoBox.x + videoBox.width)} / 侧栏左边缘 x=${Math.round(sideBox.x)}`)
      check('★ 视频是主体（宽度 > 侧栏）',
        videoBox.width > sideBox.width,
        `视频 ${Math.round(videoBox.width)}px / 侧栏 ${Math.round(sideBox.width)}px`)
    } else {
      check('★★ 视频与侧栏都渲染出来了', false, 'boundingBox 为空')
    }

    await page.screenshot({ path: join(SHOT_DIR, 'm10-1-01-layout.png'), fullPage: true })

    // ================================================================
    section('2. 记笔记（★ 时间戳来自播放器）')
    // ================================================================
    const hasVideo = (await page.locator('[data-testid="learn-video"]').count()) > 0
    check('当前动作有可播放的视频（前置数据要求）', hasVideo)
    if (!hasVideo) {
      throw new Error('第一个动作没有绑定视频 —— 请在管理端给该动作绑一个媒资再跑本脚本')
    }

    await seekVideo(page, PROBE_MOMENT)
    const clockText = (await page.textContent('[data-testid="note-composer-clock"]'))?.trim()
    check('★ 笔记条的时钟跟着播放器走（拨到 5 秒 → 显示 00:05）',
      clockText === '00:05', `实际「${clockText}」`)

    await page.fill('[data-testid="note-composer-input"]', PROBE_TEXT)
    await page.click('[data-testid="note-composer-submit"]')
    await page.waitForTimeout(1600)

    // 接口级：落库的 noteMoment 就是 5
    const sectionId = String(apiSections[0]?.id ?? '')
    const notesResp = await apiGet(
      page,
      `/ls/notes/page?pageNo=1&pageSize=50&courseId=${courseId}&sectionId=${sectionId}&onlyMine=true`,
    )
    const notes = notesResp.body?.data?.list ?? []
    const mine = notes.find((n) => (n.content ?? '').includes('__m10_1_probe__'))
    check('★ 笔记真的落库了（接口查得到）', Boolean(mine), `查回 ${notes.length} 条`)
    check('★★ 落库的 noteMoment == 播放器当时的秒数',
      Number(mine?.noteMoment) === PROBE_MOMENT,
      `接口 noteMoment=${mine?.noteMoment} / 期望 ${PROBE_MOMENT}`)
    check('★ 笔记默认是私有的（isPrivate）',
      mine?.isPrivate === true, `isPrivate=${mine?.isPrivate}`)
    createdNoteId = mine?.id ? String(mine.id) : null

    // 界面级：页面列表里显示 00:05
    /**
     * ★★ 必须**锁定"我刚写的那一条"**，不能写成"列表里存在 00:05 的时间戳" ——
     *   后者有个恶心的假绿：如果这个动作历史上就有一条 `00:05` 的笔记
     *   （用户手工记过、或上一次脚本中断留下的），那么**这次笔记压根没写成功**，
     *   断言照样会绿。判据必须包含"这条是我造的"（内容里的 probe 标记）。
     */
    const probeItem = page
      .locator('[data-testid="note-item"]')
      .filter({ hasText: '__m10_1_probe__' })
    const probeItemCount = await probeItem.count()
    const probeStampText = probeItemCount
      ? (await probeItem.locator('[data-testid="note-stamp"]').first().textContent())?.trim()
      : '(没找到刚写的那条)'
    check('★ 刚写的这条出现在列表里，且时间戳就是 00:05',
      probeItemCount === 1 && probeStampText === '00:05',
      `命中 ${probeItemCount} 条 / 时间戳「${probeStampText}」`)

    await page.screenshot({ path: join(SHOT_DIR, 'm10-1-02-note-saved.png'), fullPage: true })

    // ================================================================
    section('3. ★★ 点时间戳跳回那一秒（整套笔记的价值所在）')
    // ================================================================
    await seekVideo(page, 60) // ★ 先挪远，否则"没跳"和"本来就在那"分不清
    const before = await readVideoTime(page)
    check('已把视频拨到 60 秒（为下一步做准备）', before > 50, `currentTime=${before.toFixed(1)}`)

    // 点的必须是上一步锁定的那一条（同一个 locator）
    await probeItem.locator('[data-testid="note-stamp"]').first().click()
    await page.waitForTimeout(800)

    const after = await readVideoTime(page)
    check('★★ 点 00:05 的时间戳 → 视频真的跳回第 5 秒',
      Math.abs(after - PROBE_MOMENT) < 1.5,
      `点击后 currentTime=${after.toFixed(2)}（期望 ≈${PROBE_MOMENT}）`)

    // ================================================================
    section('4. ★ 问答评论区（M10-1 第 2 期）')
    // ================================================================
    check('评论区渲染出来了（qa-panel）',
      (await page.locator('[data-testid="qa-panel"]').count()) > 0)

    // ---------- 学员提问：★ 故意不填标题 ----------
    await page.fill('[data-testid="qa-input"]', QA_TEXT)
    await page.click('[data-testid="qa-submit"]')
    await page.waitForTimeout(2200)

    const qResp = await apiGet(
      page,
      `/ls/questions/page?pageNo=1&pageSize=20&courseId=${courseId}&sectionId=${sectionId}&onlyMine=true`,
    )
    const qList = qResp.body?.data?.list ?? []
    const myQ = qList.find((q) => (q.description ?? '').includes(QA_PROBE))
    check('★ 提问落库了（接口查得到）', Boolean(myQ), `查回 ${qList.length} 条`)
    createdQuestionId = myQ?.id ? String(myQ.id) : null

    /**
     * ★★ 这条对应本期的后端改动：`QuestionFormDTO.title` 去掉了 `@NotBlank`。
     *   断言"**没传 title 也能发出去，且服务端补了一个非空标题**"——
     *   如果只断言"code=200"，那么"标题是空的"这种半成品照样会绿。
     *   长度上限 21 = 20 个字符 + 省略号（`AUTO_TITLE_MAX`）。
     */
    const autoTitle = myQ?.title ?? ''
    check('★★ 不填标题也能发，且服务端自动补了标题（前 20 字 + 省略号）',
      autoTitle.length > 0 && autoTitle.length <= 21,
      `title=「${autoTitle}」长度 ${autoTitle.length}`)

    const myQItem = page.locator('[data-testid="qa-item"]').filter({ hasText: QA_PROBE })
    check('★ 刚提的问题出现在评论列表里', (await myQItem.count()) === 1)

    // ---------- 回答 ----------
    /**
     * ★★ 这里本来想用**教练身份**回答，好断言 `[教练]` 徽章。做不到 ——
     *   实测（2026-10-04）：用户端密码登录，`id=1` 学员 200，
     *   而 `id=2` 教练 / `id=3` 管理员**都返回 500「系统繁忙」**（稳定复现）。
     *   ★ **根因未定位**：`login_record` 的 INSERT 语句里确实没有 `user_id`
     *     （而该列 `NOT NULL` 无默认值），但那是 **2026-09-15 的历史日志**，
     *     且现在 `user_id=1` / `user_id=3` 每天都有新记录 —— 说明当前代码是带 `user_id` 的。
     *     **所以不能把这个当结论。** 已登记到 `docs/待修复问题清单.md`。
     *   → 于是在这里**用当前学员身份回答**（走 UI 的「回复」链路，顺带验证那条交互），
     *     徽章断言相应改成「学员」。★ 但"学员"是 map 的**兜底值**，
     *     只看文案证明不了映射正确 —— 所以**同时断言接口回传的 `userType === 2`**。
     */
    await myQItem.locator('[data-testid="qa-reply-btn"]').click()
    await page.waitForTimeout(700)
    await page.fill('[data-testid="qa-reply-input"]', REPLY_TEXT)
    await page.click('[data-testid="qa-reply-submit"]')
    await page.waitForTimeout(2400)

    const rResp1 = await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=20&questionId=${myQ.id}`)
    const rList1 = rResp1.body?.data?.list ?? []
    const myReply = rList1.find((x) => (x.content ?? '').includes(REPLY_PROBE))
    check('★ 回答落库了（走 UI 的「回复」链路，不是直接调接口）',
      Boolean(myReply), `该问题下查回 ${rList1.length} 条回答`)

    // ---------- 页面上看这条回答 ----------
    // 「回复」动作本身会把该问题展开，这里直接定位即可（顺便验证"内联展开"而不是跳页）
    const replyItem = myQItem
      .locator('[data-testid="qa-reply-item"]')
      .filter({ hasText: REPLY_PROBE })
    check('★ 回答**内联展开**出来了（不跳详情页）', (await replyItem.count()) === 1)

    const replyName = (await replyItem.locator('.reply-name').textContent())?.trim()
    check('★ 回答者昵称渲染出来了（B 站评论区三要素之一）',
      Boolean(replyName) && replyName !== '匿名用户', `昵称「${replyName}」`)

    const badge = (await replyItem.locator('[data-testid="qa-reply-usertype"]').textContent())?.trim()
    check('★★ 身份徽章与接口 userType 一致（页面「学员」＋ 接口 userType==2）',
      badge === '学员' && Number(myReply?.userType) === 2,
      `徽章「${badge}」/ 接口 userType=${myReply?.userType}`)

    // ---------- 点赞 ----------
    await replyItem.locator('[data-testid="qa-reply-like"]').click()
    /**
     * ★★ 必须等**接口**里的 `likedTimes` 落成 1，不能只看页面数字。
     *   原因是点赞走的是**乐观更新**（`toggleLikeWithRollback` 先改页面、失败再回滚）——
     *   所以"页面上变成 1"这件事，**在接口失败的情形下也会短暂为真**。
     *   而且后端刷新 `liked_times` 是**走 MQ 异步**的，所以要轮询等它。
     */
    const likedOk = await waitFor(
      async () => {
        const r = await apiGet(page, `/ls/replies/page?pageNo=1&pageSize=20&questionId=${myQ.id}`)
        const one = (r.body?.data?.list ?? []).find((x) => (x.content ?? '').includes(REPLY_PROBE))
        return Number(one?.likedTimes) === 1 ? one : null
      },
      /**
       * ★ 超时给到 25 秒（默认是 12 秒）。
       *   实测（2026-10-04）：同一条断言在一次运行里超时、紧跟的复跑就过了 ——
       *   因为 `liked_times` 是**走 MQ 异步刷新**的（`QaLikeTimesListener`），
       *   而 RabbitMQ 的投递 + 消费 + 重试偶尔会慢过 12 秒。
       *   12 秒的窗口把一个"最终一致"的链路当成了"立刻一致"，就会偶发假失败。
       */
      { timeoutMs: 25000, label: '回答的 likedTimes 落库（MQ 异步，最多等 25 秒）' },
    )
    check('★★ 点赞真的写进去了（接口 likedTimes == 1，不只是页面乐观更新）',
      Boolean(likedOk), likedOk ? 'likedTimes=1' : '轮询超时仍不为 1')

    await page.screenshot({ path: join(SHOT_DIR, 'm10-1-03-qa-comment.png'), fullPage: true })

    // ================================================================
    section('5. 清理（跑完即删）')
    // ================================================================
    if (createdQuestionId) {
      const delQ = await api(page, 'DELETE', `/ls/questions/${createdQuestionId}`)
      // ★ 后端是"物理删除 + 级联删掉该问题下的所有回答"，所以这一步就干净了
      check('清理：删掉本次造的问题（连带它的回答）',
        delQ.body?.code === 200, `code=${delQ.body?.code}`)
    }

    if (createdNoteId) {
      const del = await api(page, 'DELETE', `/ls/notes/${createdNoteId}`)
      check('删掉本次造的笔记', del.body?.code === 200 || del.status === 200,
        `code=${del.body?.code}`)
      const after2 = await apiGet(
        page,
        `/ls/notes/page?pageNo=1&pageSize=50&courseId=${courseId}&sectionId=${sectionId}&onlyMine=true`,
      )
      const left = (after2.body?.data?.list ?? []).filter((n) =>
        (n.content ?? '').includes('__m10_1_probe__'),
      )
      check('★ 接口里确实查不到了（不是只删了页面上的）', left.length === 0, `剩余 ${left.length} 条`)
    } else {
      check('取到了待删除的笔记 id', false, '未拿到 id，可能上一步就没落库')
    }

    // ================================================================
    section('6. ★ 答疑广场（M10-1 第 3 期）')
    // ================================================================
    /**
     * ★★ 这一节要证明的**核心一件事**：
     *   **不带 courseId 打开社区页，也有内容** —— 而不是像改造前那样
     *   （"你还没有报名任何课程"的空白页 / 或退化成"我的第一门课的问答列表"）。
     *
     * ⚠️ 同时必须证明**老行为没被破坏**：`/community?courseId=X` 仍然按课程筛
     *   （那是 `accept-web-m7-5` 钉着的契约，这里顺手也验一次）。
     */
    await page.goto(`${PORTAL}/community`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1800)

    // 不带任何上下文时，后端仍要求显式 scope —— 直接确认接口这条路是通的
    const allResp = await apiGet(page, '/ls/questions/page?pageNo=1&pageSize=10&scope=ALL')
    const allList = allResp.body?.data?.list ?? []
    check('★ 全站广场接口可用（scope=ALL）', allResp.body?.code === 200,
      `code=${allResp.body?.code} 共 ${allResp.body?.data?.total ?? 0} 条`)

    const feedTabs = page.locator('[data-testid="q-feed"]')
    check('广场出现「待回答 / 最新 / 最热 / 我的」四个页签', (await feedTabs.count()) > 0)
    const feedTexts = await feedTabs.locator('.el-radio-button__inner').allTextContents()
    check('★ 页签就是那四个',
      ['待回答', '最新', '最热', '我的'].every((t) => feedTexts.some((x) => x.includes(t))),
      `实际 [${feedTexts.map((t) => t.trim()).join(' / ')}]`)

    /**
     * ★★ 最要紧的一条：**没选课程时列表不是空的**。
     *   库里此刻至少有 2 条别人的问题（id 91/92 是 09-25 的历史数据），所以 > 0 是硬条件。
     */
    const pageTotalBefore = await page.textContent('[data-testid="question-total"]')
    check('★★ 不带 courseId 打开 community，页面**有内容**（不再是被遮住/退化的空页）',
      /共\s*[1-9]\d*\s*个问题/.test(pageTotalBefore ?? ''),
      `页面「${pageTotalBefore?.trim()}」/ 接口 total=${allResp.body?.data?.total ?? 0}`)

    const cards = await page.locator('[data-testid="question-item"]').count()
    check('★ 广场卡片数 == 接口 scope=ALL 第 1 页条数',
      cards === Math.min(allList.length, 10), `页面 ${cards} 张 / 接口 ${allList.length} 条`)

    // ---------- 「待回答」页签 ----------
    await page.locator('[data-testid="q-feed"] .el-radio-button__inner', { hasText: '待回答' }).first().click()
    await page.waitForTimeout(1500)
    const unResp = await apiGet(page, '/ls/questions/page?pageNo=1&pageSize=10&scope=ALL&unanswered=true')
    const unList = unResp.body?.data?.list ?? []
    const unPageCards = await page.locator('[data-testid="question-item"]').count()
    check('★ 「待回答」页签：卡片数 == 接口 unanswered 条数',
      unPageCards === unList.length, `页面 ${unPageCards} 张 / 接口 ${unList.length} 条`)

    /**
     * ★★ 断言"页面上这几条**确实都是待回答**"，而不只是"条数对上了"。
     *   条数相同有两种可能：筛选真的生效了，或者**两边都碰巧是同一批**（比如筛选没生效、
     *   但恰好第一页全是没有回答的）。所以要从**页面**上读答案数来验。
     */
    const answerTimesOnPage = await page
      .locator('[data-testid="question-answer-times"]')
      .allTextContents()
    check('★★ 页面上每条都显示「0 个回答」（筛选真的生效，不只是条数巧合）',
      answerTimesOnPage.length > 0 && answerTimesOnPage.every((t) => t.trim().startsWith('0')),
      `页面上的回答数 [${answerTimesOnPage.map((t) => t.trim()).join(', ')}]`)

    // ---------- 「最热」页签 ----------
    await page.locator('[data-testid="q-feed"] .el-radio-button__inner', { hasText: '最热' }).first().click()
    await page.waitForTimeout(1500)
    const hotResp = await apiGet(page, '/ls/questions/page?pageNo=1&pageSize=10&scope=ALL&sort=hot')
    const hotList = hotResp.body?.data?.list ?? []
    const hotNums = hotList.map((q) => Number(q.answerTimes ?? 0))
    check('★ 「最热」：接口按回答数倒序',
      hotNums.every((n, i) => i === 0 || hotNums[i - 1] >= n), `顺序 [${hotNums.join(', ')}]`)

    await page.screenshot({ path: join(SHOT_DIR, 'm10-1-04-plaza.png'), fullPage: true })

    // ---------- 回归：带 courseId 时仍按课程筛（m7-5 的契约，这里顺手验一次）----------
    await page.goto(`${PORTAL}/community?courseId=${courseId}`, {
      waitUntil: 'domcontentloaded',
      timeout: 30000,
    })
    await page.waitForTimeout(1600)
    const scopedResp = await apiGet(page, `/ls/questions/page?pageNo=1&pageSize=10&courseId=${courseId}`)
    const scopedTotal = scopedResp.body?.data?.total ?? 0
    const pageTotalScoped = (await page.textContent('[data-testid="question-total"]'))?.trim()
    check('★★ 带 courseId 打开时仍然「页面总数 == 接口同 courseId 的 total」（老行为没坏）',
      pageTotalScoped === `共 ${scopedTotal} 个问题`,
      `页面「${pageTotalScoped}」/ 接口 ${scopedTotal}`)

    // ================================================================
    section('7. 浏览器运行时健康度')
    // ================================================================
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0,
      pageErrors.slice(0, 2).join(' | '))
  } catch (err) {
    failed++
    failures.push(`脚本异常：${err?.message ?? err}`)
    console.log(`\n❌ 脚本异常：${err?.message ?? err}`)
  } finally {
    await browser.close()
  }

  console.log('\n============================================================')
  console.log(` 结果：${passed} 通过 / ${failed} 失败`)
  if (failures.length) {
    console.log(' 失败项：')
    for (const f of failures) console.log(`   · ${f}`)
  }
  console.log(` 截图：${SHOT_DIR}`)
  console.log(' ⚠️ 说明：写笔记会触发积分事件（M3-3），删笔记**不退积分** —— 这是既有设计')
  console.log('============================================================')
  process.exit(failed ? 1 : 0)
}

main()
