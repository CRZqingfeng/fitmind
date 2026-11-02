#!/usr/bin/env node
/**
 * ============================================================
 * A32 前端验收：「再定制一份」入口 + 「停止执行」（UI 可达性）
 * ------------------------------------------------------------
 * 背景：清单 §A32 —— 功能 P2 就上线了，但**第一个真实用户找不到入口**：
 *   「定制计划」只在 NO_PLAN 态出现；一旦有计划在执行，界面没有任何出口。
 *   所以这个脚本验的不是"接口能不能调"，而是**按钮在不在、点得动、点了会怎样**。
 *
 * 前置（本机最省事的一套）：
 *   · fm-plan(8095) 在跑
 *   · `node scripts/dev-plan-proxy.mjs`（假网关 10010）
 *   · `cd frontend/fitmind-portal && npm run dev`（5173）
 *
 * 用法：cd frontend && node scripts/accept-web-plan-a32.mjs
 *
 * ★★ 三条纪律（与 P0/P1/P2 一致）：
 *   1. 断言**可见文本**，不赌 class 名；
 *   2. 异步渲染一律 `waitUntil` 轮询，不写死 sleep 赌速度；
 *   3. 和 `scripts/accept-plan-a32.mjs` **不重叠**：那边查库，这边查页面。
 *
 * ⚠️ 本脚本会**真的停掉一个计划**（跑完再恢复成执行中），所以不能和别的 UI 脚本并行跑。
 * ============================================================
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { closeBrowser, launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots', 'plan-a32')

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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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

async function waitText(page, text, timeout = 12000) {
  try {
    const loc = page.getByText(text, { exact: false }).first()
    await loc.waitFor({ state: 'visible', timeout })
    return loc
  } catch {
    return null
  }
}

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
  console.log('A32 前端验收：「再定制一份」入口 + 「停止执行」')
  console.log('='.repeat(78))

  await requireReachable(`${PROXY}/pls/plans/recommend`, '假网关 10010')
  await requireReachable(PORTAL, '前端 dev server 5173')

  // ==================== 0. 前置：保证有一个"进行中"的计划 ====================
  // ★ 这是本脚本最关键的一步：A32 的 bug 只在"有计划在执行"时出现。
  //   没有这个前置，下面的断言会在 NO_PLAN 态下全绿 —— 而那个态本来就没问题（典型假绿）。
  const mineList = (await (await fetch(`${PROXY}/pls/plans/mine`)).json())?.data || []
  const pick = mineList.find((p) => p.dayCount > 0) || null
  check('库里存在可执行的计划（用来造"进行中"态）', !!pick, pick?.title || '无')
  if (!pick) {
    throw new Error('没有可用计划，先跑 scripts/accept-plan-p0.mjs 造一个')
  }

  const execRes = await fetch(`${PROXY}/pls/plans/${pick.id}/execute`, { method: 'POST' })
  const execJson = await execRes.json().catch(() => null)
  check('把它执行起来（造出"有计划在执行"这个 A32 现场）', execJson?.code === 200, JSON.stringify(execJson).slice(0, 120))

  const today = (await (await fetch(`${PROXY}/pls/plans/today`)).json())?.data
  check('今日训练已指向它（前置成立）', String(today?.planId) === String(pick.id),
    `today=${today?.planId} 期望 ${pick.id}`)
  check('★ 它的状态是"今天要练"或"今天休息"（不是"还没有计划"）',
    today?.status === 1 || today?.status === 2, `status=${today?.status}`)

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  const errors = []
  const badRequests = []
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text())
  })
  page.on('response', (r) => {
    if (r.url().includes('/pls/') && r.status() >= 400) {
      badRequests.push(`${r.status()} ${r.url()}`)
    }
  })

  await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(([k]) => localStorage.setItem(k, 'accept-web-plan-a32-fake-token'), [TOKEN_KEY])

  try {
    // ==================== 1. 有计划时，首页必须有"再定制一份" ====================
    section('1. /plan 首页：有执行中计划时，换计划的入口必须可见')

    await page.goto(`${PORTAL}/plan`, { waitUntil: 'domcontentloaded' })
    await waitText(page, '今日训练')

    const customBtn = await waitText(page, '再定制一份')
    check('★★ 「再定制一份」按钮可见（A32 的核心：不再需要手敲 /plan/ai）',
      !!customBtn, customBtn ? '可见' : '没找到')

    // 反证：这个页面此刻**不是** NO_PLAN 态，所以"可见"不是因为走了老分支
    const noPlanText = await page.getByText('当前没有执行的训练计划').count()
    check('★ 此时页面不是"还没有计划"态（证明按钮来自新加的分支，不是老的那个）',
      noPlanText === 0, `NO_PLAN 文案出现 ${noPlanText} 次`)

    await page.screenshot({ path: join(SHOT_DIR, '01-home-with-plan.png'), fullPage: false })

    // ==================== 2. 点它 → AI 定制页 ====================
    section('2. 点「再定制一份」→ 进 /plan/ai')

    await customBtn.click()
    await page.waitForURL(/\/plan\/ai/, { timeout: 12000 })
    check('★ 路由跳到 /plan/ai', /\/plan\/ai/.test(page.url()), page.url())
    check('AI 定制页渲染出来了（不是白屏）',
      !!(await waitText(page, '定制')) || !!(await waitText(page, '目标')) || !!(await waitText(page, '体重')))

    await page.screenshot({ path: join(SHOT_DIR, '02-plan-ai.png'), fullPage: false })

    // ==================== 3. 我的计划 tab：停止执行 ====================
    section('3. 「我的计划」tab：进行中的计划有「停止执行」')

    await page.goto(`${PORTAL}/plan?tab=mine`, { waitUntil: 'domcontentloaded' })
    // 等卡片真渲染出来（tab 内容是异步的）
    await waitUntil(async () => (await page.locator('.mine-card').count()) > 0, 15000)

    const cardCount = await page.locator('.mine-card').count()
    check('★ 「我的计划」里至少 1 张卡片', cardCount > 0, `实际 ${cardCount} 张`)

    const stopButtons = page.getByRole('button', { name: '停止执行' })
    await waitUntil(async () => (await stopButtons.count()) > 0, 10000)
    const stopBtnCount = await stopButtons.count()
    check('★★ 进行中的计划卡片上有「停止执行」按钮', stopBtnCount > 0, `实际 ${stopBtnCount} 个`)

    // ★★ 后面要停的是**指定那一份**，所以按钮必须按 planId 定位。
    //   用 `.first()` 是在赌"刚 execute 的那份排第一"（靠 update_time 排序），
    //   一旦有别的计划刚被改过，就会停错计划 —— 功能是对的、断言是对的、操作对象错了。
    const pickCard = page.locator(`.mine-card[data-plan-id="${pick.id}"]`).first()
    check('★ 那一份计划自己的卡片有「停止执行」',
      (await pickCard.getByRole('button', { name: '停止执行' }).count()) === 1)

    // 已放弃的卡片不该有停止按钮（后端也会拒，但按钮根本不该出现）
    const abandonedCards = page.locator('.mine-card.is-abandoned')
    const abandonedCount = await abandonedCards.count()
    if (abandonedCount > 0) {
      const stopInAbandoned = await abandonedCards.getByRole('button', { name: '停止执行' }).count()
      check('★ 已放弃的卡片上没有「停止执行」（没有可停的东西）',
        stopInAbandoned === 0, `实际 ${stopInAbandoned} 个`)
    } else {
      console.log('  ⏭ 当前没有"已放弃"的卡片，跳过这条')
    }

    await page.screenshot({ path: join(SHOT_DIR, '03-mine-with-stop.png'), fullPage: false })

    // ==================== 4. 点停止 → 二次确认 → 真的停下来 ====================
    section('4. 点「停止执行」→ 先弹确认框 → 确认后状态变"已放弃"')

    // ★ 记录停之前有多少张卡片：确认框"取消"路径不该改变它
    const cardsBefore = await page.locator('.mine-card').count()

    await pickCard.getByRole('button', { name: '停止执行' }).click()

    const box = page.locator('.el-message-box')
    const boxShown = await waitUntil(async () => (await box.count()) > 0, 8000)
    check('★★ 先弹二次确认框（不是点了就停）', boxShown)
    // ★ detail 必须写"实际看到了什么"，不能写成"没找到 XXX" ——
    //   那种写法在通过时也会打印出来，读日志的人会以为它红了（本项目最忌这种表述）
    const boxText = ((await box.innerText().catch(() => '')) || '').replace(/\n/g, ' ')
    check('★ 确认框里说清了"记录都会保留"（让人敢点）',
      boxText.includes('保留'), `确认框实际文案：${boxText.slice(0, 70)}`)
    check('★ 确认框点名了是哪一份计划（不是笼统的"确定吗"）',
      boxText.includes(pick.title), `确认框实际文案：${boxText.slice(0, 70)}`)

    // 先点"再想想"：验证取消不会误伤
    const cancelBtn = box.getByRole('button', { name: '再想想' })
    if (await cancelBtn.count()) {
      await cancelBtn.click()
      await sleep(600)
      const statusAfterCancel = (await (await fetch(`${PROXY}/pls/plans/mine`)).json())
        ?.data?.find((p) => String(p.id) === String(pick.id))?.status
      check('★ 点"再想想"之后计划仍在执行（没有偷偷停掉）', statusAfterCancel === 0,
        `status=${statusAfterCancel}`)
      check('取消后卡片数量没变', (await page.locator('.mine-card').count()) === cardsBefore)
    }

    // 再点一次，这次真停（仍然是同一张卡片，不是"第一个按钮"）
    await page.locator(`.mine-card[data-plan-id="${pick.id}"]`)
      .getByRole('button', { name: '停止执行' }).click()
    await waitUntil(async () => (await box.count()) > 0, 8000)
    await box.getByRole('button', { name: '停止执行' }).click()

    const stopped = await waitUntil(async () => {
      const rows = (await (await fetch(`${PROXY}/pls/plans/mine`)).json())?.data || []
      return rows.find((p) => String(p.id) === String(pick.id))?.status === 2
    }, 12000)
    check('★★ 确认后接口里 status 真的变成 2（页面不是只改了字）', stopped)

    const successToast = await waitText(page, '已停止执行', 6000)
    check('★ 弹出成功提示「已停止执行，可以重新挑一个计划了」', !!successToast)

    // 页面自动刷新：那张卡片应该变成"已放弃"、并沉到列表末尾
    await waitUntil(async () => {
      const rows = (await (await fetch(`${PROXY}/pls/plans/mine`)).json())?.data || []
      return rows.find((p) => String(p.id) === String(pick.id))?.status === 2
    }, 8000)
    await sleep(800)
    // ★★ 定位必须按 **planId**（卡片上的 data-plan-id），不能按标题：
    //   AI 定制的计划标题全都叫「为你定制的增肌训练计划」，按标题 filter 会抓到别人的卡片
    //   —— 第一次写就是这样，红了一条，而功能其实是好的（"按名字找人"在重名时必然出错）。
    const targetCard = page.locator(`.mine-card[data-plan-id="${pick.id}"]`).first()
    const targetText = ((await targetCard.innerText().catch(() => '')) || '').replace(/\n/g, ' ')
    check('★ 刚停掉的那张卡片上出现「已放弃」标签',
      targetText.includes('已放弃'), `卡片文案：${targetText.slice(0, 60)}`)
    check('★ 那张卡片上不再有「停止执行」',
      (await targetCard.getByRole('button', { name: '停止执行' }).count()) === 0)

    // 沉底：按 DOM 顺序比对 —— 所有"已放弃"都必须排在所有"非已放弃"之后
    const cardTexts = await page.locator('.mine-card').allInnerTexts()
    const firstAbandonedIdx = cardTexts.findIndex((t) => t.includes('已放弃'))
    let lastAliveIdx = -1
    cardTexts.forEach((t, i) => { if (!t.includes('已放弃')) lastAliveIdx = i })
    if (firstAbandonedIdx >= 0 && lastAliveIdx >= 0) {
      check('★ 已放弃的全部沉到列表末尾（没占着第一屏）',
        firstAbandonedIdx > lastAliveIdx,
        `第一个已放弃在第 ${firstAbandonedIdx + 1} 张，最后一个进行中/已完成在第 ${lastAliveIdx + 1} 张`)
    } else {
      console.log('  ⏭ 列表里只有一类状态，无法比对排序')
    }

    await page.screenshot({ path: join(SHOT_DIR, '04-after-abandon.png'), fullPage: false })

    // ==================== 5. 详情页：进行中才有停止按钮 ====================
    section('5. 计划详情页：已放弃的不再显示「停止执行」')

    await page.goto(`${PORTAL}/plan/detail/${pick.id}`, { waitUntil: 'domcontentloaded' })
    await waitText(page, '第 1 练').catch(() => null)
    await sleep(800)
    const detailStop = await page.getByRole('button', { name: '停止执行' }).count()
    check('★ 已放弃的计划详情页没有「停止执行」', detailStop === 0, `实际 ${detailStop} 个`)
    check('★ 底部按钮变成「重新执行本计划」（还能再练一轮）',
      (await page.getByRole('button', { name: '重新执行本计划' }).count()) > 0)

    await page.screenshot({ path: join(SHOT_DIR, '05-detail-abandoned.png'), fullPage: false })

    // 恢复成进行中，再看按钮回来
    await fetch(`${PROXY}/pls/plans/${pick.id}/execute`, { method: 'POST' })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await sleep(1200)
    const detailStop2 = await page.getByRole('button', { name: '停止执行' }).count()
    check('★★ 恢复成"进行中"后，「停止执行」又出现了（按钮由状态驱动，不是写死的）',
      detailStop2 > 0, `实际 ${detailStop2} 个`)

    // ==================== 6. 网络与控制台 ====================
    section('6. 网络与控制台')

    const realErrors = badRequests.filter((u) => !u.includes('/abandon')) // 停止接口本身是 200，这里兜底
    check('★ 没有接口级失败（/pls/** 全部 <400）', realErrors.length === 0, realErrors.slice(0, 3).join(' | '))
    check('页面无 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' | '))
  } finally {
    // 收尾：确保计划回到"执行中"，别把下一次回归的前置搞坏
    await fetch(`${PROXY}/pls/plans/${pick.id}/execute`, { method: 'POST' }).catch(() => {})
    await context.close().catch(() => {})
    await closeBrowser().catch(() => {})
  }

  console.log('\n' + '='.repeat(78))
  console.log(`本次操作的计划：${pick.title}（id=${pick.id}）`)
  console.log(`截图：${SHOT_DIR}`)
  console.log(`结果：通过 ${passed} 项 / 失败 ${failed} 项`)
  if (failed > 0) {
    console.log('\n失败项：')
    failures.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
    process.exit(1)
  }
  console.log('全部通过 ✅')
  console.log('='.repeat(78))
}

main().catch((e) => {
  console.error('\n脚本异常终止：', e)
  process.exit(1)
})
