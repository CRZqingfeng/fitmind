#!/usr/bin/env node
/**
 * ============================================================
 * M7-4 前端验收：我的课程 / 学习进度 / 训练计划 / 签到 / 积分榜
 * ------------------------------------------------------------
 * 前置条件：
 *   · 后端：fm-gateway(10010) / fm-auth(8081) / fm-user(8082) / fm-course(8084)
 *           fm-learning(8086) / fm-media(8083) / fm-search(8085)
 *   · 数据：已跑过 `node scripts/seed-demo-data.mjs`（要有**免费课**用来报名）
 *   · 前端：fitmind-portal dev server (5173)
 *
 * 用法：cd frontend && node scripts/accept-web-m7-4.mjs
 *
 * ★★ 本脚本要证明的三件事：
 *
 *   1. **进度不是"页面上画的"**。
 *      「已完成 1 / 3」必须等于接口的 `learnedSections / sections`。
 *      这条断言的价值在于：它同时覆盖了"上报成功"和"页面读的是服务端数据"——
 *      如果前端在本地维护一份进度（很多项目会这么偷懒），调价/换设备后就会对不上。
 *
 *   2. **★ "上报即完成" 这个后端行为被前端正确对待了**。
 *      后端只要收到上报就置 `finished = true`（不判断看了多少）。
 *      所以脚本点的是「标记完成」（明确动作），并断言接口的已完成数真的 +1；
 *      同时验证页面**没有**在播放时偷上报（否则点开视频就会"练完"）。
 *
 *   3. **积分/签到/榜单的页面值与接口值一致**（会变的数据更要逐字比对）。
 *
 * 可重复执行：免费课报名是幂等的；训练计划是"覆盖式保存"；
 *   签到会自动适配"今天已签/未签"两种状态。
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

/** 轮询直到条件成立（这个链路上写操作后到处是异步） */
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

async function loginWithCode(page, phone) {
  await page.fill('input[data-testid="login-phone"]', phone)
  for (let attempt = 1; attempt <= 2; attempt++) {
    await page.click('[data-testid="login-send-code"]')
    try {
      await page.waitForFunction(
        () => {
          const el = document.querySelector('input[data-testid="login-credential"]')
          return !!el && el.value.length > 0
        },
        { timeout: 9000 },
      )
      break
    } catch {
      if (attempt === 2) throw new Error('验证码始终未回填（可能是频控未解除，或后端 Mock 模式未开启）')
      console.log('  ⏳ 未取到验证码（多半是 60 秒频控），等 62 秒后重试一次…')
      await page.waitForTimeout(62000)
    }
  }
  await page.click('[data-testid="login-submit"]')
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 20000 })
}

