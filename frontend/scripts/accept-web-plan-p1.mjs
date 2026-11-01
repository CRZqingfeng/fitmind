#!/usr/bin/env node
/**
 * ============================================================
 * M11-3 饮食模块 · 前端验收（P1）
 * ------------------------------------------------------------
 * 前置（本机最省事的一套，不需要 Nacos / 网关 / 登录服务）：
 *   · fm-plan(8095) 在跑
 *   · `node scripts/dev-plan-proxy.mjs`（假网关，10010 → 8095，固定 user-info=1）
 *   · `cd frontend/fitmind-portal && npm run dev`（5173）
 *
 * 用法：cd frontend && node scripts/accept-web-plan-p1.mjs
 *
 * ★★ 与 `scripts/accept-plan-p1.mjs` 的分工（**故意不重叠**）：
 *     后端脚本查"库里的数对不对"（用 2016~2020 的独占日期，不碰今天）
 *     本脚本查"页面上真的看得见、点得动、数字回退"（只能作用在今天，
 *     因为界面就走"今天"这条路径 —— 所以断言一律用**增量**，不用绝对值）
 *
 * ★★ 四条写法纪律：
 *   1. **断言可见文本 + 数字关系**，不断言 class 名（改名不报错，文本变了才是功能变了）。
 *   2. **数字必须"同一时刻一起读"**：在 page.evaluate 里一次读完 DOM 再 fetch 接口，
 *      分两次读会被"读一半刚好刷新"骗到（P0 的 C35 就是"两个值取自不同时刻"）。
 *   3. **写操作后的断言用增量**（记完 +X、删完回到原值），
 *      这样脚本连跑十次也不会因为"昨天已经记过"而假红。
 *   4. **token 是假的**：假网关不看 token，只认 user-info。
 *      所以本脚本**不能**验"未登录会被拦" —— 那件事归后端脚本（已断言 401）。
 * ============================================================
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { closeBrowser, launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots', 'plan-p1')

const PORTAL = 'http://localhost:5173'
const PROXY = 'http://localhost:10010'
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

async function requireReachable(url, name) {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(8000) })
    if (!res.ok && res.status >= 500) throw new Error(`HTTP ${res.status}`)
  } catch (e) {
    console.error(`\n❌ 前置条件不满足：${name} 不可达（${url}）—— ${e.message}`)
    console.error('   起服务：node scripts/dev-plan-proxy.mjs  和  cd frontend/fitmind-portal && npm run dev')
    process.exit(2)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitText(page, text, timeout = 12000) {
  try {
    const loc = page.getByText(text, { exact: false }).first()
    await loc.waitFor({ state: 'visible', timeout })
    return loc
  } catch {
    return null
  }
}

async function waitUntil(fn, timeout = 15000, step = 250) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) return true
    await sleep(step)
  }
  return false
}

/**
 * 一次性读完"饮食首页"的所有数字（DOM + 接口在同一时刻）。
 *
 * ★ 为什么不能分两次读：先读 DOM 再 fetch，中间只要有一次重载，
 *   就会拿"旧页面的数字"和"新接口的数字"比，差值莫名其妙，
 *   最后被误判成"前端算错了"。
 */
