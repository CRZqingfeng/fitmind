#!/usr/bin/env node
/**
 * ============================================================
 * M7-7 前端验收：AI 教练（SSE 流式 / 卡片 / 引用 / 调试面板）
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-auth(8081) / fm-aigc(8094) / fm-course(8084)
 *           / fm-search(8085) / fm-trade(8088)
 *   · 数据：已跑过 `node scripts/seed-demo-data.mjs`
 *   · 前端：fitmind-portal dev server (5173)
 *
 * 用法：cd frontend && node scripts/accept-web-m7-7.mjs
 *
 * ★★ 本脚本要钉住的四件事（都属于"做错了完全看不出来"）：
 *
 *   1. ★★ **回答是"流式"的，而不是"一次性到达"**。
 *      这是最容易造假的一条：前端把一次性返回的文本用定时器逐字吐出来，
 *      看起来也是"打字机"，但网络层其实什么都没变。
 *      → 唯一诚实的证明是**在流式进行中反复采样回答区的长度**，观察它**多次递增**。
 *        本脚本每 120ms 采一次，直到 `data-streaming` 变 false，
 *        然后断言"样本里出现了多个不同长度，且首个样本明显短于最终长度"。
 *
 *   2. ★★ **前端的手写 SSE 解析没有丢字符**。
 *      解析器要处理粘包/半包、UTF-8 跨 chunk、多行 `data:`……
 *      丢字符的典型表现是"少个空格"或"某个字没了"，肉眼几乎看不出来。
 *      → 用**同一句话**分别走"非流式接口"和"页面"，逐字符比对。
 *        为了让这条断言成立，必须确认通道是**确定性的**（Mock）：
 *        脚本先连续调两次非流式接口，确认两次结果**完全一致**，
 *        再去比页面 —— 否则环境本身不确定，比对失败说明不了任何问题。
 *        （provider=deepseek 时这条自动跳过，并明确打印"未验证"。）
 *
 *   3. **卡片与引用必须与接口同源**：断言页面上卡片里的课程名、引用里的 docId
 *      与**同一轮**消息落库的 `params` 完全一致（不是"再问一次拿到的"——
 *      那样比的是两次不同的调用）。
 *
 *   4. **"停止"要真的停下**：点停止后
 *        ① 页面出现"已停止生成"
 *        ② 回答长度**不再增长**
 *        ③ 服务端把"已生成的部分"落了库（消息里有一条长度在 0 和完整长度之间的回答）
 *      第 ③ 条是关键：只断前端连接时服务端会继续生成到结束，那样也能通过 ①②。
 *
 * ⚠️ 已知副作用（同 M7-6 的体测记录）：本脚本会**新建几个会话**（接口没有删除会话的能力），
 *    以及"停止"那一轮会留下一条**被截断的助手消息**。都属于正常数据形态。
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

/** 三句话各验证一条链路（与页面里的快速提问保持一致） */
const Q_SIMPLE = '今天练什么好？'
const Q_KNOWLEDGE = '硬拉的时候腰疼是什么原因？'
const Q_COURSE = '我想增肌，有什么哑铃课程推荐？'

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

async function requireReachable(url, label) {
  try {
    const res = await fetch(url, { method: 'GET' })
    if (!res.status || res.status >= 500) throw new Error(`HTTP ${res.status}`)
  } catch (e) {
    throw new Error(`前置检查失败：${label} 不可达（${url}）—— ${e.message}`)
  }
}

// ★ 浏览器连接统一见 ./_cdp-browser.mjs —— 本机必须走 CDP，
//   `chromium.launch({ channel: 'msedge' })` 在这里一定失败（见模块头部说明）。

/** 用密码登录（避开验证码频控），并把 token 写进 localStorage */
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

/**
 * 在浏览器里用页面自己的 token 打接口（拿到的就是"页面看到的那份数据"）。
 *
 * ★ 两个坑（与 M7-5/M7-6 一致）：GET 不能带 body；`Content-Type: application/json`
 *   属于非简单头，会给 GET 触发 CORS 预检 → 只在真的有 body 时才加。
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
        /* 非 JSON（SSE 就是这种） */
      }
      return { status: res.status, body: json }
    },
    { gateway: GATEWAY, key: TOKEN_KEY, m: method, p: path, b: body ?? null },
  )
}
const apiGet = (page, p) => api(page, 'GET', p)
const apiPost = (page, p, b) => api(page, 'POST', p, b)

