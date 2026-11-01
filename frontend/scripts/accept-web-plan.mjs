#!/usr/bin/env node
/**
 * ============================================================
 * M11-2 计划模块 · 前端验收（P0）
 * ------------------------------------------------------------
 * 前置（本机最省事的一套，不需要 Nacos / 网关 / 登录服务）：
 *   · fm-plan(8095) 在跑
 *   · `node scripts/dev-plan-proxy.mjs`（假网关，10010 → 8095，固定 user-info=1）
 *   · `cd frontend/fitmind-portal && npm run dev`（5173）
 *
 * 用法：cd frontend && node scripts/accept-web-plan.mjs
 *
 * ★★ 这个脚本要证明的事，和 `scripts/accept-plan-p0.mjs` **不重叠**：
 *   那边查的是"库里对不对"，这边查的是"页面上真的看得见、点得动"。
 *
 *   典型的分工：
 *     - "删除训练日" 接口在库里删干净了（后端脚本验）
 *     - 但页面上的「删除」按钮**在不在、点了有没有确认框**（这个脚本验）
 *   两条都过，才叫"做完了"。
 *
 * ★★ 三条写法纪律（都是被假绿坑出来的）：
 *   1. **断言可见文本，不断言 class** —— class 改名不报错，文本变了才是功能变了。
 *   2. **先等 `data-testid`/文案出现再断言**，不用固定 `sleep` 赌加载速度。
 *   3. **token 是假的**：假网关不看 token，只认 user-info。所以本脚本
 *      **不能**用来验"未登录会被拦"——那件事归后端脚本 + 真网关。
 * ============================================================
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { closeBrowser, launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots', 'plan')

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

/** 等某个文本出现，返回它所在的定位器；超时返回 null（不抛，交给 check 记失败） */
async function waitText(page, text, timeout = 12000) {
  try {
    const loc = page.getByText(text, { exact: false }).first()
    await loc.waitFor({ state: 'visible', timeout })
    return loc
  } catch {
    return null
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 轮询等一个条件成立。
 *
 * ★ 为什么需要它：`waitText` 只能等"某个文本出现"，而这里的断言是
 *   "按钮数量 > 0" —— 页面头部的静态文案（如"编排训练日"）**先于接口返回**渲染，
 *   等它出现就开始数按钮，数到的永远是 0（假红）。
 *   这不是功能坏了，是脚本抢跑；所以判据要绑在"数据到了"这件事上。
 */
async function waitUntil(fn, timeout = 15000, step = 300) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) return true
    await sleep(step)
  }
  return false
}