async function readDiet(page) {
  return page.evaluate(async () => {
    const q = (s) => document.querySelector(s)
    const t = (s) => q(s)?.innerText?.trim() ?? ''
    const numOf = (s) => {
      const m = t(s).replace(/,/g, '').match(/-?\d+(\.\d+)?/)
      return m ? Number(m[0]) : null
    }
    const meals = Array.from(document.querySelectorAll('.meal-card')).map((el) => ({
      label: el.querySelector('.label')?.innerText?.trim() ?? '',
      kcal: Number((el.querySelector('.kcal')?.innerText ?? '').replace(/[^\d-]/g, '') || 0),
      items: Array.from(el.querySelectorAll('.food-row')).map((r) => ({
        name: r.querySelector('.name')?.innerText?.trim() ?? '',
        amount: r.querySelector('.amount')?.innerText?.trim() ?? '',
        kcal: Number((r.querySelector('.kcal')?.innerText ?? '').replace(/[^\d-]/g, '') || 0),
      })),
    }))
    let api = null
    try {
      const res = await fetch('http://localhost:10010/pls/diet/today', {
        headers: { authorization: localStorage.getItem('fitmind:token') || '' },
      })
      api = (await res.json())?.data ?? null
    } catch { /* 接口失败留空，交给断言报 */ }
    return {
      ringLabel: t('.ring-label'),
      ringNum: numOf('.ring-num'),
      ringSub: t('.ring-sub'),
      intake: numOf('.intake b'),
      weightText: t('.weight'),
      macros: Array.from(document.querySelectorAll('.macro-bar')).map((el) => el.innerText.replace(/\s+/g, ' ').trim()),
      quickBtns: Array.from(document.querySelectorAll('.quick-btn')).map((el) => el.innerText.trim()),
      sportNote: t('.sport-note'),
      mealCount: meals.length,
      meals,
      api,
    }
  })
}

