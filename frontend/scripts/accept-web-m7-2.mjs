#!/usr/bin/env node
/**
 * ============================================================
 * M7-2 前端验收：首页推荐 / 课程列表与搜索 / 课程详情
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-auth(8081) / fm-user(8082)
 *           fm-course(8084) / fm-search(8085) / fm-media(8083) 已启动
 *   · 数据：已跑过 `node scripts/seed-demo-data.mjs`（否则列表是空的，
 *           断言会退化成 0 == 0 —— 那等于没测）
 *   · 前端：fitmind-portal dev server (5173)
 *
 * 用法：cd frontend && node scripts/accept-web-m7-2.mjs
 *
 * ★ 与 M7-1 一样的取舍：**不接受"看起来渲染出来了"**。
 *   本脚本里真正有价值的断言是「页面显示的数量 == 直接调接口拿到的数量」——
 *   "渲染出卡片了"和"渲染的是全部/正确的卡片"是两件事。
 * ============================================================
 */

import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { chromium } from 'playwright'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = join(HERE, '..', '..', 'docs', 'screenshots')

const PORTAL = 'http://localhost:5173'
const GATEWAY = 'http://localhost:10010'
const STUDENT_PHONE = '13800010001'
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

async function launchBrowser() {
  try {
    return await chromium.launch()
  } catch (err) {
    if (!/Executable doesn't exist/i.test(String(err?.message ?? ''))) throw err
    console.log('  ⚠️ 未找到 Playwright 自带 Chromium，回退使用系统 Edge（channel=msedge）')
    return await chromium.launch({ channel: 'msedge' })
  }
}

async function requireReachable(url, name) {
  try {
    await fetch(url, { method: 'GET', signal: AbortSignal.timeout(8000) })
  } catch {
    console.error(`\n❌ 前置条件不满足：${name} 不可达（${url}）`)
    process.exit(2)
  }
}

/** 在浏览器上下文里直接调网关（复用页面里那份 token，避免两边环境不一致） */
async function apiGet(page, path) {
  return page.evaluate(
    async ({ gateway, key, p }) => {
      const token = localStorage.getItem(key)
      const res = await fetch(gateway + p, { headers: { Authorization: token } })
      return res.json()
    },
    { gateway: GATEWAY, key: TOKEN_KEY, p: path },
  )
}

/**
 * 验证码登录
 *
 * ★ 为什么带重试：后端对同一手机号有 **60 秒发送间隔**限制
 *   （`{"code":400,"msg":"验证码发送过于频繁，请稍后再试"}`）。
 *   连续跑验收脚本、或刚用同一个号手工调过接口时必定撞上 ——
 *   这是**正常的业务规则**，不是功能坏了，所以脚本应该等一轮再试，
 *   而不是直接判失败（否则每次跑之前都要人肉等一分钟，久了就没人愿意跑了）。
 */
async function loginWithCode(page, phone) {
  await page.fill('input[data-testid="login-phone"]', phone)

  const CODE_WAIT_MS = 9000
  const THROTTLE_WAIT_MS = 62000

  for (let attempt = 1; attempt <= 2; attempt++) {
    await page.click('[data-testid="login-send-code"]')
    try {
      await page.waitForFunction(
        () => {
          const el = document.querySelector('input[data-testid="login-credential"]')
          return !!el && el.value.length > 0
        },
        { timeout: CODE_WAIT_MS },
      )
      break
    } catch {
      if (attempt === 2) {
        throw new Error('验证码始终未回填（可能是频控未解除，或后端 Mock 模式未开启）')
      }
      console.log('  ⏳ 未取到验证码（多半是 60 秒频控），等 62 秒后重试一次…')
      await page.waitForTimeout(THROTTLE_WAIT_MS)
    }
  }

  await page.click('[data-testid="login-submit"]')
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 20000 })
}