async function main() {
  console.log('='.repeat(78))
  console.log('M11-2 / P0 前端验收：计划模块（训练编排 / 执行 / 动作库）')
  console.log('='.repeat(78))

  await requireReachable(`${PROXY}/pls/plans/recommend`, '假网关 10010')
  await requireReachable(PORTAL, '前端 dev server 5173')

  // 取一个"自建计划"的 id（要验编辑页的删除/改名按钮，官方计划没有这些）
  const mine = await (await fetch(`${PROXY}/pls/plans/mine`)).json()
  const ownPlan = (mine?.data || []).find((p) => p.title?.includes('验收') && p.dayCount > 0) || mine?.data?.[0]
  const ownPlanId = ownPlan?.id
  check('拿到了一个可编辑的自建计划', !!ownPlanId, ownPlan?.title || '无')
  if (!ownPlanId) throw new Error('没有自建计划可用，先跑 scripts/accept-plan-p0.mjs 造一个')

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  const errors = []
  const notFound = []
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text())
  })
  page.on('response', (r) => {
    if (r.status() === 404) notFound.push(r.url())
  })

  // 先落到同源页面，才能写 localStorage（跨域写不了）
  await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(([k]) => localStorage.setItem(k, 'accept-web-plan-fake-token'), [TOKEN_KEY])

  // ==================== 1. 计划首页 ====================
  section('1. /plan 首页：三个 tab + 今日训练 + 推荐计划')

  await page.goto(`${PORTAL}/plan`, { waitUntil: 'domcontentloaded' })
  check('页面标题出现「计划」', !!(await waitText(page, '计划')))
  check('tab「训练」在', !!(await waitText(page, '训练')))
  check('tab「我的计划」在', !!(await waitText(page, '我的计划')))
  check('今日训练的分钟数有渲染', !!(await waitText(page, '今日训练')))

  const cards = page.locator('[data-testid="plan-card"]')
  // 首屏也是异步的：等卡片真的渲染出来再数（否则读到 0 就是假红）
  await waitUntil(async () => (await cards.count()) >= 3, 15000)
  const cardCount = await cards.count()
  check('★ 推荐计划卡片渲染出来了（≥3 张）', cardCount >= 3, `实际 ${cardCount} 张`)

  const firstCardTitle = (await cards.first().innerText()).split('\n')[0]
  check('卡片上有计划名', !!firstCardTitle, firstCardTitle)

  await page.screenshot({ path: join(SHOT_DIR, '01-plan-home.png'), fullPage: false })

  // ==================== 2. 点卡片 → 计划详情 ====================
  section('2. 点推荐卡片 → 计划详情')

  await cards.first().click()
  await page.waitForURL(/\/plan\/detail\/\d+/, { timeout: 12000 })
  check('路由进到 /plan/detail/:id', /\/plan\/detail\/\d+/.test(page.url()), page.url())

  const execBtn = await waitText(page, '执行本计划')
  const reExecBtn = execBtn ? null : await waitText(page, '重新执行本计划')
  check('★ 底部出现「执行本计划」（或"重新执行"）', !!(execBtn || reExecBtn), execBtn ? '执行本计划' : reExecBtn ? '重新执行' : '都没有')
  check('详情页展示了训练日（第 1 练 / 周一 之类）',
    !!(await waitText(page, '第 1 练')) || !!(await waitText(page, '周一')) || !!(await waitText(page, '计划内容')))

  await page.screenshot({ path: join(SHOT_DIR, '02-plan-detail.png') })

  // ==================== 3. 编排页：改名 / 删除训练日（P0 补齐的两个入口） ====================
  section('3. /plan/edit/:id 编排页：「改名」与「删除」入口真的存在')

  await page.goto(`${PORTAL}/plan/edit/${ownPlanId}`, { waitUntil: 'domcontentloaded' })
  check('编排页标题「编排训练日」在', !!(await waitText(page, '编排训练日')))

  const renameBtn = await waitText(page, '改名')
  check('★ 有「改名」入口（对应 PUT /pls/plans/{id}）', !!renameBtn)

  const delBtn = page.getByRole('button', { name: '删除', exact: true })
  // 训练日列表是异步渲染的：等"删除按钮 >0"或"训练日空态"出现，再断言
  await waitUntil(async () => (await delBtn.count()) > 0 || !!(await waitText(page, '还没有训练日', 600)), 15000)
  const delCount = await delBtn.count()
  check('★ 每个训练日都有「删除」按钮（对应 DELETE /pls/plans/days/{dayId}）', delCount > 0, `实际 ${delCount} 个`)

  await page.screenshot({ path: join(SHOT_DIR, '03-plan-edit.png') })

  // 点一下删除 → 必须弹确认框（不能点一下就真删）
  if (delCount > 0) {
    await delBtn.first().click()
    const dialog = await waitText(page, '删除训练日')
    check('★ 点「删除」弹了确认框（不是一点就删）', !!dialog)
    if (dialog) {
      const cancel = page.getByRole('button', { name: '取消', exact: true })
      if (await cancel.count()) await cancel.first().click()
    }
    await sleep(300)
  }

  // ==================== 4. 动作库 ====================
  section('4. /plan/exercise-lib 动作库：左侧分类 + 卡片 + 底部已选计数')

  // 用真实存在的训练日 id：假 id 会让"确认添加"必然失败，那样验的就不是页面了
  const detail = await (await fetch(`${PROXY}/pls/plans/${ownPlanId}`)).json()
  const realDayId = detail?.data?.days?.[0]?.id
  check('拿到计划里真实的训练日 id', !!realDayId, String(realDayId))

  await page.goto(`${PORTAL}/plan/exercise-lib?planId=${ownPlanId}&dayId=${realDayId}`, {
    waitUntil: 'domcontentloaded',
  })
  // ★★ 2026-11-01（P3 全量导入）改：字典已动态化。
  //   旧断言写的是「左侧一级分类有『热身』」—— 那条结论的前提（"P3 导 876 条时热身自然会有"）
  //   已被实测推翻：873 条里 category 没有任何热身值。现在字典只返回库里真有数据的分类，
  //   "热身"从左侧栏**消失**才是正确行为；继续断言"有热身"，等于要求代码保留一个死入口。
  // ★ 必须等左侧栏渲染出来：字典是进页面后才拉的，goto 完立刻读会拿到空数组
  //   （第一版就是这么写的，结果"有全部/有拉伸"两条假红 —— 页面其实是对的）
  await page.waitForSelector('.sidebar .side-item', { timeout: 15000 })
  const sideItems = (await page.locator('.sidebar .side-item').allInnerTexts()).map((t) => t.trim())
  check('左侧一级分类有「全部」', sideItems.includes('全部'), sideItems.join('/'))
  check('★ 左侧**没有**「热身」（数据集无此分类 → 字典动态化后该入口应消失）',
    !sideItems.includes('热身'), sideItems.join('/'))
  check('左侧有「拉伸」「有氧」（这两类库里有数据）',
    sideItems.includes('拉伸') && sideItems.includes('有氧'), sideItems.join('/'))

  // ★ 必须等骨架屏消失再断言：加载中 `.el-skeleton` 里没有任何按钮，
  //   这时候断言"按钮 ≥1"必然失败 —— 而那不是功能坏了，是脚本抢跑（假红）。
  const ready = await waitText(page, '添加动作', 15000)
  const emptyState = ready ? null : await waitText(page, '没有符合条件的动作', 1000)
  check('★ 动作卡渲染出来了（有「添加动作」按钮）', !!ready, ready ? '' : '超时未见按钮')
  check('（若为空）空态文案是「没有符合条件的动作」而不是一片空白', !!ready || !!emptyState)

  const addBtns = page.getByRole('button', { name: '添加动作', exact: true })
  const addCount = await addBtns.count()
  check('卡片数量 > 0', addCount > 0, `实际 ${addCount} 个`)

  // 点第一个「添加动作」→ 文案变成「已选」、底部计数 +1
  if (addCount > 0) {
    await addBtns.first().click()
    const selected = await waitText(page, '已选 1 个动作', 5000)
    check('★ 点「添加动作」后底部计数变成「已选 1 个动作」', !!selected)
  }

  check('底部有「确认添加」按钮（一次性提交，中途退出不落库）',
    (await page.getByRole('button', { name: '确认添加' }).count()) > 0)

  await page.screenshot({ path: join(SHOT_DIR, '04-exercise-lib.png') })

  // ==================== 5. 控制台错误 ====================
  section('5. 网络与控制台')

  // ★ 区分三类 404，别一锅端：
  //   ① /pls/**  → 计划模块接口，坏了就是功能坏了（必须 0）
  //   ② 图片     → 动作图是 ghfast.top 外链（已知遗留，本机被墙就 404）
  //   ③ 其它     → 本代理只转发 /pls/**，`/us/users/me` 之类必然 404（假网关的局限，不是产品问题）
  const api404 = notFound.filter((u) => u.includes('/pls/'))
  const img404 = notFound.filter((u) => /\.(png|jpe?g|webp|gif)(\?|$)/i.test(u))
  const other404 = notFound.filter((u) => !api404.includes(u) && !img404.includes(u))
  check('★ 没有接口级 404（/pls/** 全部命中）', api404.length === 0, api404.slice(0, 2).join(' | '))
  // ★★ 2026-11-01（P3）改：这一条以前是 `console.log` 的"已知遗留"提示
  //   （动作图走 ghfast.top 外链，本机被墙就 404）。P3 已把 1746 张图转存到对象存储、
  //   DB 改存对象 key —— 遗留 #7 收尾，提示升级成**硬断言**。
  //   ⚠️ 这条只覆盖"404"（notFound 按 `status() === 404` 收集）。
  //      "200 但 Content-Type 是 binary/octet-stream → <img> 不渲染"那个坑，
  //      由后端脚本 accept-plan-p3.mjs 断言（200 / image/jpeg / >10000 字节）——
  //      前端这里拿不到响应头里的类型判断成本更高，分工即可。
  check('★★ 动作图 0 张 404（P3 后图在对象存储里，不再是外链 → 遗留 #7 收尾）',
    img404.length === 0, img404.slice(0, 3).join(' | '))
  if (other404.length > 0) {
    console.log(`  ℹ️ ${other404.length} 个非计划接口 404（假网关只转发 /pls/**，属测试环境局限）`)
    console.log(`     例：${other404[0].slice(0, 90)}`)
  }
  check('页面无 JS 运行时错误（console.error 里排除图片 404）',
    errors.filter((e) => !e.includes('Failed to load resource')).length === 0,
    errors.filter((e) => !e.includes('Failed to load resource')).slice(0, 2).join(' | '))

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

main().catch(async (e) => {
  console.error('\n脚本异常终止：', e)
  await closeBrowser().catch(() => {})
  process.exit(2)
})