async function main() {
  console.log('='.repeat(78))
  console.log('M11-3 / P1 前端验收：饮食（今日汇总 / 饮食库 / 记运动 / 饮食计划）')
  console.log('='.repeat(78))

  await requireReachable(`${PROXY}/pls/diet/today`, '假网关 10010')
  await requireReachable(PORTAL, '前端 dev server 5173')

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
  const page = await context.newPage()
  const pageErrors = []
  const apiBad = []
  page.on('pageerror', (e) => pageErrors.push(e.message))
  page.on('response', (r) => {
    if (r.url().includes('/pls/') && r.status() >= 400) apiBad.push(`${r.status()} ${r.request().method()} ${r.url()}`)
  })

  // 先落到同源页面，才能写 localStorage（跨域写不了）
  await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(([k]) => localStorage.setItem(k, 'accept-web-plan-p1-fake-token'), [TOKEN_KEY])

  // ==================== 1. 饮食 tab 的基本盘 ====================
  section('1. /plan?tab=diet：三个 tab / 圆环三数 / 三大营养素 / 四餐 / 五个入口')

  await page.goto(`${PORTAL}/plan?tab=diet`, { waitUntil: 'domcontentloaded' })
  check('tab「训练」在', !!(await waitText(page, '训练')))
  check('tab「饮食」在', !!(await waitText(page, '饮食')))
  check('tab「我的计划」在', !!(await waitText(page, '我的计划')))
  check('饮食内容真的渲染出来了（圆环出现）', (await waitForRing(page)), '')
  await page.screenshot({ path: join(SHOT_DIR, '01-diet-home.png') })

  const s1 = await readDiet(page)
  check('圆环标签是「还可以吃」（不是"已吃"）',
    s1.ringLabel === '还可以吃' || s1.ringLabel === '已超出', s1.ringLabel)
  check('★ 圆环 = 预算 − 摄入（三个数字自洽，误差 0）',
    s1.ringNum !== null && s1.intake !== null
    && Math.abs(s1.ringNum - (numFromText(s1.ringSub) - s1.intake)) < 1,
    `${s1.ringNum} vs ${numFromText(s1.ringSub)} − ${s1.intake}`)
  check('★ 页面预算 == 接口 budgetKcal（不是前端硬写的数）',
    numFromText(s1.ringSub) === Number(s1.api?.budgetKcal),
    `页面 ${numFromText(s1.ringSub)} / 接口 ${s1.api?.budgetKcal}`)
  check('★ 页面摄入 == 接口 intakeKcal',
    s1.intake === Number(s1.api?.intakeKcal), `页面 ${s1.intake} / 接口 ${s1.api?.intakeKcal}`)
  check('★ 页面「还可以吃」== 接口 remainKcal',
    s1.ringNum === Number(s1.api?.remainKcal), `页面 ${s1.ringNum} / 接口 ${s1.api?.remainKcal}`)

  check('三大营养素三列都在（碳水/蛋白/脂肪）',
    s1.macros.length === 3
    && s1.macros.some((m) => m.includes('碳水'))
    && s1.macros.some((m) => m.includes('蛋白'))
    && s1.macros.some((m) => m.includes('脂肪')),
    s1.macros.join(' | ').slice(0, 120))
  check('营养素目标来自后端（不是 0/0）',
    s1.macros.every((m) => !/\/\s*0(\D|$)/.test(m)), s1.macros.join(' | ').slice(0, 120))

  check('四张餐次卡（早/午/晚/加餐）', s1.mealCount === 4,
    s1.meals.map((m) => m.label).join('/'))
  check('五个快捷入口（4 个记餐 + 记运动）',
    s1.quickBtns.length === 5
    && ['记早餐', '记午餐', '记晚餐', '记加餐', '记运动'].every((x) => s1.quickBtns.includes(x)),
    s1.quickBtns.join('/'))

  const weightJin = Number((s1.weightText.match(/(\d+(\.\d+)?)/) || [])[1])
  check('★ 体重按「斤」显示，且 = 接口 kg × 2（换算只有一处）',
    Math.abs(weightJin - Number(s1.api?.weightKg) * 2) <= 0.2,
    `页面 ${weightJin} 斤 / 接口 ${s1.api?.weightKg}kg`)
  check('运动那一行有文案（记完运动不会"什么数字都不动"）',
    !!s1.sportNote, s1.sportNote)

  // ==================== 2. tab 与 URL 双向同步 ====================
  section('2. tab 与 URL 同步（回首页靠它刷新，不依赖 keep-alive）')

  await page.locator('.el-tabs__header .el-tabs__item').filter({ hasText: '训练' }).first().click()
  await waitUntil(async () => !page.url().includes('tab=diet'), 6000)
  check('点「训练」→ URL 不再带 tab=diet', !page.url().includes('tab=diet'), page.url())

  await page.locator('.el-tabs__header .el-tabs__item').filter({ hasText: '饮食' }).first().click()
  await waitUntil(async () => page.url().includes('tab=diet'), 6000)
  check('★ 点「饮食」→ URL 变成 ?tab=diet（刷新/返回都能复原）', page.url().includes('tab=diet'), page.url())

  // ==================== 3. 记早餐全链路（含"同一食物再记一次 = 合并"） ====================
  section('3. 记早餐：进饮食库 → 选份量 → 保存 → 三个数字同时变；再记一次 → 合并')

  const before = await readDiet(page)
  const beforeIntake = before.intake
  const breakfastBefore = before.meals.find((m) => m.label === '早餐')
  const beforeMealItems = (breakfastBefore?.items || []).length
  const existingNames = new Set((breakfastBefore?.items || []).map((i) => i.name))
  console.log(`  记之前：摄入 ${beforeIntake} 千卡 · 早餐 ${beforeMealItems} 条 · 还可以吃 ${before.ringNum} · 已有 ${[...existingNames].join('/')}`)

  await page.locator('.quick-btn').filter({ hasText: '记早餐' }).first().click()
  await page.waitForURL(/\/plan\/food-lib/, { timeout: 12000 })
  check('★ 点「记早餐」→ /plan/food-lib?mealType=1（四个入口共用一个页面）',
    /mealType=1/.test(page.url()), page.url())

  check('页面标题是「记早餐」（按餐次变）', !!(await waitText(page, '记早餐')))
  await waitUntil(async () => (await page.locator('.food-card').count()) > 0, 15000)
  const foodCards = await page.locator('.food-card').count()
  check('饮食库渲染出食物卡（≥1）', foodCards > 0, `${foodCards} 张`)
  await page.screenshot({ path: join(SHOT_DIR, '02-food-lib.png') })

  const firstCardText = (await page.locator('.food-card').first().innerText()).replace(/\s+/g, ' ')
  check('食物卡带「N 千卡/100g」', /千卡\/100g/.test(firstCardText), firstCardText.slice(0, 60))

  // ★ 必须挑一个"早餐里还没有的"食物。
  //   如果选已有的那个，后端会**合并**到同一行（这是正确行为），
  //   于是"早餐卡多一条行"这个断言永远不可能成立 —— 那不是 bug，是断言写错了。
  const cardNames = (await page.locator('.food-card .name').allInnerTexts()).map((n) => n.trim())
  const pickIndex = cardNames.findIndex((n) => n && !existingNames.has(n))
  check('★ 挑到一个早餐里还没有的食物（否则会和已有行合并，验不了"多一条"）',
    pickIndex >= 0, `候选：${cardNames.slice(0, 4).join('/')}`)
  if (pickIndex < 0) throw new Error('饮食库里所有食物都已在早餐中出现，无法验证新增行')
  const chosenName = cardNames[pickIndex]

  await page.locator('.food-card').nth(pickIndex).locator('.add').click()
  check('点 ＋ 弹出份量弹层（不是直接按 100g 记）', (await page.locator('.el-dialog').count()) > 0)

  const dialogTitle = await page.locator('.el-dialog__title').first().innerText()
  check('弹层标题就是选中的那个食物', dialogTitle.trim() === chosenName, `${dialogTitle} / ${chosenName}`)

  const tipBefore = (await page.locator('.portion-tip').first().innerText()).replace(/\s+/g, ' ')
  check('弹层显示"约 N 克 · 约 N 千卡"预览', /约\s*\d+.*克/.test(tipBefore) && /千卡/.test(tipBefore), tipBefore)

  // 改份量 → 预览必须跟着变（证明份量真的参与折算，不是摆设）
  const amountInput = page.locator('.el-dialog .el-input-number input').first()
  await amountInput.fill('50')
  await amountInput.press('Enter')
  await sleep(400)
  const tipAfter = (await page.locator('.portion-tip').first().innerText()).replace(/\s+/g, ' ')
  check('★ 改成 50 后预览的克数也变成 50（份量参与计算）', /约\s*50\s*克/.test(tipAfter), tipAfter)

  await page.locator('.el-dialog__footer button.el-button--primary').first().click()
  await page.waitForURL(/\/plan\?tab=diet/, { timeout: 12000 })
  check('★ 保存后回到 /plan?tab=diet（带 query，回首页必刷新）', /tab=diet/.test(page.url()), page.url())

  await waitForRing(page)
  await waitUntil(async () => (await readDiet(page)).intake !== beforeIntake, 12000)
  const after = await readDiet(page)
  const breakfastAfter = after.meals.find((m) => m.label === '早餐')
  const newRow = breakfastAfter?.items?.find((i) => i.name === chosenName)

  check('★ 摄入变大了', after.intake > beforeIntake, `${beforeIntake} → ${after.intake}`)
  check('★ 早餐卡多了一条食物行（是同一餐次、新食物 → 不合并）',
    (breakfastAfter?.items?.length || 0) === beforeMealItems + 1,
    `${beforeMealItems} → ${breakfastAfter?.items?.length}`)
  check('★ 摄入增量 == 新那一行的千卡（总数 = 明细之和，不是另算一个数）',
    newRow && after.intake - beforeIntake === newRow.kcal,
    `增量 ${after.intake - beforeIntake} / 行 ${newRow?.kcal}`)
  check('★ 份量行显示「50克」（不是 100g）', newRow?.amount === '50克', String(newRow?.amount))
  check('★ 「还可以吃」减少了同样的数值',
    after.ringNum === before.ringNum - (after.intake - beforeIntake),
    `${before.ringNum} → ${after.ringNum}`)
  check('★ 碳水进度前进了',
    Number((after.api?.macros || []).find((m) => m.key === 'carb')?.current)
    > Number((before.api?.macros || []).find((m) => m.key === 'carb')?.current),
    `${(before.api?.macros || []).find((m) => m.key === 'carb')?.current} → ${(after.api?.macros || []).find((m) => m.key === 'carb')?.current}`)
  await page.screenshot({ path: join(SHOT_DIR, '03-diet-after-add.png') })

  // ---- 3.2 再记一次同样的食物 50g → 必须"合并"，不是多一行 ----
  const oneShotKcal = after.intake - beforeIntake
  await page.locator('.quick-btn').filter({ hasText: '记早餐' }).first().click()
  await page.waitForURL(/\/plan\/food-lib/, { timeout: 12000 })
  await waitUntil(async () => (await page.locator('.food-card').count()) > 0, 15000)
  const againIndex = (await page.locator('.food-card .name').allInnerTexts()).map((n) => n.trim()).indexOf(chosenName)
  await page.locator('.food-card').nth(againIndex).locator('.add').click()
  const input2 = page.locator('.el-dialog .el-input-number input').first()
  await input2.fill('50')
  await input2.press('Enter')
  await sleep(300)
  await page.locator('.el-dialog__footer button.el-button--primary').first().click()
  await page.waitForURL(/\/plan\?tab=diet/, { timeout: 12000 })
  await waitUntil(async () => (await readDiet(page)).intake === beforeIntake + oneShotKcal * 2, 12000)

  const merged = await readDiet(page)
  const mergedRows = (merged.meals.find((m) => m.label === '早餐')?.items || []).filter((i) => i.name === chosenName)
  check('★★ 再记一次同食物同单位 → 仍然只有一行（合并，不是插新行）', mergedRows.length === 1,
    `匹配 ${mergedRows.length} 行`)
  check('★★ 合并后份量变成「100克」', mergedRows[0]?.amount === '100克', String(mergedRows[0]?.amount))
  check('★★ 合并后热量也翻倍（只翻份量不翻热量是典型错法）',
    mergedRows[0]?.kcal === oneShotKcal * 2, `${oneShotKcal} → ${mergedRows[0]?.kcal}`)
  check('★ 摄入也翻倍（明细与总数同步）',
    merged.intake === beforeIntake + oneShotKcal * 2, `${merged.intake} / 期望 ${beforeIntake + oneShotKcal * 2}`)

  // ==================== 4. 删掉它 → 三个数字回退 ====================
  section('4. 删掉刚记的那一条 → 三个数字回到记之前')

  const rowLocator = page.locator('.meal-card').filter({ hasText: '早餐' })
    .locator('.food-row').filter({ hasText: chosenName })
  await rowLocator.locator('button').last().click()
  const confirmDialog = await waitText(page, '删除记录')
  check('点「删」先弹确认框（不是一点就没）', !!confirmDialog)
  await page.locator('.el-message-box__btns button').filter({ hasText: '删除' }).first().click()
  await waitUntil(async () => (await readDiet(page)).intake === beforeIntake, 12000)

  const afterDel = await readDiet(page)
  check('★ 摄入回到记之前', afterDel.intake === beforeIntake, `${afterDel.intake} / 期望 ${beforeIntake}`)
  check('★ 「还可以吃」也回到记之前', afterDel.ringNum === before.ringNum,
    `${afterDel.ringNum} / 期望 ${before.ringNum}`)
  check('早餐卡回到原来的条数',
    (afterDel.meals.find((m) => m.label === '早餐')?.items?.length || 0) === beforeMealItems,
    `${afterDel.meals.find((m) => m.label === '早餐')?.items?.length}`)
  check('删干净了：早餐里已经没有这个食物',
    !(afterDel.meals.find((m) => m.label === '早餐')?.items || []).some((i) => i.name === chosenName))

  // ==================== 5. 记运动：可记录，但不抵扣 ====================
  section('5. 记运动：有记录、有文案，但「还可以吃」不变')

  const beforeSport = await readDiet(page)
  await page.locator('.quick-btn').filter({ hasText: '记运动' }).first().click()
  await page.waitForURL(/\/plan\/sport-lib/, { timeout: 12000 })
  check('点「记运动」→ /plan/sport-lib', page.url().includes('sport-lib'), page.url())

  await waitUntil(async () => (await page.locator('.sport-card').count()) > 0, 15000)
  const sportCardText = (await page.locator('.sport-card').first().innerText()).replace(/\s+/g, ' ')
  check('运动卡带「N 千卡/30分钟」', /千卡\/30分钟/.test(sportCardText), sportCardText.slice(0, 60))

  await page.locator('.sport-card button').first().click()
  const sportDialog = await waitText(page, '基准消耗')
  check('点运动卡弹出时长弹层', !!sportDialog)

  const baseText = await page.locator('.dialog-base b').first().innerText()
  const expectKcal = Number((baseText.match(/(\d+)/) || [])[1])
  const previewText = await page.locator('.preview').first().innerText()
  check('弹层默认 30 分钟，预览消耗 = 基准值', previewText.replace(/\s+/g, '').includes(String(expectKcal)),
    `${baseText} | ${previewText.replace(/\s+/g, '')}`)
  check('★ 弹层写明"不会加到还可以吃里"', previewText.includes('以后端') || (await page.locator('.note').first().innerText()).includes('不会加到'),
    (await page.locator('.note').first().innerText()).slice(0, 40))
  await page.screenshot({ path: join(SHOT_DIR, '04-sport-dialog.png') })

  await page.locator('.el-dialog__footer button.el-button--primary').first().click()
  await page.waitForURL(/\/plan\?tab=diet/, { timeout: 12000 })
  await waitForRing(page)
  await waitUntil(async () => {
    const s = await readDiet(page)
    return Number(s.api?.sportKcal) > Number(beforeSport.api?.sportKcal)
  }, 12000)

  const afterSport = await readDiet(page)
  check('★ 运动消耗增加了（记完运动首页有变化）',
    Number(afterSport.api?.sportKcal) === Number(beforeSport.api?.sportKcal) + expectKcal,
    `${beforeSport.api?.sportKcal} → ${afterSport.api?.sportKcal}（+${expectKcal}）`)
  check('★ 页面上出现"运动消耗 X 千卡 · 不计入预算"',
    afterSport.sportNote.includes('不计入预算'), afterSport.sportNote)
  check('★★ 记完运动「还可以吃」一点没变（口径 1：不抵扣）',
    afterSport.ringNum === beforeSport.ringNum, `${beforeSport.ringNum} → ${afterSport.ringNum}`)
  check('摄入也没变（运动不写进饮食日志）', afterSport.intake === beforeSport.intake,
    `${beforeSport.intake} → ${afterSport.intake}`)

  // ==================== 6. 饮食计划详情 ====================
  section('6. 饮食计划：推荐卡 → 详情 → 第1~5天 → 「加餐①」')

  const recommendText = await waitText(page, '推荐饮食计划')
  check('首页有「推荐饮食计划」区块', !!recommendText)
  await waitUntil(async () => (await page.locator('.diet-home .plan-card').count()) >= 3, 12000)
  const dietCards = await page.locator('.diet-home .plan-card').count()
  check('推荐饮食计划卡 ≥ 3 张', dietCards >= 3, `${dietCards} 张`)

  await page.locator('.diet-home .plan-card').first().click()
  await page.waitForURL(/\/plan\/diet-plan\/\d+/, { timeout: 12000 })
  check('点卡片 → /plan/diet-plan/:id', /\/plan\/diet-plan\/\d+/.test(page.url()), page.url())

  check('详情页出现「第1天」…「第5天」的天数切换',
    (await waitUntil(async () => (await page.locator('.el-radio-button').count()) === 5, 12000)),
    `${await page.locator('.el-radio-button').count()} 个`)
  const detailText = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
  check('★ 出现「加餐①」（每日六餐靠 meal_index 区分，第一顿也带序号）',
    /加餐①/.test(detailText), '')
  check('详情里有「当天合计」这类由后端汇总的数字',
    /合计/.test(detailText) && /千卡/.test(detailText), '')
  check('食物项带份量文案（如 100g）', /\d+g/.test(detailText), '')
  await page.screenshot({ path: join(SHOT_DIR, '05-diet-plan.png') })

  // ★ 点 .el-radio-button__inner（可见的那层），不要点外层 label：
  //   Element Plus 的 radio 里有一个透明度 0 的 input，点它不一定触发 v-model。
  const day1Sum = (await page.locator('.day-summary').first().innerText()).replace(/\s+/g, ' ')
  await page.locator('.el-radio-button__inner').nth(1).click()
  await waitUntil(async () => {
    const now = (await page.locator('.day-summary').first().innerText()).replace(/\s+/g, ' ')
    return now !== day1Sum
  }, 6000)
  const day2Sum = (await page.locator('.day-summary').first().innerText()).replace(/\s+/g, ' ')
  check('★ 切到第 2 天「当天合计」变了（真换了数据，不是一直渲染第 1 天）',
    day1Sum !== day2Sum, `${day1Sum.slice(0, 42)} → ${day2Sum.slice(0, 42)}`)
  check('第 2 天同样是 6 餐（六餐结构每天都成立）',
    (await page.locator('.meal').count()) === 6, `${await page.locator('.meal').count()} 餐`)

  // ==================== 7. 空态（没有记录的那一天） ====================
  section('7. 空态：没记录的那一天显示"还没记"，不是一片 0')

  await page.goto(`${PORTAL}/plan?tab=diet&date=2019-01-01`, { waitUntil: 'domcontentloaded' })
  await waitForRing(page)
  const empty = await readDiet(page)
  check('★ 空日期摄入是 0', empty.intake === 0, String(empty.intake))
  check('★ 空日期「还可以吃」= 全部预算（不是 null / 不是 0）',
    empty.ringNum === numFromText(empty.ringSub) && empty.ringNum > 0,
    `${empty.ringNum} / 预算 ${numFromText(empty.ringSub)}`)
  const emptyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
  check('★ 空态文案是人话（「还没记」），不是空白',
    /还没记/.test(emptyText), emptyText.slice(0, 80))
  await page.screenshot({ path: join(SHOT_DIR, '06-empty-day.png') })

  // ==================== 8. 网络与控制台 ====================
  section('8. 网络与控制台')

  const api4xx = apiBad.filter((x) => !x.includes('/diet/logs/')) // 删除/校验类请求允许 4xx
  check('★ 没有接口级失败（/pls/** 全部 <400，删除校验类除外）', api4xx.length === 0,
    api4xx.slice(0, 2).join(' | '))
  check('页面无 JS 运行时错误', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '))

  await context.close()
  await closeBrowser()

  console.log('\n' + '='.repeat(78))
  console.log(`截图：${SHOT_DIR}`)
  console.log(`结果：通过 ${passed} 项 / 失败 ${failed} 项`)
  if (failed > 0) {
    console.log('\n失败明细：')
    failures.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
    process.exitCode = 1
  } else {
    console.log('全部通过 ✅')
  }
  console.log('='.repeat(78))
}

/** 从「预算 1820 千卡」里取 1820 */
function numFromText(text) {
  const m = String(text || '').replace(/,/g, '').match(/(\d+(\.\d+)?)/)
  return m ? Number(m[1]) : null
}

/** 等圆环出现（DOM 有 .ring-num 说明接口回来了，不是骨架屏） */
async function waitForRing(page) {
  try {
    await page.locator('.ring-num').first().waitFor({ state: 'visible', timeout: 15000 })
    return true
  } catch {
    return false
  }
}

main().catch(async (e) => {
  console.error('\n脚本异常终止：', e)
  await closeBrowser().catch(() => {})
  process.exit(2)
})
