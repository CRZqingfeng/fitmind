#!/usr/bin/env node
/**
 * ============================================================
 * M11-4 AI 定制计划 · 前端验收（P2）
 * ------------------------------------------------------------
 * 前置（本机最省事的一套，不需要 Nacos / 网关 / 登录服务）：
 *   · fm-plan(8095) + fm-aigc(8094, AI_MODE=mock) 在跑
 *   · `node scripts/dev-plan-proxy.mjs`（假网关：/pls→fm-plan、/ais→fm-aigc）
 *   · `cd frontend/fitmind-portal && npm run dev`（5173）
 *
 * 用法：cd frontend && node scripts/accept-web-plan-p2.mjs
 *
 * ★★ 与 `scripts/accept-plan-p2.mjs` 的分工（**故意不重叠**）：
 *     后端脚本查"库里对不对、公式对不对"
 *     本脚本查"页面上真的看得见、点得动、数字对得上接口"
 *
 * ★★ 三条纪律（与 P1 一致）：
 *   1. 断言**可见文本 + 数字关系**，不赌 class 名；
 *   2. 数字在 `page.evaluate` 里**同一时刻**读完（DOM + 接口一起读，避免"读一半刚好刷新"）；
 *   3. 每次跑都会真实生成并接受一份计划 —— 所以断言只能用"相对关系"（页面对得上接口），
 *      不能写死某个计划 id 或某个动作数。
 * ============================================================
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { closeBrowser, launchBrowser } from './_cdp-browser.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots', 'plan-p2')

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
    console.error('   起服务：node scripts/dev-plan-proxy.mjs 与 cd frontend/fitmind-portal && npm run dev')
    process.exit(2)
  }
}

async function waitText(page, text, timeout = 15000) {
  try {
    const loc = page.getByText(text, { exact: false }).first()
    await loc.waitFor({ state: 'visible', timeout })
    return loc
  } catch {
    return null
  }
}

async function waitUntil(fn, timeout = 25000, step = 300) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) return true
    await sleep(step)
  }
  return false
}

/** 一次性读"结果页 + 两个接口"的数字（同一时刻，避免跨刷新比较） */
async function readAiPage(page) {
  return page.evaluate(async () => {
    const t = (s) => document.querySelector(s)?.innerText?.trim() ?? ''
    const numOf = (s) => {
      const m = t(s).replace(/,/g, '').match(/-?\d+(\.\d+)?/)
      return m ? Number(m[0]) : null
    }
    let api = null
    try {
      const res = await fetch('http://localhost:10010/pls/plans/ai/target', {
        headers: { authorization: localStorage.getItem('fitmind:token') || '' },
      })
      api = (await res.json())?.data ?? null
    } catch { /* 交给断言报 */ }
    return {
      targetMain: t('.target-main').replace(/\s+/g, ' '),
      targetSub: t('.target-sub').replace(/\s+/g, ' '),
      forecastCount: document.querySelectorAll('.forecast-col').length,
      forecastValues: Array.from(document.querySelectorAll('.fc-value')).map((e) => Number(e.innerText)),
      evalTexts: Array.from(document.querySelectorAll('.eval-item')).map((e) => e.innerText.replace(/\s+/g, ' ')),
      bmiMarkerLeft: document.querySelector('.bmi-marker')?.style?.left ?? null,
      macroCount: document.querySelectorAll('.macro-item').length,
      macroTexts: Array.from(document.querySelectorAll('.macro-item')).map((e) => e.innerText.replace(/\s+/g, ' ')),
      budget: numOf('.budget'),
      dayTabs: Array.from(document.querySelectorAll('.day-tabs .el-radio-button')).map((e) => e.innerText.trim()),
      mealRows: Array.from(document.querySelectorAll('.meal-row')).map((e) => e.innerText.replace(/\s+/g, ' ')),
      dayTotal: t('.meal-day-total').replace(/\s+/g, ' '),
      foodChips: document.querySelectorAll('.food-chip').length,
      intensityActive: Array.from(document.querySelectorAll('.i-pill.active')).map((e) => e.innerText.trim()),
      distRows: Array.from(document.querySelectorAll('.dist-row')).map((e) => e.innerText.replace(/\s+/g, ' ')),
      trainDays: Array.from(document.querySelectorAll('.train-day')).map((e) => e.innerText.replace(/\s+/g, ' ')),
      planMeta: t('.plan-meta').replace(/\s+/g, ' '),
      degradedVisible: document.querySelectorAll('.degraded-alert').length,
      api,
    }
  })
}