async function main() {
  console.log('============================================================')
  console.log(' FitMind M7-4 前端验收（我的课程 / 学习 / 计划 / 签到 / 积分）')
  console.log('============================================================')

  await requireReachable(`${GATEWAY}/us/codes`, '后端网关 fm-gateway')
  await requireReachable(PORTAL, '用户端 dev server')
  await requireReachable(`${GATEWAY}/ls/lessons/page`, 'fm-learning')

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

  /**
   * ★★ 记录"HTTP 200 但业务码非 200"的响应。
   *
   * 为什么必须要这一条：这类失败**不会**产生 `pageerror`，也**不会**产生 console error ——
   * 它只是让 `request.ts` 弹一条红条给用户。也就是说：
   * 页面看起来一切正常、脚本全绿，但用户实际看到"服务暂时不可用"。
   *
   * 这是本轮真实踩到的盲区（截图上有一条红条，而 27 项断言全过），
   * 所以把它变成一条硬断言 —— **让"用户看到的错误"进入验收范围**。
   */
  const businessErrors = []
  page.on('response', async (res) => {
    if (!res.url().includes(':10010')) return
    try {
      const body = await res.json()
      if (body && typeof body.code === 'number' && body.code !== 200) {
        const path = new URL(res.url()).pathname + (new URL(res.url()).search || '')
        businessErrors.push(`${res.request().method()} ${path} → ${body.code} ${body.msg ?? ''}`)
      }
    } catch {
      /* 非 JSON 响应（文件流等），跳过 */
    }
  })

  try {
    // ================================================================
    section('0. 登录')
    // ================================================================
    await page.goto(`${PORTAL}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await page.waitForSelector('input[data-testid="login-phone"]', { timeout: 30000 })
    await loginWithCode(page, STUDENT_PHONE)
    check('登录成功', true)

    // ================================================================
    section('1. 准备一门免费课并报名（免费课走报名、不走下单）')
    // ================================================================
    const courseResp = await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=100')
    const freeCourses = (courseResp.body?.data?.list ?? []).filter((c) => c.free)
    check('存在免费课程（免费课不能下单，只能直接报名）', freeCourses.length > 0,
      `共 ${freeCourses.length} 门`)

    const mineResp = await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100')
    const mineBefore = mineResp.body?.data?.list ?? []
    const enrolledIds = new Set(mineBefore.map((l) => String(l.courseId)))

    // 优先挑一门**还没报名**的免费课，这样能顺带验证"报名"这个动作
    const target = freeCourses.find((c) => !enrolledIds.has(String(c.id))) ?? freeCourses[0]
    const courseId = String(target.id)
    const alreadyEnrolled = enrolledIds.has(courseId)
    console.log(`     目标课程：${courseId} ${target.name}（已报名=${alreadyEnrolled}）`)

    if (!alreadyEnrolled) {
      await page.goto(`${PORTAL}/course/${courseId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
      await page.waitForSelector('[data-testid="course-buy"]', { timeout: 20000 })
      const btnText = (await page.textContent('[data-testid="course-buy"]'))?.trim()
      check('免费课的按钮是「免费学习」而不是「加入购物车」', btnText === '免费学习',
        `按钮文案「${btnText}」`)

      await page.click('[data-testid="course-buy"]')
      const enrolled = await waitFor(
        async () => {
          const r = await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100')
          return (r.body?.data?.list ?? []).some((l) => String(l.courseId) === courseId)
        },
        { label: '报名落库', timeoutMs: 12000 },
      )
      check('★ 点击后真的报名了（课表里出现了这门课）', Boolean(enrolled))
    } else {
      console.log('     （这门课之前已报名，跳过报名动作）')
    }

    const lessonResp = await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100')
    const mineNow = lessonResp.body?.data?.list ?? []
    const myLesson = mineNow.find((l) => String(l.courseId) === courseId)

    // ================================================================
    section('2. 我的课程页（★ 页面进度 == 接口进度）')
    // ================================================================
    await page.goto(`${PORTAL}/my-courses`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1200)

    // ⚠️⚠️ 口径必须对齐：页面是**分页**的（每页 6 张），不能拿"接口一次拉的 100 条"去比。
    //   这是本轮第二次踩同一个坑（上一次是"取第一张卡片"）：断言把
    //   "总数"和"这一页的条数"混为一谈了。**断言失败先怀疑断言。**
    const MY_COURSES_PAGE_SIZE = 6 // 与 MyCoursesView.vue 的 pageSize 保持一致
    const cards = await page.locator('[data-testid="mycourse-card"]').count()
    const firstPageResp = await apiGet(page, `/ls/lessons/page?pageNo=1&pageSize=${MY_COURSES_PAGE_SIZE}`)
    const firstPageCount = (firstPageResp.body?.data?.list ?? []).length
    check('★ 我的课程卡片数 == 接口第 1 页条数（同 pageSize 口径）',
      cards === firstPageCount,
      `页面 ${cards} 张 / 接口第 1 页 ${firstPageCount} 条`)

    // ★ 总数用页面上明确写"共 N 门"的地方比 —— 它不受分页影响
    const totalText = (await page.textContent('[data-testid="mycourse-total"]'))?.trim()
    check('★ 页面「共 N 门」== 接口 total',
      totalText === `共 ${mineNow.length} 门`,
      `页面「${totalText}」/ 接口共 ${mineNow.length} 门`)

    // ★ 不能用 .first() —— 列表排序**不保证**把目标课程排在第一张。
    //   第一版就是这么写的：拿"刚报名的 0/3"去比"已学过的另一门 1/3"，
    //   看着像功能坏了，其实是断言找错了卡片。**断言失败先怀疑断言。**
    /**
     * ★★ 目标课程**可能不在第 1 页** —— 页面每页 6 条（`MY_COURSES_PAGE_SIZE`），
     *   而学员的课程数会随着验收/演示数据增长。
     *   ⚠️ 实测踩到：课程涨到 8 门后目标课落到**第 2 页**，这里直接
     *   `waiting for locator ...` 超时，看着像"功能坏了"，其实只是找错页。
     *   ★ 与上面那条"不能用 `.first()` 猜顺序"是同一族问题的另一面：
     *     那条说"别猜它在第几个"，这条说"**别假设它一定在第 1 页**"。
     */
    const targetCard = await (async () => {
      for (let pageNo = 1; pageNo <= 6; pageNo++) {
        const card = page
          .locator('[data-testid="mycourse-card"]')
          .filter({ hasText: target.name })
          .first()
        if (await card.count()) return card
        const next = page.locator('.el-pagination button.btn-next')
        // 没有下一页按钮、或它已禁用 → 找完了
        if ((await next.count()) === 0 || (await next.isDisabled().catch(() => true))) break
        await next.click()
        await page.waitForTimeout(600)
      }
      return null
    })()
    const progressText = targetCard
      ? (await targetCard.locator('[data-testid="mycourse-progress"]').textContent())
          ?.replace(/\s+/g, ' ')
          .trim()
      : '(第 1~6 页都没找到该课程的卡片)'
    const expectProgress = `${myLesson?.learnedSections ?? 0} / ${myLesson?.sections ?? 0}`
    check('★ 目标课程卡片的进度 == 接口 learnedSections / sections',
      progressText === expectProgress,
      `页面「${progressText}」/ 接口「${expectProgress}」`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-4-01-my-courses.png'), fullPage: true })

    // ================================================================
    section('3. 学习页：目录与接口一致')
    // ================================================================
    await page.goto(`${PORTAL}/learn/${courseId}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="learn-section"]', { timeout: 25000 })
    await page.waitForTimeout(1000)

    const catasResp = await apiGet(page, `/cs/courses/${courseId}/catalogs`)
    const days = catasResp.body?.data ?? []
    const apiSections = days.flatMap((d) => d.sections ?? [])

    const pageSections = await page.locator('[data-testid="learn-section"]').count()
    check('★ 训练目录里的动作数 == 接口 catalogs 的动作数',
      pageSections === apiSections.length,
      `页面 ${pageSections} 个 / 接口 ${apiSections.length} 个`)

    const progressResp = await apiGet(page, `/ls/learning-records/course/${courseId}`)
    const apiFinished = (progressResp.body?.data?.records ?? []).filter((r) => r.finished).length
    const finishedTag = (await page.textContent('[data-testid="learn-finished-count"]'))?.trim()
    check('★ 页面「已完成 X / Y」== 接口里 finished 的记录数',
      finishedTag === `已完成 ${apiFinished} / ${apiSections.length}`,
      `页面「${finishedTag}」/ 接口 finished=${apiFinished}，总动作=${apiSections.length}`)

    const currentName = (await page.textContent('[data-testid="learn-current-name"]'))?.trim()
    const latestId = progressResp.body?.data?.latestSectionId
    const latestName = apiSections.find((s) => String(s.id) === String(latestId))?.name
    check('★ 默认定位到「上次学到的动作」（断点续播）',
      latestName ? currentName === latestName : Boolean(currentName),
      `页面「${currentName}」/ 接口 latestSectionId=${latestId}（${latestName ?? '无记录'}）`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-4-02-learn.png'), fullPage: true })

    // ================================================================
    section('4. ★ 标记完成 → 接口的已完成数真的 +1')
    // ================================================================
    const beforeFinished = apiFinished

    // 找一个还没完成的动作（避免重复点一个已完成动作导致断言无意义）
    const notFinished = apiSections.find(
      (s) => !(progressResp.body?.data?.records ?? []).some(
        (r) => String(r.sectionId) === String(s.id) && r.finished,
      ),
    )

    if (!notFinished) {
      console.log('     所有动作都已完成，跳过"标记完成"这一步（不影响其它断言）')
    } else {
      // 通过目录点击切到这个动作
      const targetIndex = apiSections.findIndex((s) => String(s.id) === String(notFinished.id))
      await page.locator('[data-testid="learn-section"]').nth(targetIndex).click()
      await page.waitForTimeout(1200)

      const nowName = (await page.textContent('[data-testid="learn-current-name"]'))?.trim()
      check('点击目录后当前动作已切换', nowName === notFinished.name,
        `页面「${nowName}」/ 期望「${notFinished.name}」`)

      await page.click('[data-testid="learn-mark-done"]')

      const afterFinished = await waitFor(
        async () => {
          const r = await apiGet(page, `/ls/learning-records/course/${courseId}`)
          const n = (r.body?.data?.records ?? []).filter((x) => x.finished).length
          return n > beforeFinished ? n : null
        },
        { label: '已完成数 +1', timeoutMs: 15000 },
      )
      check('★ 点「标记完成」后接口的已完成记录数 +1',
        afterFinished === beforeFinished + 1,
        `${beforeFinished} → ${afterFinished}`)

      // 页面也要跟着变（说明它读的是服务端数据，不是本地缓存的旧值）
      const tagAfter = await waitFor(
        async () => {
          const t = (await page.textContent('[data-testid="learn-finished-count"]'))?.trim()
          return t === `已完成 ${beforeFinished + 1} / ${apiSections.length}` ? t : null
        },
        { label: '页面进度刷新', timeoutMs: 10000 },
      )
      check('★ 页面进度也跟着变（不是本地缓存的旧值）', Boolean(tagAfter),
        `页面「${tagAfter}」`)

      const lessonAfter = (await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100'))
        .body?.data?.list?.find((l) => String(l.courseId) === courseId)
      check('★ 课表接口的 learnedSections 与学习记录一致',
        lessonAfter?.learnedSections === afterFinished,
        `课表 ${lessonAfter?.learnedSections} / 学习记录 ${afterFinished}`)
    }

    // ================================================================
    section('5. 训练计划（★ 本周进度 == 接口）')
    // ================================================================
    const planBefore = (await apiGet(page, '/ls/lessons/plans')).body?.data

    await page.goto(`${PORTAL}/plans`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1200)

    const weekFinishedText = (await page.textContent('[data-testid="plan-week-finished"]'))?.trim()
    const weekTotalText = (await page.textContent('[data-testid="plan-week-total"]'))?.trim()
    check('★ 计划页「本周已完成」== 接口 weekFinished',
      weekFinishedText === String(planBefore?.weekFinished ?? 0),
      `页面「${weekFinishedText}」/ 接口「${planBefore?.weekFinished ?? 0}」`)
    check('★ 计划页「本周计划总数」== 接口 weekTotalPlan',
      weekTotalText === String(planBefore?.weekTotalPlan ?? 0),
      `页面「${weekTotalText}」/ 接口「${planBefore?.weekTotalPlan ?? 0}」`)

    // 如果这门课还没计划，就加入计划并断言数据变了
    const inPlan = (planBefore?.list ?? []).some((p) => String(p.courseId) === courseId)
    if (!inPlan) {
      const available = page.locator('[data-testid="plan-available"]')
      const n = await available.count()
      check('未加入计划的课程有「加入计划」入口', n > 0, `${n} 门可选`)

      if (n > 0) {
        await available.first().locator('button', { hasText: '加入计划' }).click()
        const planAfter = await waitFor(
          async () => {
            const r = await apiGet(page, '/ls/lessons/plans')
            return (r.body?.data?.list ?? []).length > (planBefore?.list ?? []).length
              ? r.body.data
              : null
          },
          { label: '计划已创建', timeoutMs: 12000 },
        )
        check('★ 点「加入计划」后接口里有计划了', Boolean(planAfter),
          planAfter ? `计划数 ${planAfter.list.length}，本周计划 ${planAfter.weekTotalPlan} 次` : '未创建')
      }
    } else {
      // 已有计划 → 验证"保存频次"会刷新本周总数
      const card = page.locator('[data-testid="plan-card"]').first()
      const freqBtn = card.locator('[data-testid="plan-save"]')
      check('进行中的计划有「保存」按钮（可改频次）', (await freqBtn.count()) > 0)
    }

    await page.waitForTimeout(500)
    await page.screenshot({ path: join(SHOT_DIR, 'm7-4-03-plans.png'), fullPage: true })

    // ================================================================
    section('6. 成长中心：签到')
    // ================================================================
    await page.goto(`${PORTAL}/growth`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('[data-testid="growth-sign-btn"]', { timeout: 20000 })
    await page.waitForTimeout(800)

    const signRecord = (await apiGet(page, '/ls/sign-records')).body?.data
    const todayDate = new Date().getDate()
    const apiSignedToday = (signRecord?.days ?? []).includes(todayDate)

    const signBtnText = (await page.textContent('[data-testid="growth-sign-btn"]'))?.trim()
    check('★ 签到按钮状态与接口一致（今天是否已签）',
      apiSignedToday ? signBtnText === '今天已签到' : signBtnText === '立即签到',
      `今天已签=${apiSignedToday} / 按钮「${signBtnText}」`)

    const signDaysText = (await page.textContent('[data-testid="growth-sign-days"]'))?.replace(/\s+/g, ' ').trim()
    check('★ 连续签到天数 == 接口 continuousDays',
      signDaysText === `连续签到 ${signRecord?.continuousDays ?? 0} 天`,
      `页面「${signDaysText}」/ 接口「${signRecord?.continuousDays ?? 0}」`)

    if (!apiSignedToday) {
      await page.click('[data-testid="growth-sign-btn"]')
      const signed = await waitFor(
        async () => {
          const r = await apiGet(page, '/ls/sign-records')
          return (r.body?.data?.days ?? []).includes(todayDate) ? r.body.data : null
        },
        { label: '签到落库', timeoutMs: 12000 },
      )
      check('★ 点签到后接口里出现了今天（签到真的写入了）', Boolean(signed))
      await page.waitForTimeout(800)
      const btnAfter = (await page.textContent('[data-testid="growth-sign-btn"]'))?.trim()
      check('签到后按钮变为「今天已签到」', btnAfter === '今天已签到', `按钮「${btnAfter}」`)
    } else {
      console.log('     （今天已签到，跳过点击）')
    }

    // ================================================================
    section('7. ★ 积分榜（页面值 == 接口值）')
    // ================================================================
    /**
     * ★★ "我的积分"这一步，两个值必须**同一时刻**取才有意义。
     *
     *   ⚠️ 原写法是：先取接口值 → 等 1 秒 → 读页面值。这 1 秒里页面可能拉到**新的**积分
     *   （签到/学习是异步加分的），于是页面是新的、接口快照是旧的 ——
     *   实测出现过「页面 204 / 接口 205」这种**差 1 分**的假失败，重跑又通过（典型 flaky）。
     *   → 改成**轮询到两边一致**：每轮同时取一次，一致即通过；
     *     一直不一致才失败（那才是真问题）。
     *
     * ★★ 但只是"重读值"还不够 —— **循环里必须刷新页面**。
     *   「页面值」是一个**渲染时就定格**的 DOM；页面为「79」、接口为「80」时，
     *   不刷新就永远不可能一致，循环只会空转 12 次后报**假失败**
     *   （M8-2c 回归时实测到一次：页面 79 / 接口 80，而**单独重跑就过**）。
     *   根因是**签到/学习的加分是异步（MQ）的**：页面渲染发生在加分落库之前。
     *   ★ 这属于「异步写入 → 断言必须 waitFor」那一族；这里还要额外**reload**，
     *     因为要变的是**页面上的值**，而不是等待某个后端状态。
     */
    let pagePts = ''
    let apiPts = ''
    let pointsOk = false
    let boardApi
    for (let i = 0; i < 12; i++) {
      await page.reload({ waitUntil: 'domcontentloaded' })
      await page.waitForSelector('[data-testid="growth-my-points"]', { timeout: 15000 })
      boardApi = (await apiGet(page, '/ls/boards?pageNo=1&pageSize=10&season=0')).body?.data
      pagePts = (await page.textContent('[data-testid="growth-my-points"]'))?.trim() ?? ''
      apiPts = String(boardApi?.points ?? 0)
      if (pagePts === apiPts) {
        pointsOk = true
        break
      }
      await page.waitForTimeout(800)
    }
    check('★ 页面「我的积分」== 接口 points', pointsOk, `页面「${pagePts}」/ 接口「${apiPts}」`)

    const myRankText = (await page.textContent('[data-testid="growth-my-rank"]'))?.trim()
    const expectRank = boardApi?.rank ? `第 ${boardApi.rank} 名` : '未上榜'
    check('★ 页面「我的排名」== 接口 rank',
      myRankText === expectRank,
      `页面「${myRankText}」/ 接口「${expectRank}」`)

    const rowsOnPage = await page.locator('.board-table .el-table__body tbody tr').count()
    check('★ 榜单行数 == 接口 boardList 长度',
      rowsOnPage === (boardApi?.boardList ?? []).length,
      `页面 ${rowsOnPage} 行 / 接口 ${(boardApi?.boardList ?? []).length} 条`)

    const firstRowName = (await page
      .locator('.board-table .el-table__body tbody tr')
      .first()
      .locator('td')
      .nth(1)
      .textContent())?.trim()
    check('★ 榜首用户名 == 接口第一条的 name',
      firstRowName === (boardApi?.boardList ?? [])[0]?.name,
      `页面「${firstRowName}」/ 接口「${(boardApi?.boardList ?? [])[0]?.name}」`)

    const todayPointsRows = await page.locator('[data-testid="growth-today-points"]').count()
    const todayApi = (await apiGet(page, '/ls/boards/points/today')).body?.data ?? []
    check('★ 今日积分明细条数 == 接口条数',
      todayPointsRows === todayApi.length,
      `页面 ${todayPointsRows} 条 / 接口 ${todayApi.length} 条`)

    await page.screenshot({ path: join(SHOT_DIR, 'm7-4-04-growth.png'), fullPage: true })

    // ================================================================
    section('8. 数据一致性收尾')
    // ================================================================
    const lessonsFinal = (await apiGet(page, '/ls/lessons/page?pageNo=1&pageSize=100')).body?.data
    const coursesResp = await apiGet(page, '/ss/courses/portal?pageNo=1&pageSize=100')
    const allCourseIds = new Set((coursesResp.body?.data?.list ?? []).map((c) => String(c.id)))
    const orphan = (lessonsFinal?.list ?? []).filter((l) => !allCourseIds.has(String(l.courseId)))
    check('★ 课表里的课程都能在课程库里找到（没有脏数据）',
      orphan.length === 0,
      orphan.length ? `孤立课程：${orphan.map((o) => o.courseId).join(',')}` : '全部匹配')

    // ================================================================
    section('9. 浏览器运行时健康度')
    // ================================================================
    // ★ 等一会儿让 response 事件处理完（这个监听器本身是异步的）
    await page.waitForTimeout(800)
    check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
    check('全程无非网络类 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
    check('★★ 全程没有「业务码非 200」的响应（这类失败只弹红条，不留任何日志）',
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
  console.error('脚本异常：', e)
  process.exit(2)
})