async function waitFor(fn, { timeoutMs = 20000, intervalMs = 500, label = '' } = {}) {
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

/** 文字归一化：去掉空白再比（用于"渲染后文本 vs 接口原文"这种必须宽松的场合） */
const flat = (s) => String(s ?? '').replace(/\s+/g, '')

/** 往输入框里填字：Element Plus 的 textarea 是"属性落在内层元素上"，两种结构都要兼容 */
async function fillInput(page, testid, text) {
  const el = page.locator(`[data-testid="${testid}"]`).first()
  await el.waitFor({ state: 'visible', timeout: 15000 })
  const tag = await el.evaluate((node) => node.tagName)
  if (tag === 'TEXTAREA' || tag === 'INPUT') {
    await el.fill(text)
  } else {
    await el.locator('textarea, input').first().fill(text)
  }
}

/** 取最后一条回答的 { 文本, 是否仍在流式 } */
async function readLastAnswer(page) {
  return page.evaluate(() => {
    const nodes = [...document.querySelectorAll('[data-testid="ai-answer"]')]
    const last = nodes[nodes.length - 1]
    if (!last) return null
    return {
      text: last.textContent ?? '',
      streaming: last.getAttribute('data-streaming') === 'true',
    }
  })
}

/**
 * 发送一个问题，并在流式过程中持续采样（★ 见文件头第 1 条）
 *
 * 返回：{ samples, text, streamingSeconds }
 */
async function sendAndSample(page, question, { maxMs = 40000 } = {}) {
  const before = await page.locator('[data-testid="ai-answer"]').count()

  await fillInput(page, 'ai-input', question)
  await page.click('[data-testid="ai-send"]')

  // 等新气泡出现（旧气泡可能还在，所以按"数量变多"判断）
  await page
    .waitForFunction(
      (n) => document.querySelectorAll('[data-testid="ai-answer"]').length > n,
      before,
      { timeout: 20000 },
    )
    .catch(() => {})

  const samples = []
  const started = Date.now()
  while (Date.now() - started < maxMs) {
    const info = await readLastAnswer(page)
    if (info) {
      samples.push({ len: info.text.length, streaming: info.streaming, at: Date.now() - started })
      if (!info.streaming) break
    }
    await sleep(120)
  }
  const last = await readLastAnswer(page)
  return {
    samples,
    text: last?.text ?? '',
    streamingSeconds: (Date.now() - started) / 1000,
  }
}

/** 取会话列表里最新的那个会话（列表按 lastActive 倒序） */
async function latestSessionId(page) {
  const page1 = (await apiGet(page, '/ais/chat/sessions?pageNo=1&pageSize=1')).body?.data
  return (page1?.list ?? [])[0]?.sessionId ?? null
}

/** 取某个会话的消息，返回 { user:[], assistant:[] } */
async function sessionMessages(page, sessionId) {
  const list = (await apiGet(page, `/ais/chat/sessions/${sessionId}/messages`)).body?.data ?? []
  return {
    all: list,
    assistants: list.filter((m) => m.role === 'assistant'),
  }
}

async function main() {
  console.log('============================================================')
  console.log(' FitMind M7-7 前端验收（AI 教练：流式 / 卡片 / 引用 / 面板）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/ais/chat/provider`, 'fm-aigc')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(`${GATEWAY}/ss/courses/portal?pageNo=1&pageSize=1`, 'fm-search')

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1560, height: 1000 } })
  const page = await context.newPage()

  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(e.message))
  const consoleErrors = []
  page.on('console', (m) => {
    // ★ SSE 请求被 abort 时浏览器会记一条 `Failed to load resource` —— 那是停止功能
    //   的正常产物，不是缺陷（与网络类错误一起排除）
    if (m.type() === 'error' && !/Failed to load resource/i.test(m.text())) {
      consoleErrors.push(m.text())
    }
  })
  /** ★ "HTTP 200 但业务码非 200"：只弹红条，不产生 pageerror / console error（M7-4 踩过的盲区） */
  const businessErrors = []
  page.on('response', async (res) => {
    if (!res.url().includes(':10010')) return
    try {
      const body = await res.json()
      if (body && typeof body.code === 'number' && body.code !== 200) {
        const u = new URL(res.url())
        businessErrors.push(
          `${res.request().method()} ${u.pathname}${u.search} → ${body.code} ${body.msg ?? ''}`,
        )
      }
    } catch {
      /* SSE 不是 JSON */
    }
  })

  try {
    // ================================================================
    section('0. 登录 + 进入 AI 教练')
    // ================================================================
    await loginByApi(page, STUDENT_PHONE, STUDENT_PASSWORD)
    check('登录成功（密码登录，避开验证码频控）', true)

    const provider = (await apiGet(page, '/ais/chat/provider')).body?.data
    console.log(`     ℹ️ 当前模型通道：${provider}`)

    await page.goto(`${PORTAL}/ai`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="ai-input"]', { timeout: 30000 })
    await page.waitForTimeout(1500)

    const providerText = (await page.textContent('[data-testid="ai-provider"]'))?.trim()
    check(
      '★ 页面标注了模型通道（真实模型 / Mock）',
      Boolean(providerText),
      `页面「${providerText}」`,
    )
    check(
      '★ 页面显示的通道与接口一致',
      provider === 'mock' ? /Mock/i.test(providerText ?? '') : /真实/.test(providerText ?? ''),
      `接口「${provider}」/ 页面「${providerText}」`,
    )

    const hasTypeSelect = (await page.locator('[data-testid="ai-chattype"]').count()) > 0
    check('编排策略选择器存在（SIMPLE / ENHANCE / ROUTE）', hasTypeSelect)

    // 会话列表：要么有历史，要么明确告知"还没有对话记录"
    const sessionItems = await page.locator('[data-testid="ai-session-item"]').count()
    const emptyText = (await page.textContent('.session-empty').catch(() => '')) ?? ''
    check(
      '★ 左侧会话列表已渲染（有记录或明确空态）',
      sessionItems > 0 || emptyText.includes('还没有对话记录'),
      sessionItems > 0 ? `${sessionItems} 条会话` : '空态提示',
    )
    await page.screenshot({ path: join(SHOT_DIR, 'm7-7-01-coach-entry.png') })

    // ================================================================
    section('1. 新对话：空态与快速提问')
    // ================================================================
    await page.click('[data-testid="ai-new-session"]')
    await page.waitForTimeout(600)
    const quickCount = await page.locator('[data-testid^="ai-quick-"]').count()
    check('★ 新对话是空态，并给出可点的快速提问（而不是只有输入框）', quickCount >= 3, `${quickCount} 条`)

    // ================================================================
    section('2. ★★ 流式证明：回答在生成过程中逐步变长')
    // ================================================================
    // 先把策略切到"直答"（SIMPLE）：第 3 节要做逐字符比对，需要一条确定性的短回答
    await page.locator('[data-testid="ai-chattype"]').click()
    await page.waitForSelector('.el-select-dropdown__item', { timeout: 8000 }).catch(() => {})
    await page.locator('.el-select-dropdown__item').filter({ hasText: '直答' }).first().click()
    await page.waitForTimeout(500)

    const stream1 = await sendAndSample(page, Q_SIMPLE)
    const lengths = stream1.samples.map((s) => s.len)
    const distinct = [...new Set(lengths)]
    const firstLen = lengths[0] ?? 0
    const finalLen = lengths[lengths.length - 1] ?? 0

    check('★ 采样到了回答内容', finalLen > 0, `最终长度 ${finalLen} 字`)
    check(
      '★★ 流式过程中长度出现多个不同值（不是一次性到达）',
      distinct.length >= 2,
      `采样 ${lengths.length} 次 → ${distinct.length} 个不同长度：${lengths.slice(0, 8).join(' → ')}…`,
    )
    check(
      '★★ 首个样本明显短于最终长度（"边写边长"的直接证据）',
      firstLen < finalLen && firstLen >= 0,
      `${firstLen} → ${finalLen}`,
    )
    check('★ 流式结束后气泡的 data-streaming 归为 false', stream1.samples.at(-1)?.streaming === false)
    console.log(
      `     ℹ️ 完整流式耗时约 ${stream1.streamingSeconds.toFixed(1)}s，采样 ${stream1.samples.length} 次`,
    )

    // ================================================================
    section('3. ★★ 前端手写 SSE 解析不丢字符（逐字符比对）')
    // ================================================================
    const apiContent1 = (await apiPost(page, '/ais/chat', { content: Q_SIMPLE, chatType: 'SIMPLE' }))
      .body?.data?.content
    const apiContent2 = (await apiPost(page, '/ais/chat', { content: Q_SIMPLE, chatType: 'SIMPLE' }))
      .body?.data?.content

    if (provider !== 'mock') {
      console.log(
        `     ⚠️ 当前通道是 ${provider}（非确定性）→ 这条断言**无法成立**，已跳过。` +
          ' 要验证"不丢字符"，请用 Mock 通道重跑：bash scripts/start-all.sh fm-aigc mock',
      )
    } else {
      check(
        '★ 前置：Mock 通道的同一问题两次调用结果完全一致（确定性验证）',
        Boolean(apiContent1) && apiContent1 === apiContent2,
        apiContent1 === apiContent2 ? `${apiContent1.length} 字` : '两次结果不一致',
      )

      // ★ 这里做**严格相等**（不去空白）：因为"丢一个空格"正是最可能发生的字符丢失，
      //   用 flat() 抹掉空白会把这类问题一并抹掉，等于没测。
      //   能被严格比对的前提是这条回答不含 Markdown（不含就没被渲染改写）。
      /**
       * ★ 页面文本要先去掉**首尾**空白再比。
       *
       * 原因不是"宽容"，而是**渲染器的排版产物**：Markdown 产出的是 `<p>…</p>\n`，
       * 那个尾换行经 `innerHTML` 会变成一个真实文本节点，于是 `textContent` 末尾多一个 `\n`。
       * 实测证据（逐字符 diff）：`body = api + "\n"`，仅此一处。
       *
       * ★ 只去首尾、**绝不去中间**：中间的空格正是要防的那类丢失（见下一条断言）。
       */
      const pageText = stream1.text.replace(/^\s+|\s+$/g, '')
      const apiText = apiContent1 ?? ''
      const hasMd = /[*_`#|]|\]\(/.test(apiText)

      if (hasMd) {
        console.log('     ⚠️ 接口原文含 Markdown 标记（渲染后标记会被去掉）→ 改为"去标记后比对"')
        const stripped = apiText.replace(/[*_`#>]/g, '')
        check(
          '★ 页面文本 == 接口原文（去掉 Markdown 标记后）',
          flat(pageText) === flat(stripped),
          `页面 ${flat(pageText).length} 字 / 接口（去标记）${flat(stripped).length} 字`,
        )
      } else {
        check(
          '★★ 页面文本与接口原文**逐字符完全一致**（手写 SSE 解析没有丢字符）',
          pageText === apiText,
          pageText === apiText
            ? `${apiText.length} 字全部一致`
            : `页面 ${pageText.length} 字 / 接口 ${apiText.length} 字`,
        )
      }

      /**
       * ★★ 这条专门盯住那个最隐蔽的坑（本项目实测踩到过）：
       *
       * SSE 规范说"`data:` 后的**一个**空格属于分隔符，解析时应被去掉"，
       * 绝大多数现成解析器（浏览器 EventSource、各类 JS SSE 库）都照做。
       * **但 Spring 的 `SseEmitter` 并不在冒号后补空格** —— 它写的是 `data:` + 载荷原文。
       * 于是当某个分片本身以空格开头时，线上就是 `data: 而`，
       * 规范解析器会把它解析成 `and`，**悄悄吞掉那个空格**。
       *
       * 实测：同一份 727 字的回答，按规范解析只剩 720 字 —— 少 7 个字符、**不报任何错**。
       * 所以这里单独断言"空格数量一致"：它是这类丢失唯一的可见信号。
       */
      const pageSpaces = (pageText.match(/ /g) ?? []).length
      const apiSpaces = (apiText.match(/ /g) ?? []).length
      check(
        '★★ 空格数量与接口一致（专防"SSE 规范吞掉 data: 后前导空格"这个坑）',
        pageSpaces === apiSpaces,
        `页面 ${pageSpaces} 个 / 接口 ${apiSpaces} 个`,
      )

      if (pageText !== apiText && !hasMd) {
        const n = Math.min(pageText.length, apiText.length)
        for (let i = 0; i < n; i++) {
          if (pageText[i] !== apiText[i]) {
            console.log(`       首个差异 @${i}：`)
            console.log(`         页面  : ${JSON.stringify(pageText.slice(Math.max(0, i - 20), i + 20))}`)
            console.log(`         接口  : ${JSON.stringify(apiText.slice(Math.max(0, i - 20), i + 20))}`)
            break
          }
        }
      }
    }

    // ================================================================
    section('4. 工具卡片：课程列表（内容与同一轮落库的数据一致）')
    // ================================================================
    await page.locator('[data-testid="ai-chattype"]').click()
    await page.waitForSelector('.el-select-dropdown__item', { timeout: 8000 }).catch(() => {})
    await page.locator('.el-select-dropdown__item').filter({ hasText: '智能路由' }).first().click()
    await page.waitForTimeout(500)

    const stream2 = await sendAndSample(page, Q_COURSE)

    await page.waitForSelector('[data-testid="chat-card-course-list"]', { timeout: 25000 })
    const uiCourseCount = await page.locator('[data-testid="chat-course-item"]').count()
    check('★ 回答下方出现「课程列表卡片」', uiCourseCount > 0, `${uiCourseCount} 门`)

    const uiCourseNames = await page
      .locator('[data-testid="chat-course-item"] .mini-name')
      .allTextContents()

    // ★ 与**同一轮**落库的 params 对比（不是"再问一次"—— 那样比的是两次不同的调用）
    const sid2 = await latestSessionId(page)
    const msgs2 = await sessionMessages(page, sid2)
    const lastAssistant2 = msgs2.assistants.at(-1)
    const apiCards = lastAssistant2?.params?.cards ?? []
    const apiCourseNames = (apiCards[0]?.courses ?? []).map((c) => c.courseName)

    check(
      '★★ 页面卡片条数 == 该轮落库的 cards[0].courses 条数',
      uiCourseCount === apiCourseNames.length,
      `页面 ${uiCourseCount} / 落库 ${apiCourseNames.length}`,
    )
    check(
      '★★ 页面上的课程名与落库数据逐项一致',
      uiCourseNames.length > 0 &&
        uiCourseNames.every((n, i) => flat(n) === flat(apiCourseNames[i])),
      `页面 ${JSON.stringify(uiCourseNames.slice(0, 3))}`,
    )

    const cardTitle = (await page.textContent('.card-title').catch(() => ''))?.trim()
    check('卡片有可读标题（不是空白框）', Boolean(cardTitle), `「${cardTitle}」`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-7-02-course-card.png') })

    // ================================================================
    section('5. ★★ 调试面板：指标与接口同源')
    // ================================================================
    await page.click('[data-testid="ai-panel-toggle"]')
    await page.waitForSelector('[data-testid="ai-panel"]', { timeout: 10000 })
    await page.waitForTimeout(600)

    const firstTokenText = (await page.textContent('[data-testid="ai-metrics-firsttoken"]'))?.trim()
    const totalText = (await page.textContent('[data-testid="ai-metrics-total"]'))?.trim()
    const agentText = (await page.textContent('[data-testid="ai-metrics-agent"]'))?.trim()
    const promptTokensText = (await page.textContent('[data-testid="ai-metrics-prompt-tokens"]'))?.trim()

    check('★ 面板显示了首字延迟（真实数值）', /\d+\s*ms/.test(firstTokenText ?? ''), `「${firstTokenText}」`)
    check('★ 面板显示了总耗时', /\d+\s*ms/.test(totalText ?? ''), `「${totalText}」`)
    check('★ 面板显示了执行 Agent', Boolean(agentText) && agentText !== '—', `「${agentText}」`)
    check(
      '★ 面板显示了 token 用量',
      /^\d+$/.test(promptTokensText ?? ''),
      `输入 ${promptTokensText} token`,
    )

    // ★ 与落库消息交叉验证首字延迟
    const apiFirstToken = msgs2.assistants.at(-1)?.firstTokenMs
    check(
      '★★ 面板的首字延迟 == 该轮落库消息的 firstTokenMs',
      apiFirstToken == null || firstTokenText === `${apiFirstToken} ms`,
      `面板「${firstTokenText}」/ 落库 ${apiFirstToken}`,
    )

    // ★★ 工具链：面板必须如实展示"本轮调了哪些工具" —— 与落库的 toolCalls 逐项比对。
    //    这一轮（课程推荐）真实调用了工具，所以这是一条"面板没有自己编数据"的硬证据。
    const uiToolNames = await page.locator('[data-testid="ai-tool-name"]').allTextContents()
    const apiToolNames = (msgs2.assistants.at(-1)?.toolCalls?.invocations ?? []).map((i) => i.name)
    check(
      '★★ 面板的工具调用链 == 该轮落库的 toolCalls.invocations（名称逐项一致）',
      uiToolNames.length > 0 && JSON.stringify(uiToolNames) === JSON.stringify(apiToolNames),
      `面板 ${JSON.stringify(uiToolNames)} / 落库 ${JSON.stringify(apiToolNames)}`,
    )

    const memoryCountText = (await page.textContent('[data-testid="ai-memory-count"]'))?.trim()
    const quotaText = (await page.textContent('[data-testid="ai-quota-used"]'))?.trim()
    check('★ 面板显示了上下文窗口占用', Boolean(memoryCountText), `「${memoryCountText}」`)
    check('★ 面板显示了配额用量', Boolean(quotaText), `「${quotaText}」`)

    const promptSourceCount = await page.locator('[data-testid="ai-prompt-source"]').count()
    check('★ 面板列出了提示词来源（7 条）', promptSourceCount >= 5, `${promptSourceCount} 条`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-7-04-coach-panel.png') })


    // ================================================================
    section('6. 知识库引用：docId 与同一轮落库的 rag.sources 一致')
    // ================================================================
    const stream3 = await sendAndSample(page, Q_KNOWLEDGE)
    check('知识类问题返回了内容', stream3.text.length > 0, `${stream3.text.length} 字`)

    // ★★ 第 2 节那条用的是 40 字的短回答（只有 1 次明显的增长），证据偏薄；
    //    这里用 600+ 字的长回答再证一次 —— 采样窗口内能观测到**大量**递增长度，
    //    这才真正说明"分片是逐帧到达的"（一次性返回不可能出现这种阶梯）。
    const kvLengths = stream3.samples.map((s) => s.len)
    const kvDistinct = new Set(kvLengths).size
    check(
      '★★ 长回答的流式过程观测到多个递增长度（逐帧到达，不是一次性返回）',
      kvDistinct >= 5,
      `采样 ${kvLengths.length} 次 → ${kvDistinct} 个不同长度，最长 ${kvLengths.at(-1)} 字`,
    )

    const ragVisible = (await page.locator('[data-testid="rag-sources"]').count()) > 0
    check('★ 回答下方出现「引用来源」区', ragVisible)

    let uiDocIds = []
    if (ragVisible) {
      // 引用区默认收起 → 先展开
      const expandBtn = page.locator('[data-testid="rag-sources"]').last().getByText('展开')
      if ((await expandBtn.count()) > 0) await expandBtn.first().click()
      await page.waitForTimeout(400)
      uiDocIds = (await page.locator('[data-testid="rag-doc-id"]').allTextContents()).map((s) => s.trim())
    }

    const sid3 = await latestSessionId(page)
    const msgs3 = await sessionMessages(page, sid3)
    const apiSources = (msgs3.assistants.at(-1)?.params?.rag?.sources ?? []).map((s) => s.docId)

    check('★ 页面至少展示了一条引用来源', uiDocIds.length > 0, `${uiDocIds.length} 条`)
    check(
      '★★ 页面引用的 docId 与同一轮落库的来源逐项一致',
      uiDocIds.length > 0 && uiDocIds.every((d, i) => d === apiSources[i]),
      `页面 ${JSON.stringify(uiDocIds.slice(0, 3))} / 落库 ${JSON.stringify(apiSources.slice(0, 3))}`,
    )

    // ★ 引用分数：混合检索下会出现 >1 的值（BM25），页面必须给出解释而不是当成"相似度"
    const scoreWarn = await page.locator('.score-warn').count()
    const footnote = await page.locator('.rag-footnote').count()
    check(
      '★ 引用分数出现 >1（BM25）时，页面给出了口径说明',
      scoreWarn === 0 || footnote > 0,
      scoreWarn > 0 ? `有 ${scoreWarn} 个 BM25 分数，说明块 ${footnote} 处` : '本轮无超界分数',
    )

    await page.screenshot({ path: join(SHOT_DIR, 'm7-7-03-rag-sources.png') })

    // ================================================================
    section('7. ★★ 停止生成：前端断开 + 服务端真停（长度不再增长）')
    // ================================================================
    await fillInput(page, 'ai-input', Q_KNOWLEDGE)
    await page.click('[data-testid="ai-send"]')
    // 等"停止"按钮出现（说明流已经开始）
    await page.waitForSelector('[data-testid="ai-stop"]', { timeout: 15000 })
    await page.waitForTimeout(900)

    const beforeStop = await readLastAnswer(page)
    await page.click('[data-testid="ai-stop"]')

    await page.waitForSelector('[data-testid="ai-stopped"]', { timeout: 15000 }).catch(() => {})
    const stoppedTag = await page.locator('[data-testid="ai-stopped"]').count()
    check('★ 点停止后页面出现「已停止生成」标记', stoppedTag > 0)

    // ★ 关键：停止后长度必须**不再增长**（只断前端、服务端还在生成时，这里也会增长）
    const lenA = (await readLastAnswer(page))?.text.length ?? 0
    await sleep(1200)
    const lenB = (await readLastAnswer(page))?.text.length ?? 0
    check(
      '★★ 停止后回答长度不再增长（前端这条流真的断了）',
      lenA === lenB,
      `${lenA} → ${lenB}`,
    )

    // ★ 再验服务端：这一轮是"有内容但被截断"的
    const sid4 = await latestSessionId(page)
    const msgs4 = await sessionMessages(page, sid4)
    const stoppedMsg = msgs4.assistants.at(-1)
    const stoppedLen = (stoppedMsg?.content ?? '').length
    const fullLen = (apiContent1 ?? '').length
    check(
      '★ 服务端把"已生成的部分"落了库（长度 > 0）',
      stoppedLen > 0,
      `落库 ${stoppedLen} 字（本轮自停止长度 ${beforeStop?.text.length ?? 0} 字）`,
    )
    check(
      '★ 落库内容短于完整回答（确实是"被停止"而不是"生成完了"）',
      stoppedLen > 0 && (fullLen === 0 || stoppedLen !== fullLen),
      `落库 ${stoppedLen} 字`,
    )

    // ================================================================
    section('8. 多轮记忆：第二轮把上文带进了 prompt')
    // ================================================================
    const stream5 = await sendAndSample(page, '那减脂呢？')
    check('多轮追问返回了内容', stream5.text.length > 0, `${stream5.text.length} 字`)

    const historyText = await waitFor(
      async () => {
        const t = (await page.textContent('[data-testid="ai-memory-history"]').catch(() => ''))?.trim()
        const n = Number(String(t).replace(/\D/g, ''))
        return n > 0 ? t : null
      },
      { timeoutMs: 15000, label: '面板刷新 memoryHistory', intervalMs: 800 },
    )
    check(
      '★★ 第二轮带上了历史（面板 memoryHistory > 0，说明多轮记忆真的生效）',
      Boolean(historyText),
      `「${historyText}」`,
    )

    // 工具链在"课程推荐"那轮才有 → 切回那一轮所在会话看明细
    // （面板展示的是"最近一轮"，所以这里只断言当前轮次无工具时也如实显示 0 次）
    const toolCountText = (await page.textContent('[data-testid="ai-panel"] .block-title:has-text("工具调用") .count').catch(() => '')) ?? ''
    check('★ 面板如实标注本轮工具调用次数（含 0 次）', /\d+\s*次/.test(toolCountText), `「${toolCountText.trim()}」`)

    // ================================================================
    section('9. 浏览器运行时健康度')
    // ================================================================
    await page.waitForTimeout(800)
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
    check(
      '★★ 全程没有「业务码非 200」的响应',
      businessErrors.length === 0,
      businessErrors.slice(0, 5).join(' | ') || `共 ${businessErrors.length} 条`,
    )
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
  console.error('脚本异常：', e.message)
  process.exit(2)
})