async function main() {
  console.log('============================================================')
  console.log(' FitMind M7-2 前端验收（首页推荐 / 课程列表与搜索 / 课程详情）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')

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

  try {
    // ================================================================
    section('0. 登录')
    // ================================================================
    await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await page.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(page, STUDENT_PHONE)
    check('登录成功', !page.url().includes('/login'), `URL=${page.url()}`)

    // ================================================================
    section('1. 首页三个推荐区')
    // ================================================================
    await page.goto(`${PORTAL}/`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="home-best"]', { timeout: 20000 })
    check('首页渲染出「猜你喜欢」区块', true)

    // ★ 交叉验证：页面卡片数 == 接口返回条数
    for (const [testid, path, label] of [
      ['home-best', '/ss/recommend/best?size=8', '猜你喜欢'],
      ['home-free', '/ss/recommend/free?size=8', '免费专区'],
      ['home-newest', '/ss/recommend/new?size=8', '最新上架'],
    ]) {
      const cards = await page.locator(`[data-testid="${testid}"] [data-testid="course-card"]`).count()
      const api = await apiGet(page, path)
      const apiLen = (api?.data || []).length
      check(
        `★「${label}」页面卡片数 == 接口返回条数`,
        cards === apiLen && apiLen > 0,
        `页面 ${cards} 张 / 接口 ${apiLen} 条`,
      )
    }

    await page.screenshot({ path: join(SHOT_DIR, 'm7-2-01-home.png'), fullPage: true })

    // ================================================================
    section('2. 课程列表：总数与卡片数一致')
    // ================================================================
    await page.goto(`${PORTAL}/courses`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="course-card"]', { timeout: 20000 })

    const apiPage = await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=12')
    const apiTotal = apiPage?.data?.total ?? -1
    const apiListLen = (apiPage?.data?.list || []).length

    const totalText = (await page.textContent('[data-testid="course-total"]')) ?? ''
    const pageTotalNum = Number((totalText.match(/(\d+)/) || [])[1] ?? -1)
    check('★★ 页面显示的总数 == 接口 total', pageTotalNum === apiTotal, `页面 ${pageTotalNum} / 接口 ${apiTotal}`)

    const listCards = await page.locator('[data-testid="course-card"]').count()
    check('★ 列表页卡片数 == 接口当页条数', listCards === apiListLen, `页面 ${listCards} / 接口 ${apiListLen}`)

    check('课程卡片渲染出价格', (await page.locator('[data-testid="course-price"]').count()) > 0)
    check('课程卡片渲染出分类路径', (await page.locator('[data-testid="course-path"]').count()) > 0)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-2-02-course-list.png'), fullPage: true })

    // ================================================================
    section('3. 关键词搜索 + ★ 高亮安全渲染')
    // ================================================================
    await page.fill('input[data-testid="course-search-input"]', '哑铃')
    await page.click('[data-testid="course-search-btn"]')
    await page.waitForURL(/keyword/, { timeout: 15000 })
    await page.waitForTimeout(600)

    const apiSearch = await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=12&keyword=%E5%93%91%E9%93%83')
    const searchTotal = apiSearch?.data?.total ?? -1
    const searchCards = await page.locator('[data-testid="course-card"]').count()
    check('搜索「哑铃」有结果', searchTotal > 0, `接口 total=${searchTotal}`)
    check('★ 搜索结果卡片数 == 接口条数', searchCards === (apiSearch?.data?.list || []).length,
      `页面 ${searchCards} / 接口 ${(apiSearch?.data?.list || []).length}`)

    // 高亮：接口返回 highlightName 含 <em>，页面应把它渲染成真的 <em> 元素
    const apiHasHighlight = (apiSearch?.data?.list || []).some((c) => c.highlightName?.includes('<em>'))
    check('接口返回了带 <em> 的高亮名', apiHasHighlight)
    const emCount = await page.locator('[data-testid="course-grid"] em').count()
    check('★ 高亮被渲染成 <em> 元素（而不是把标签当纯文本显示）', emCount > 0, `页面 <em> 数量=${emCount}`)

    const gridText = (await page.textContent('[data-testid="course-grid"]')) ?? ''
    check('★ 页面上没有裸露的 <em> 标签文本（说明经过了转义 + 白名单还原）',
      !gridText.includes('<em>'), gridText.includes('<em>') ? '页面上出现了字面量 <em>' : '')

    check('URL 里带上了 keyword（可分享 / 刷新不丢）', page.url().includes('keyword='), page.url())

    await page.screenshot({ path: join(SHOT_DIR, 'm7-2-03-search.png'), fullPage: true })

    // ================================================================
    section('4. 筛选：只看免费（★ 页面内容与接口口径一致）')
    // ================================================================
    await page.goto(`${PORTAL}/courses?free=true`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(800)

    const apiFree = await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=12&free=true')
    const freeTotal = apiFree?.data?.total ?? -1
    const freeCards = await page.locator('[data-testid="course-card"]').count()
    check('筛选「只看免费」有结果', freeTotal > 0, `接口 total=${freeTotal}`)
    check('★ 免费筛选：页面卡片数 == 接口条数', freeCards === (apiFree?.data?.list || []).length,
      `页面 ${freeCards} / 接口 ${(apiFree?.data?.list || []).length}`)

    // 页面上每张卡都应显示「免费」
    const prices = await page.locator('[data-testid="course-price"]').allTextContents()
    check('★ 页面上每张卡的价格都是「免费」', prices.length > 0 && prices.every((t) => t.trim() === '免费'),
      `价格样本：${prices.slice(0, 4).join(' / ')}`)

    // ================================================================
    section('5. 详情页（★ 展示值 == 接口值）')
    // ================================================================
    await page.goto(`${PORTAL}/courses`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="course-card"]', { timeout: 20000 })
    await page.locator('[data-testid="course-card"] [data-testid="course-name"]').first().click()
    await page.waitForURL(/\/course\//, { timeout: 20000 })
    await page.waitForSelector('[data-testid="course-detail-title"]', { timeout: 20000 })

    const courseId = (page.url().match(/\/course\/(\d+)/) || [])[1]
    check('进入了课程详情页', !!courseId, `courseId=${courseId}`)

    const apiDetail = await apiGet(page, `/cs/courses/baseInfo/${courseId}`)
    const detail = apiDetail?.data || {}

    const titleOnPage = ((await page.textContent('[data-testid="course-detail-title"]')) ?? '').trim()
    check('★ 详情页标题 == 接口课程名', titleOnPage === detail.name, `页面「${titleOnPage}」/ 接口「${detail.name}」`)

    const priceOnPage = ((await page.textContent('[data-testid="course-detail-price"]')) ?? '').trim()
    const expectPrice = detail.free === 1 ? '免费' : `¥${(detail.price / 100).toFixed(2)}`
    check('★ 详情页价格 == 接口价格（分→元换算正确）', priceOnPage === expectPrice,
      `页面「${priceOnPage}」/ 期望「${expectPrice}」（接口 price=${detail.price} 分）`)

    // 目录：训练日数
    const apiCatas = await apiGet(page, `/cs/courses/${courseId}/catalogs`)
    const cataDays = (apiCatas?.data || [])
    const dayNodes = await page.locator('[data-testid="course-catalog"] .el-collapse-item').count()
    check('★ 详情页训练日数 == 接口条数', dayNodes === cataDays.length && cataDays.length > 0,
      `页面 ${dayNodes} / 接口 ${cataDays.length}`)

    const apiActionTotal = cataDays.reduce((s, d) => s + (d.sections?.length || 0), 0)

    // 展开全部训练日，断言"表格总行数 == 接口动作总数"
    // ⚠️ 第一版只点了第一个训练日，却用全局选择器 `.el-table__body tbody tr` 数行 ——
    //    数到的是**所有已经渲染进 DOM 的表格**（el-collapse 只是把内容收起，并没有不渲染），
    //    于是"11 行 vs 4 个动作"报错，看着像页面错了，其实是断言口径没对齐。
    //    教训与清算单第 1 条同源：**断言失败先怀疑断言**。
    //    现在两边都是"总数对总数"，不含糊。
    const headers = page.locator('[data-testid="course-catalog"] .el-collapse-item__header')
    const headerCount = await headers.count()
    for (let i = 0; i < headerCount; i++) {
      await headers.nth(i).click()
    }
    await page.waitForTimeout(700)

    const rows = await page.locator('[data-testid="course-catalog"] .el-table__body tbody tr').count()
    check('★ 展开全部训练日后，动作总行数 == 接口动作总数', rows === apiActionTotal && apiActionTotal > 0,
      `页面 ${rows} 行 / 接口 ${apiActionTotal} 个动作`)

    const sectionText = ((await page.textContent('[data-testid="course-detail-sections"]')) ?? '').trim()
    check('★ 页面「训练动作」数 == 目录里动作总数', sectionText === `${apiActionTotal} 个`,
      `页面「${sectionText}」/ 接口合计 ${apiActionTotal}`)

    // 教练
    const apiTeachers = await apiGet(page, `/cs/courses/teachers/${courseId}?see=true`)
    const teacherLen = (apiTeachers?.data || []).length
    if (teacherLen > 0) {
      const teacherText = (await page.textContent('[data-testid="course-detail-teachers"]')) ?? ''
      check('★ 详情页教练名 == 接口返回的教练名',
        teacherText.includes(apiTeachers.data[0].name),
        `页面含「${apiTeachers.data[0].name}」=${teacherText.includes(apiTeachers.data[0].name)}`)
    } else {
      check('接口没有教练数据（跳过教练断言）', true)
    }

    await page.screenshot({ path: join(SHOT_DIR, 'm7-2-04-course-detail.png'), fullPage: true })

    // ================================================================
    section('6. 详情页对"未上架 / 不存在"的处理（用户端不应看到草稿课）')
    // ================================================================
    await page.goto(`${PORTAL}/course/99999999`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1200)
    const notFoundText = (await page.textContent('body')) ?? ''
    check('访问不存在的课程 id 时给出明确提示（而不是白屏或报错页）',
      /不存在|已下架/.test(notFoundText), `页面是否含提示文案=${/不存在|已下架/.test(notFoundText)}`)

    // ================================================================
    section('7. 浏览器运行时健康度')
    // ================================================================
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
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