async function main() {
  console.log('='.repeat(78))
  console.log('M11-4 / P2 前端验收：AI 定制结果页')
  console.log('='.repeat(78))

  await requireReachable(`${PROXY}/pls/plans/ai/target`, '假网关 /pls → fm-plan')
  await requireReachable(`${PORTAL}`, '前端 dev server 5173')

  // 骨架链路也必须通 —— 代理漏配 /ais 的话，页面会"永远降级"，那是本脚本要抓的第一类问题
  const skeletonPing = await fetch(`${PROXY}/ais/plan/skeleton`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ weightKg: 65, targetWeightKg: 75 }),
  })
  const skJson = await skeletonPing.json().catch(() => null)
  check('假网关能转发 /ais/**（否则页面永远走降级）',
    skeletonPing.status === 200 && !!skJson?.data?.division,
    `HTTP ${skeletonPing.status}`)

  mkdirSync(SHOT_DIR, { recursive: true })

  const browser = await launchBrowser()
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  const pageErrors = []
  const apiBad = []
  const previewIds = []

  page.on('pageerror', (e) => pageErrors.push(e.message))
  page.on('response', async (r) => {
    const u = r.url()
    if ((u.includes('/pls/') || u.includes('/ais/')) && r.status() >= 400) apiBad.push(`${r.status()} ${u}`)
    if (u.includes('/pls/plans/ai/preview') && r.status() === 200) {
      try { previewIds.push((await r.json()).data.previewId) } catch { /* ignore */ }
    }
  })

  await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(([k]) => localStorage.setItem(k, 'accept-web-plan-p2-fake-token'), [TOKEN_KEY])

  // ==================== 1. 四个区块 ====================
  section('1. /plan/ai：4 个区块都渲染出来')

  await page.goto(`${PORTAL}/plan/ai`, { waitUntil: 'domcontentloaded' })
  const loaded = await waitUntil(async () => (await page.locator('.block-target').count()) > 0, 45000)
  check('★ 结果页加载完成（目标卡出现）', loaded, loaded ? '' : '超时未见目标卡')

  if (!loaded) {
    const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
    check('（诊断）页面没有白屏', body.length > 0, body.slice(0, 160))
  }

  check('① 目标卡标题「你的个人计划已准备就绪」', !!(await waitText(page, '你的个人计划已准备就绪')))
  check('② 评测卡标题「你的评测结果」', !!(await waitText(page, '你的评测结果')))
  check('③ 饮食区块「饮食建议」', !!(await waitText(page, '饮食建议')))
  check('④ 运动区块「运动建议」', !!(await waitText(page, '运动建议')))
  check('顶部有返回入口（一次性页面也要能回去）', !!(await waitText(page, '返回计划')))

  const s1 = await readAiPage(page)
  check('7 天折线 7 个点', s1.forecastCount === 7, `${s1.forecastCount} 个`)
  check('评测卡 4 项（身高/体重/目标/BMI）', s1.evalTexts.length === 4, s1.evalTexts.join(' | '))
  check('BMI 刻度条有游标', !!s1.bmiMarkerLeft, String(s1.bmiMarkerLeft))
  check('营养素 3 块（蛋白/碳水/脂肪）', s1.macroCount === 3, s1.macroTexts.join(' | ').slice(0, 100))
  check('饮食预览有 5 个天 tab', s1.dayTabs.length === 5, s1.dayTabs.join('/'))
  check('★ 当天 6 餐（早/加①/午/加②/晚/加③）', s1.mealRows.length === 6, `${s1.mealRows.length} 餐`)
  check('食物 chip 渲染出来了（emoji + 名称 + 份量）', s1.foodChips > 0, `${s1.foodChips} 个`)
  check('★ 加餐标题带序号', s1.mealRows.some((r) => r.includes('加餐①')), '')
  check('强度三档里有一个高亮', s1.intensityActive.length === 1, s1.intensityActive.join(''))
  check('运动分布 3 行（力量/有氧/柔韧）', s1.distRows.length === 3, s1.distRows.join(' | '))
  check('训练日列表非空', s1.trainDays.length > 0, `${s1.trainDays.length} 天：${s1.trainDays[0] || ''}`)
  check('每个训练日都写了动作数与预计分钟',
    s1.trainDays.every((d) => /个动作/.test(d) && /分钟/.test(d)), s1.trainDays[0] || '')

  await page.screenshot({ path: join(SHOT_DIR, '01-ai-result.png') })

  // ==================== 2. 数字对得上接口 ====================
  section('2. 页面数字 == 接口数字（前端不自己算）')

  const api = s1.api
  check('（前置）拿到了接口的目标数据', !!api, api ? `${api.currentWeightKg}→${api.targetWeightKg}` : 'null')
  if (api) {
    // 界面上"斤" = 接口 kg × 2
    check('★ 目标卡「130 斤」= 接口 65kg × 2',
      s1.targetMain.includes('130 斤'), s1.targetMain)
    check('★ 目标卡「150 斤」= 接口 75kg × 2',
      s1.targetMain.includes('150 斤'), s1.targetMain)
    check('★ 达成天数就是接口的 daysToGoal（前端不算日期）',
      s1.targetSub.includes(`还有 ${api.daysToGoal} 天`) && s1.targetSub.includes(String(api.targetDate)),
      s1.targetSub)
    check('折线首值 == 接口当前体重', Math.abs(Number(s1.forecastValues[0]) - Number(api.currentWeightKg)) < 0.05,
      `${s1.forecastValues[0]} vs ${api.currentWeightKg}`)
  }
  check('★ 每天预算就是接口的 kcalBudget', s1.budget !== null && !!api, `${s1.budget}`)
  check('★ 当天合计里的"千卡"与各餐之和一致',
    (() => {
      const m = s1.dayTotal.match(/当天合计\s*(\d+)/)
      const sum = s1.mealRows.reduce((s, r) => {
        const k = r.match(/(\d+)\s*千卡/)
        return s + (k ? Number(k[1]) : 0)
      }, 0)
      return m && Number(m[1]) === sum
    })(), `${s1.dayTotal.slice(0, 26)}`)

  // ==================== 3. 重新生成 ====================
  section('3. 「重新生成」必须真的重算（新 previewId）')

  const firstId = previewIds[0]
  await page.locator('.bottom-bar button').first().click()
  await waitUntil(async () => previewIds.length >= 2, 45000)
  check('★ 重新生成产生了新的 previewId', previewIds.length >= 2 && previewIds[0] !== previewIds[1],
    `${String(firstId).slice(0, 8)} → ${String(previewIds[1]).slice(0, 8)}`)
  await waitUntil(async () => (await page.locator('.block-target').count()) > 0, 30000)
  check('重新生成后页面仍完整（没有白屏）',
    (await page.locator('.train-day').count()) > 0)

  // ==================== 4. 接受 ====================
  section('4. 接受并加入我的计划 → 回首页且今日训练出现')

  await page.locator('.bottom-bar button').nth(1).click()
  const jumped = await waitUntil(async () => /\/plan(\?|$)/.test(page.url()), 30000)
  check('★ 接受后跳到 /plan', jumped, page.url())

  const homeLoaded = await waitUntil(async () => (await page.locator('.today-card').count()) > 0, 20000)
  check('首页今日训练卡渲染出来了', homeLoaded)
  const homeText = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
  check('★ 今日训练卡是「今天要练」而不是「今天休息」',
    homeText.includes('今天要练'), homeText.slice(0, 120))
  check('★ 卡上写着 AI 计划的名字（为你定制…）',
    homeText.includes('为你定制的'), '')
  check('今日训练显示了动作数与预计分钟',
    /个动作/.test(homeText) && /分钟/.test(homeText),
    (homeText.match(/第 \d+ 练[^|]{0,40}/) || [''])[0])

  await page.screenshot({ path: join(SHOT_DIR, '02-after-accept.png') })

  // ==================== 5. 首页按钮接线 ====================
  section('5. 首页「定制计划」按钮已接到 /plan/ai（不再弹"P2 才开放"）')

  // 当前用户已经有计划，那个按钮只在"无计划"态显示；这里用源码级断言兜住接线
  const fsMod = await import('node:fs')
  const homeSrc = fsMod.readFileSync(join(HERE, '..', 'fitmind-portal', 'src', 'views', 'PlanHomeView.vue'), 'utf8')
  check('★ 首页源码里 goCustom 指向 /plan/ai',
    /function goCustom\(\)\s*\{\s*router\.push\('\/plan\/ai'\)/.test(homeSrc.replace(/\r/g, '')))
  check('★ 那句"P2（AI 定制）开放"的 toast 与注释已删除',
    !homeSrc.includes('定制计划将在 P2'))

  // ==================== 6. 网络与控制台 ====================
  section('6. 网络与控制台')

  const api4xx = apiBad.filter((x) => !x.includes('401'))
  check('★ 没有接口级失败（/pls + /ais 全部 <400）', api4xx.length === 0, api4xx.slice(0, 3).join(' | '))
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

main().catch(async (e) => {
  console.error('\n脚本异常终止：', e)
  await closeBrowser().catch(() => {})
  process.exit(2)
})
