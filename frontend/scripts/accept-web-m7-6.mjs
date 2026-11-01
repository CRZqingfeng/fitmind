#!/usr/bin/env node
/**
 * ============================================================
 * M7-6 前端验收：体测评估（录入 / 报告 / 趋势 / 历史）+ 动作小测
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-auth(8081) / fm-user(8082) /
 *           fm-exam(8092) / fm-course(8084) / fm-learning(8086) /
 *           fm-trade(8088)（布局的购物车角标会调它）
 *   · 数据：已跑过 `node scripts/seed-demo-data.mjs`（会造出「动作小测演示课」
 *           并在**上架前**给它配好 3 道题，同时把学员报进去）
 *   · 前端：fitmind-portal dev server (5173)
 *
 * 用法：cd frontend && node scripts/accept-web-m7-6.mjs
 *
 * ★★ 本脚本要钉住的四件事（都属于"做错了看不出来"）：
 *
 *   1. **报告里每个数字都必须来自后端**。
 *      BMI / 分级 / BMR / TDEE / 去脂体重 / 综合评分 / 结论 / 训练建议，
 *      全部由 fm-exam 算好返回。前端若自己重算一份，迟早出现"同一份数据两个数"。
 *      → 所以断言方式是"页面上的文字 == 接口返回的原文"（逐项比，不做任何换算）。
 *
 *   2. **两个空态是"数据"而不是"错误"**：
 *      · 一次都没测过 → `/es/assessments/latest` 返回 `data: null`
 *      · 只有一次     → `trend` 有对象但各种 `*Change` 是 `null`
 *      → "能不能看趋势"要看 `times >= 2`，而不是"trend 是不是 null"。
 *
 *   3. **小测作答前后两个状态**：作答前接口**故意不下发** `answer` / `analysis`
 *      （`SubjectSimpleVO` 里根本没这两个字段），所以页面在提交前**不应该**出现
 *      "正确答案/解析" —— 这条要正着断言一次（提交后必须有）。
 *
 *   4. **多选题的答案要排序归一化**：后端按字符串比对，`"3,1"` 与 `"1,3"` 不是一回事。
 *
 * ⚠️ 注意：本脚本**每跑一次会多一条体测记录**（后端没有"删除体测记录"的接口）。
 *    体测记录属于正常用户数据（不是"验收提问 xxx"那种垃圾），所以不做清理；
 *    但跑很多次后评估页会积累多条历史 —— 这是预期行为。
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function requireReachable(url, label) {
  try {
    const res = await fetch(url, { method: 'GET' })
    // 401/403 也算"可达"（服务活着，只是没带 token）
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

/** 在浏览器里用页面自己的 token 打接口（这样拿到的就是"页面看到的那份数据"） */
async function api(page, method, path, body) {
  return page.evaluate(
    async ({ gateway, key, m, p, b }) => {
      const token = localStorage.getItem(key)
      // ★ 两个坑叠在这里，注释留清楚：
      //   ① GET / HEAD **不能带 body**（浏览器直接抛 TypeError）
      //   ② `Content-Type: application/json` 属于**非简单头** → 会给 GET 触发一次
      //      CORS **预检（OPTIONS）**；网关的 CORS 配置对 OPTIONS 的处理和实际请求不同，
      //      实测 GET 带上它就会 `Failed to fetch`。
      //   → 所以：**只在真的有 body 时才加 Content-Type**（与 M7-5 的写法保持一致）
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
const apiGet = (page, p) => api(page, 'GET', p)
const apiPost = (page, p, b) => api(page, 'POST', p, b)

async function waitFor(fn, { timeoutMs = 15000, intervalMs = 700, label = '' } = {}) {
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

/** 文字归一化：把空白全部去掉再比 —— 页面上有缩进/换行，接口原文没有 */
const flat = (s) => String(s ?? '').replace(/\s+/g, '')

async function main() {
  console.log('============================================================')
  console.log(' FitMind M7-6 前端验收（体测评估 + 动作小测）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(`${GATEWAY}/es/assessments/latest`, 'fm-exam')
  await requireReachable(`${GATEWAY}/cs/categories/list?level=1`, 'fm-course')

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
  const page = await context.newPage()

  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(e.message))
  const consoleErrors = []
  page.on('console', (m) => {
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
        businessErrors.push(`${res.request().method()} ${u.pathname}${u.search} → ${body.code} ${body.msg ?? ''}`)
      }
    } catch {
      /* 非 JSON */
    }
  })

  try {
    // ================================================================
    section('0. 登录')
    // ================================================================
    await loginByApi(page, STUDENT_PHONE, STUDENT_PASSWORD)
    check('登录成功（密码登录，避开验证码频控）', true)

    // ================================================================
    section('1. 体测页：首测态 or 已有报告')
    // ================================================================
    const before = (await apiGet(page, '/es/assessments/latest')).body?.data

    await page.goto(`${PORTAL}/assessment`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1500)

    if (!before) {
      // 一次都没测过：页面必须给"引导录入"，而不是报错或者空白
      const emptyVisible = await page.locator('[data-testid="assess-empty"]').isVisible()
      check('★ 没有体测记录时，页面显示"还没有体测记录"的引导（而不是报错）', emptyVisible)
    } else {
      const scoreText = (await page.textContent('[data-testid="assess-score"]'))?.trim()
      check('★ 页面上的综合评分 == 接口 latest.score（前端不自己算）',
        scoreText === String(before.score),
        `页面「${scoreText}」/ 接口「${before.score}」`)
    }
    await page.screenshot({ path: join(SHOT_DIR, 'm7-6-01-assessment-before.png'), fullPage: true })

    // ================================================================
    section('2. 录入一次体测')
    // ================================================================
    await page.click('[data-testid="assess-open-form"]')
    await page.waitForSelector('[data-testid="assess-height"]', { timeout: 10000 })
    await page.waitForTimeout(800) // 等弹窗动画（动画期间点击会被吞）

    // 每次换一组数据，保证"这次提交"确实产生了新记录
    const weight = (70 + Math.random() * 1.2).toFixed(1)
    /**
     * ★★ 认"这条记录是不是我刚提交的"要用 **id 变化**，不能用"体重相等"。
     *
     * 第一版就是按体重相等认的，结果**假绿了一次**：那次的提交其实被后端拒了
     * （`500 腰围和臀围要一起填`），但库里恰好存着一条体重相同的旧记录 →
     * 断言"找到了、成功了"，后面的报告断言也就跟着比对了一条"不是我提交的"记录。
     * ★ 这和 M7-3 的"金额五处一致"是同一类教训：**判定"新数据产生了"必须看标识，不能看值**。
     */
    const beforeId = (await apiGet(page, '/es/assessments/latest')).body?.data?.id ?? null

    /**
     * ★ el-input-number 的填值方式要选对。
     *
     * `page.fill()` 只设置 DOM 值并派发 `input` 事件，而 Element Plus 的
     * `el-input-number` 是在 **`change`（失焦）** 时才把值写回 v-model。
     * 所以"fill 完直接点提交"是有风险的：最后一次 fill 的字段还没来得及 commit
     * （实测就出现过"页面看着填好了、后端收到的是空的"）。
     *
     * → 这里用**真人输入**的方式：点进去 → 全选 → 逐字输入 → Tab 失焦。
     *   既保证值被 commit，也更接近用户真实操作。
     */
    const setNum = async (testid, value) => {
      const input = page.locator(`[data-testid="${testid}"] input`)
      await input.click()
      await input.press('Control+a')
      await input.type(String(value))
      await input.press('Tab')
      await page.waitForTimeout(150)
    }

    await setNum('assess-height', 182)
    await setNum('assess-weight', weight)
    await setNum('assess-target-weight', 78)
    await setNum('assess-fat-rate', 16.5)
    // ★ 腰围与臀围**必须成对填**：后端要算腰臀比，只给一个会直接报错
    //   「腰围和臀围要一起填（腰臀比 = 腰围 ÷ 臀围），只填一个算不出来」
    await setNum('assess-waist', 78)
    await setNum('assess-hip', 94)
    await page.click('[data-testid="assess-submit"]')

    const created = await waitFor(
      async () => {
        const r = await apiGet(page, '/es/assessments/latest')
        const d = r.body?.data
        // ★ 判定条件：**id 变了**（新记录）+ 体重对得上（确实是我这条）
        if (!d || String(d.id) === String(beforeId)) return null
        return Number(d.weight) === Number(weight) ? d : null
      },
      { label: '体测落库', timeoutMs: 20000 },
    )
    check('★ 体测提交成功（latest 的 id 变了，且体重是我刚填的）',
      Boolean(created),
      created ? `id ${beforeId} → ${created.id}，体重=${created.weight}` : `期望体重 ${weight}`)

    if (!created) throw new Error('体测没落库，后续断言无法进行')

    // ================================================================
    section('2-b. ★ 前端要镜像后端的跨字段规则（腰围/臀围必须成对）')
    // ================================================================
    /**
     * 后端要算腰臀比（腰围 ÷ 臀围），只给一个会直接抛错：
     *   「腰围和臀围要一起填（腰臀比 = 腰围 ÷ 臀围），只填一个算不出来」
     * 这条规则前端必须自己拦一道 —— 让用户撞服务端错误不是好体验，
     * 而且这类"表单自己就能判断"的错误本来就不该发请求出去。
     * → 断言两件事：**出现友好提示** + **没有产生新记录**。
     */
    const idBeforeBad = (await apiGet(page, '/es/assessments/latest')).body?.data?.id
    await page.click('[data-testid="assess-open-form"]')
    await page.waitForSelector('[data-testid="assess-height"]', { timeout: 10000 })
    await page.waitForTimeout(800)
    await setNum('assess-height', 182)
    await setNum('assess-weight', 71)
    await setNum('assess-waist', 78) // 故意只填腰围、不填臀围
    await page.click('[data-testid="assess-submit"]')
    await page.waitForTimeout(1200)

    const warnTexts = await page.evaluate(() =>
      [...document.querySelectorAll('.el-message')].map((e) => (e.textContent ?? '').trim()),
    )
    check('★ 只填腰围不填臀围时，前端给出明确提示（不发请求、不报服务端错）',
      warnTexts.some((t) => t.includes('臀围')), `页面提示：${warnTexts.join(' | ') || '（无）'}`)

    const idAfterBad = (await apiGet(page, '/es/assessments/latest')).body?.data?.id
    check('★ 被前端拦下的提交没有产生新记录', String(idAfterBad) === String(idBeforeBad),
      `latest id ${idBeforeBad} → ${idAfterBad}`)

    // 关掉弹窗，回到报告页做后续断言
    const cancelBtn = page.locator('.el-dialog__footer button').first()
    if (await cancelBtn.count()) await cancelBtn.click()
    await page.waitForTimeout(700)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1500)

    // ================================================================
    section('3. ★★ 报告：页面上的每个数字都必须等于接口返回的（前端不重算）')
    // ================================================================
    await page.waitForTimeout(1200) // 提交后页面会重新拉一次报告

    const pairs = [
      ['assess-score', 'score'],
      ['assess-bmr', 'bmr'],
      ['assess-tdee', 'tdee'],
    ]
    for (const [testid, field] of pairs) {
      const text = (await page.textContent(`[data-testid="${testid}"]`))?.trim()
      // 页面上带了单位（如 "1733 kcal"）→ 用 contains 而不是全等
      check(`★ 页面 ${field} == 接口 ${field}`,
        text?.includes(String(created[field])), `页面「${text}」/ 接口「${created[field]}」`)
    }

    const bmiLevelText = (await page.textContent('[data-testid="assess-bmi-level"]'))?.trim()
    check('★ BMI 与分级文案 == 接口（含中文分级）',
      flat(bmiLevelText) === flat(`BMI ${created.bmi} · ${created.bmiLevelDesc}`),
      `页面「${bmiLevelText}」/ 接口「BMI ${created.bmi} · ${created.bmiLevelDesc}」`)

    const summaryText = (await page.textContent('[data-testid="assess-summary"]'))?.trim()
    check('★ 一句话结论 == 接口 summary 原文（不做任何改写）',
      flat(summaryText) === flat(created.summary), `页面长度 ${flat(summaryText).length}`)

    const suggText = (await page.textContent('[data-testid="assess-suggestion"]'))?.trim()
    check('★ 训练建议 == 接口 suggestion 原文（后端生成的长文本，前面最容易"再加工"）',
      flat(suggText) === flat(created.suggestion),
      `页面长度 ${flat(suggText).length} / 接口长度 ${flat(created.suggestion).length}`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-6-02-assessment-report.png'), fullPage: true })

    // ================================================================
    section('4. 趋势（★ 数据不足时必须"说明"而不是"空着"）')
    // ================================================================
    const trend = (await apiGet(page, '/es/assessments/trend')).body?.data
    const times = Number(trend?.times ?? 0)
    if (times >= 2) {
      const timesText = (await page.textContent('[data-testid="assess-trend-times"]'))?.trim()
      check('★ 趋势里的"体测次数" == 接口 times',
        flat(timesText) === `${times}次`, `页面「${timesText}」/ 接口 ${times}`)

      const daysText = (await page.textContent('[data-testid="assess-trend-days"]'))?.trim()
      check('★ 趋势里的"间隔天数" == 接口 days',
        flat(daysText) === `${trend?.days}天`, `页面「${daysText}」/ 接口 ${trend?.days}`)

      // 体重变化：接口给的是数字（可为负），页面是带符号的文案
      const w = trend?.weightChange
      const expectW = w === null || w === undefined ? '—' : `${Number(w) > 0 ? '+' : ''}${w} kg`
      const wText = (await page.textContent('[data-testid="assess-trend-weight"]'))?.trim()
      check('★ 趋势里的"体重变化" == 接口 weightChange（含正负号）',
        flat(wText) === flat(expectW), `页面「${wText}」/ 接口「${expectW}」`)
    } else {
      const tip = await page.locator('[data-testid="assess-trend-insufficient"]').isVisible()
      check('★ 只有一次体测时，趋势区显示"至少两次体测"的说明而不是空白', tip, `times=${times}`)
    }

    // ================================================================
    section('5. 历史记录（★ 页面条数 == 接口 total）')
    // ================================================================
    const history = (await apiGet(page, '/es/assessments/page?pageNo=1&pageSize=5')).body?.data
    const historyTotal = Number(history?.total ?? 0)
    const totalText = (await page.textContent('[data-testid="assess-history-total"]'))?.trim()
    check('★ 页面「共 N 次」== 接口 total（同分页口径）',
      flat(totalText) === `共${historyTotal}次`, `页面「${totalText}」/ 接口 ${historyTotal}`)

    const rows = await page.locator('[data-testid="assess-history"] .el-table__body tbody tr').count()
    check('★ 历史表格行数 == 接口第 1 页条数',
      rows === (history?.list ?? []).length, `页面 ${rows} 行 / 接口 ${(history?.list ?? []).length} 条`)

    // ================================================================
    section('6. 动作小测：从学习页进入（★ 目录题数 == 接口题数）')
    // ================================================================
    const lessons = (await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100')).body?.data?.list ?? []
    const quizLesson = lessons.find((l) => String(l.courseName ?? '').startsWith('动作小测演示课'))
    if (!quizLesson) {
      check('已报名「动作小测演示课」（seed 会自动报）', false,
        `我的课程：${lessons.map((l) => l.courseName).join(' / ')}`)
      throw new Error('缺少小测演示课，请先跑 node scripts/seed-demo-data.mjs')
    }
    check('已报名「动作小测演示课」', true, `courseId=${quizLesson.courseId}`)

    const catas = (await apiGet(page, `/cs/courses/${quizLesson.courseId}/catalogs`)).body?.data ?? []
    const quizAction = catas.flatMap((d) => d.sections ?? []).find((s) => (s.subjectNum ?? 0) > 0)
    check('★ 目录里能查到"有题的动作"（subjectNum > 0）',
      Boolean(quizAction), quizAction ? `${quizAction.name}：${quizAction.subjectNum} 题` : '没有')

    const questions = (await apiGet(page, `/cs/practices/${quizAction.id}`)).body?.data ?? []
    check('★ 学员能拉到题目', questions.length === Number(quizAction.subjectNum),
      `接口题目 ${questions.length} / 目录 subjectNum ${quizAction.subjectNum}`)
    check('★★ 拉题时**不返回**答案与解析（作答前不泄露）',
      questions.every((q) => q.answer === undefined && q.analysis === undefined),
      questions.some((q) => q.answer !== undefined) ? '⚠️ 有题目带了 answer' : '都没有 answer/analysis')

    await page.goto(`${PORTAL}/learn/${quizLesson.courseId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="learn-catalog"]', { timeout: 20000 })
    await page.waitForTimeout(1000)

    /**
     * ★★ 必须先**选中那节有题的动作**，再点"去作答"。
     *
     *   页面的作答按钮只在「当前节」有题时才渲染（`v-if="currentSection.subjectNum"`，见 LearnView.vue），
     *   而当前节是**按学习进度定位**的 —— 学员进度一变，默认节就换，按钮直接不存在。
     *   ⚠️ 第一版脚本直接 `click('[data-testid="learn-open-quiz"]')`，
     *   相当于依赖了"进页面时默认那节恰好有题"；实测在课程进度推进后必然超时
     *   （`waiting for locator('[data-testid="learn-open-quiz"]')`）。
     *   ★ 又是"断言依赖了当时恰好成立的前提"那一类。
     */
    await page
      .locator('[data-testid="learn-section"]')
      .filter({ hasText: quizAction.name })
      .first()
      .click()
    await page.waitForSelector('[data-testid="learn-open-quiz"]', { timeout: 15000 })
    await page.click('[data-testid="learn-open-quiz"]')
    await page.waitForSelector('[data-testid="quiz-drawer"]', { timeout: 10000 })
    await page.waitForTimeout(900)

    const shown = await page.locator('[data-testid="quiz-question"]').count()
    check('★ 抽屉里的题目数 == 接口题目数', shown === questions.length,
      `页面 ${shown} 题 / 接口 ${questions.length} 题`)

    // ★ 提交前不该出现"正确答案/解析"
    const rightBefore = await page.locator('[data-testid="quiz-result-right"]').count()
    const analysisBefore = await page.locator('[data-testid="quiz-result-analysis"]').count()
    check('★★ 作答前页面上没有"正确答案 / 解析"（只有提交后才出现）',
      rightBefore === 0 && analysisBefore === 0,
      `正确答案元素 ${rightBefore} 个 / 解析元素 ${analysisBefore} 个`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-6-03-quiz-answering.png'), fullPage: true })

    // ================================================================
    section('7. 小测作答 → ★ 页面判分结果 == 接口判分结果')
    // ================================================================
    /**
     * 作答策略：单选/判断选**第 1 个**，多选选**前两个**。
     * ★ 多选故意选两个：顺带覆盖"逗号分隔 + 排序归一化"这条路径
     *   （用户勾选顺序可能是 3,1，直接 join 会被判错）。
     */
    const picksOf = (q) => {
      const t = Number(q.subjectType)
      const opts = t === 4 ? ['正确', '错误'] : (q.options ?? [])
      return t === 2 || t === 3 ? [1, Math.min(2, opts.length)] : [1]
    }

    for (const q of questions) {
      const box = page.locator('[data-testid="quiz-question"]').nth(questions.indexOf(q))
      for (const p of picksOf(q)) {
        // el-radio / el-checkbox 的可点区域是 label
        await box.locator('.el-radio, .el-checkbox').nth(p - 1).click()
        await page.waitForTimeout(120)
      }
    }

    await page.click('[data-testid="quiz-submit"]')
    await page.waitForSelector('[data-testid="quiz-result-summary"]', { timeout: 15000 })
    await page.waitForTimeout(500)

    /**
     * ★★ 交叉验证的做法：**用同样的作答再提交一次给接口**，拿接口的判分结果
     *    和"页面上显示的结果"逐项比。
     *
     * 为什么可以重复提交：判分是**纯函数**（同样的作答必然同样的结果），
     * 而重复提交的副作用是安全的 ——
     *   · `answerTimes` / `correctTimes` 是**累加**语义（后端注释写明"每次提交都算一次作答"）
     *   · 积分靠 `points_record` 的 (user_id, type, bizId) 唯一索引幂等，不会重复计分
     * 这样就不用把"正确答案是什么"硬编码进脚本（那会让验收和 seed 数据绑死）。
     */
    const sameAnswers = questions.map((q) => ({
      subjectId: q.id,
      // ★ 与页面同一套归一化：多选排序后用逗号连接
      answer: picksOf(q).sort((a, b) => a - b).join(','),
    }))
    const apiResult = (await apiPost(page, '/cs/practices/submit', {
      cataId: quizAction.id,
      answers: sameAnswers,
    })).body?.data

    const summary = (await page.textContent('[data-testid="quiz-result-summary"]'))?.trim()
    const itemCount = await page.locator('[data-testid="quiz-result-item"]').count()
    check('★ 结果区逐题回显（每题一条）', itemCount === questions.length,
      `页面 ${itemCount} 条 / 题目 ${questions.length} 道`)

    check('★★ 页面上"答对 X / Y 题" == 接口判分结果',
      flat(summary) === `答对${apiResult?.correctCount}/${apiResult?.totalCount}题`,
      `页面「${summary}」/ 接口「答对 ${apiResult?.correctCount}/${apiResult?.totalCount} 题」`)

    // 逐题对错也要一致（页面按接口 details 的顺序渲染，所以按下标比）
    const pageVerdicts = []
    for (let i = 0; i < itemCount; i++) {
      const t = (await page.locator('[data-testid="quiz-result-correct"]').nth(i).textContent())?.trim()
      pageVerdicts.push(t === '答对')
    }
    const apiVerdicts = (apiResult?.details ?? []).map((d) => Boolean(d.correct))
    check('★★ 逐题的对/错与接口完全一致',
      JSON.stringify(pageVerdicts) === JSON.stringify(apiVerdicts),
      `页面 ${JSON.stringify(pageVerdicts)} / 接口 ${JSON.stringify(apiVerdicts)}`)

    // 总分（结果区标题里的"得分 X / Y"）
    const scoreTitle = (await page.locator('[data-testid="quiz-drawer"] .el-result__title').first().textContent())?.trim()
    check('★ 得分与满分 == 接口 score / totalScore',
      flat(scoreTitle) === `得分${apiResult?.score}/${apiResult?.totalScore}`,
      `页面「${scoreTitle}」/ 接口「得分 ${apiResult?.score}/${apiResult?.totalScore}」`)

    const rightShown = await page.locator('[data-testid="quiz-result-right"]').count()
    const analysisShown = await page.locator('[data-testid="quiz-result-analysis"]').count()
    check('★★ 提交后每题都给了正确答案（答案只在判分后下发）',
      rightShown === questions.length, `${rightShown} / ${questions.length}`)
    check('★ 提交后给出了答案解析（后端 analysis 字段）', analysisShown > 0, `${analysisShown} 条`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-6-04-quiz-result.png'), fullPage: true })

    // ================================================================
    section('8. 浏览器运行时健康度')
    // ================================================================
    await page.waitForTimeout(600)
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
    check('★★ 全程没有「业务码非 200」的响应',
      businessErrors.length === 0,
      businessErrors.slice(0, 5).join(' | ') || `共 ${businessErrors.length} 条`)
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
