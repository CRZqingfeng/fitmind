/**
 * M7-10 端到端验收：动作考核（学员上传提交 → 教练评分 → 学员看到结果）
 *
 * 运行：cd frontend && npm run accept:m7-10
 *
 * 前置：后端 15 服务 + dev server 5173/5174。
 *
 * 本脚本验证四类事：
 *  ① **上传三步真的走通了**（申请签名 → 直传对象存储 → 确认），
 *     而不是只看"页面上有个上传按钮"。
 *  ② ★★ **视频地址是"现取"的**：VO 里的 `videoUrl` 一直是 null
 *     （签名地址不存库），页面必须用 `mediaId` 现取 play-url。
 *     所以这里断言的是"`<video>` 有 src"，而不是"接口返回了 videoUrl"。
 *  ③ ★ **评分链路**：教练打分 → 学员端看到的是**同一份**分数与评语原文。
 *  ④ ★ **权限边界**：学员不能调管理端接口（这条在 API 层也应有）。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { chromium } from 'playwright'
import { launchBrowser } from './_cdp-browser.mjs'

const PORTAL = 'http://localhost:5173'
const ADMIN = 'http://localhost:5174'
const GATEWAY = 'http://localhost:10010'
const SHOT_DIR = 'D:/develop/develop/code/fitmind/docs/screenshots'

const STUDENT_PHONE = '13800010001'
const ADMIN_PHONE = '13800010003'
const SEED_PASSWORD = '123456'
const PORTAL_TOKEN_KEY = 'fitmind:token'
const ADMIN_TOKEN_KEY = 'fitmind:admin:token'

let pass = 0
let fail = 0
const failures = []

function check(label, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`  ✅ ${label}${detail ? `（${detail}）` : ''}`)
  } else {
    fail++
    failures.push(label)
    console.log(`  ❌ ${label}${detail ? `（${detail}）` : ''}`)
  }
}

function section(t) {
  console.log('')
  console.log('='.repeat(74))
  console.log(`  ${t}`)
  console.log('='.repeat(74))
}

const flat = (s) => (s ?? '').replace(/\s+/g, ' ').trim()

/** 页面里直接打网关（带该端 token） */
async function api(page, method, path, body, tokenKey, retries = 2) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await page.evaluate(
        async ([m, p, b, key]) => {
          const token = localStorage.getItem(key)
          const headers = {}
          if (b !== undefined && b !== null) headers['Content-Type'] = 'application/json'
          if (token) headers.Authorization = token
          const init = { method: m, headers }
          if (b !== undefined && b !== null) init.body = JSON.stringify(b)
          const res = await fetch(`http://localhost:10010${p}`, init)
          const text = await res.text()
          let json = null
          try {
            json = JSON.parse(text)
          } catch {
            json = null
          }
          return { status: res.status, body: json, raw: text.slice(0, 160) }
        },
        [method, path, body ?? null, tokenKey],
      )
    } catch (e) {
      const msg = String(e?.message ?? e)
      if (attempt < retries && /Execution context was destroyed|Target closed/i.test(msg)) {
        await page.waitForLoadState('domcontentloaded').catch(() => undefined)
        await page.waitForTimeout(500)
        continue
      }
      throw e
    }
  }
}

const portalApi = (page, m, p, b) => api(page, m, p, b, PORTAL_TOKEN_KEY)
const adminApi = (page, m, p, b) => api(page, m, p, b, ADMIN_TOKEN_KEY)

/** 密码登录并把 token 注入 localStorage（★ 注入后必须等页面稳定，见 M7-9 的教训） */
async function loginByApi(page, base, path, phone, tokenKey) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 1, cellPhone: phone, password: SEED_PASSWORD, rememberMe: true }),
  })
  const body = await res.json()
  if (!body?.data) throw new Error(`登录失败：code=${body?.code} msg=${body?.msg}`)
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await page.evaluate(([k, t]) => localStorage.setItem(k, t), [tokenKey, body.data])
  await page.waitForLoadState('domcontentloaded').catch(() => undefined)
  await page.waitForTimeout(800)
  return body.data
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })
  const browser = await launchBrowser()
  const admin = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
  const portal = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()

  const pageErrors = []
  const businessErrors = []
  /**
   * ★★ 业务错误收集开关。
   *
   *   第 6 节会**刻意**用学员 token 去打管理端接口，那必然产生几个 403 ——
   *   如果健康度断言把"我自己打出来的 403"也算成失败，就变成"负向测试越充分、
   *   健康度越红"，最后只能把这条断言删掉，等于没测。
   *   ★ 断言口径要排除"我自己的行为"（与 M7-8 同一条教训）。
   */
  let collectErrors = true
  for (const [pg, tag] of [[admin, 'admin'], [portal, 'portal']]) {
    pg.on('pageerror', (e) => pageErrors.push(`[${tag}] ${e.message}`))
    pg.on('response', async (res) => {
      if (!collectErrors || !res.url().includes('localhost:10010')) return
      try {
        const json = await res.json()
        if (json && typeof json.code === 'number' && json.code !== 200) {
          businessErrors.push(`[${tag}] ${res.request().method()} ${res.url().split('10010')[1]} → ${json.code}`)
        }
      } catch {
        /* 非 JSON */
      }
    })
  }

  // ================================================================
  section('1. 登录')
  // ================================================================
  await loginByApi(portal, PORTAL, '/as/accounts/login', STUDENT_PHONE, PORTAL_TOKEN_KEY)
  check('学员登录成功', true)
  await loginByApi(admin, ADMIN, '/as/accounts/admin/login', ADMIN_PHONE, ADMIN_TOKEN_KEY)
  check('管理员登录成功', true)

  // ================================================================
  section('2. 学员端：上传 → 提交')
  // ================================================================
  await portal.goto(`${PORTAL}/action-assess`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await portal.waitForSelector('[data-testid="action-total"]', { timeout: 30000 })
  const beforeApi = (await portalApi(portal, 'GET', '/es/action-assessments/page?pageNo=1&pageSize=20'))
    .body?.data
  const beforeExpect = `共 ${Number(beforeApi?.total ?? 0)} 条`
  /**
   * ★ 轮询等页面跟上，而不是 `goto` 完立刻读一次。
   *
   *   页面是**先渲染、后拿数据**的：`waitForSelector` 只保证元素在、
   *   不保证它的文本已经是最新值 —— 立刻读会读到初始的「共 0 条」，
   *   然后与接口的 4 一比就"失败"，而其实是**问得太早**。
   *   （M7-8 在结算页券区踩过同款：`goto` 之后立刻数元素。）
   */
  let beforeTotal = ''
  for (let i = 0; i < 12; i++) {
    beforeTotal = flat(await portal.textContent('[data-testid="action-total"]'))
    if (beforeTotal === beforeExpect) break
    await portal.waitForTimeout(500)
  }
  check('★ 页面「共 N 条」== 接口 total',
    beforeTotal === beforeExpect,
    `页面「${beforeTotal}」/ 接口 ${beforeApi?.total}`)

  // 造一个 ~2KB 的假 mp4：后端不校验内容，只走"签名→直传→确认"三步
  const fakeVideo = join(tmpdir(), `m7-10-smoke-${Date.now()}.mp4`)
  writeFileSync(fakeVideo, Buffer.alloc(2048, 1))

  await portal.click('[data-testid="action-upload-btn"]')
  await portal.waitForSelector('[data-testid="action-upload-dialog"]', { timeout: 10000 })
  // ★ 隐藏的 <input type=file> 不能 click，但可以直接 setInputFiles
  await portal.setInputFiles('[data-testid="action-file-input"]', fakeVideo)
  const picked = flat(await portal.textContent('[data-testid="action-picked"]'))
  check('选中文件后在弹窗里回显文件名', picked.includes('m7-10-smoke'), picked.slice(0, 40))

  await portal.fill('[data-testid="action-remark"]', 'M7-10 验收：侧面机位，第 3 组深蹲')
  await portal.click('[data-testid="action-submit"]')
  // 上传要经过三步（含一次直传），给足时间
  await portal.waitForSelector('[data-testid="action-upload-dialog"]', { state: 'hidden', timeout: 60000 })
  await portal.waitForTimeout(1200)

  const afterApi = (await portalApi(portal, 'GET', '/es/action-assessments/page?pageNo=1&pageSize=20'))
    .body?.data
  check('★★ 提交后接口里多了一条记录（三步上传真的走完了）',
    Number(afterApi?.total ?? 0) === Number(beforeApi?.total ?? 0) + 1,
    `${beforeApi?.total} → ${afterApi?.total}`)

  const newest = (afterApi?.list ?? [])[0]
  check('★ 新记录状态是「待评分」', newest?.status === 1, `status=${newest?.status} id=${newest?.id}`)
  check('★ 新记录带 mediaId（前端靠它现取播放地址）', !!newest?.mediaId, `mediaId=${newest?.mediaId}`)

  await portal.screenshot({ path: join(SHOT_DIR, 'm7-10-01-portal-list.png') })

  // ================================================================
  section('3. 学员端：详情能看到视频（★ 地址是现取的）')
  // ================================================================
  await portal.reload({ waitUntil: 'domcontentloaded' })
  await portal.waitForSelector('[data-testid="action-card"]', { timeout: 30000 })
  await portal.locator('[data-testid="action-card"]').first().click()
  await portal.waitForSelector('[data-testid="action-detail-dialog"]', { timeout: 10000 })
  await portal.waitForSelector('[data-testid="action-video"]', { timeout: 15000 })
  const src = await portal.getAttribute('[data-testid="action-video"]', 'src')
  check('★★ 详情里的 <video> 拿到了 src（用 mediaId 现取的签名地址）',
    !!src && src.length > 20, (src ?? '').slice(0, 52))
  await portal.screenshot({ path: join(SHOT_DIR, 'm7-10-02-portal-detail.png') })
  await portal.keyboard.press('Escape')
  await portal.waitForTimeout(600)

  // ================================================================
  section('4. 管理端：待评分 → 评分')
  // ================================================================
  await admin.goto(`${ADMIN}/action-assess`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await admin.waitForSelector('[data-testid="action-pending-total"]', { timeout: 30000 })
  /**
   * ★★ 「页面值 == 接口值」必须**轮询到一致**，而且**每轮要刷新页面**。
   *
   *   ⚠️ 原写法是"读一次页面、调一次接口、直接比" —— 两个值取自**不同时刻**。
   *   待评分条数会因"学员刚提交"而变化，于是会出现
   *   「页面 3 条 / 接口 4 条」这种**假失败**（M8-2c 回归时实测到一次，单独重跑就过）。
   *   ★ 只重读值不够：页面上的数字是**渲染时定格**的 DOM，不 reload 永远不会变。
   */
  let pendingText = ''
  let pendingApi = null
  let pendingOk = false
  for (let i = 0; i < 10; i++) {
    await admin.reload({ waitUntil: 'domcontentloaded' })
    await admin.waitForSelector('[data-testid="action-pending-total"]', { timeout: 20000 })
    pendingText = flat(await admin.textContent('[data-testid="action-pending-total"]'))
    pendingApi = (await adminApi(admin, 'GET', '/es/admin/action-assessments/pending?pageNo=1&pageSize=20'))
      .body?.data
    if (pendingText === `待评分 ${Number(pendingApi?.total ?? 0)} 条`) {
      pendingOk = true
      break
    }
    await admin.waitForTimeout(800)
  }
  check('★ 页面「待评分 N 条」== 接口 total',
    pendingOk,
    `页面「${pendingText}」/ 接口 ${pendingApi?.total}`)
  check('★★ 管理端列表带上了学员名（教练要知道是谁提交的）',
    (pendingApi?.list ?? []).some((x) => !!x.userName),
    `userName=${(pendingApi?.list ?? [])[0]?.userName}`)

  // 找到刚刚那条（按自述里的标记）
  const targetRow = admin
    .locator('[data-testid="action-table"] tbody tr')
    .filter({ hasText: 'M7-10 验收' })
    .first()
  check('★ 刚提交的那条出现在待评分列表里', (await targetRow.count()) > 0)
  await targetRow.locator('[data-testid="action-review-btn"]').click()
  await admin.waitForSelector('[data-testid="action-review-dialog"]', { timeout: 10000 })
  await admin.waitForSelector('[data-testid="action-review-video"]', { timeout: 15000 })
  const reviewSrc = await admin.getAttribute('[data-testid="action-review-video"]', 'src')
  check('★★ 评分弹窗里的视频也有 src（教练能边看边打分）',
    !!reviewSrc && reviewSrc.length > 20, (reviewSrc ?? '').slice(0, 52))

  // ★ el-input-number 不能用 page.fill（它失焦才回写 v-model）→ click → Ctrl+A → type → Tab
  const scoreInput = admin.locator('[data-testid="action-score-input"] input')
  await scoreInput.click()
  await admin.keyboard.press('Control+A')
  await admin.keyboard.type('72')
  await admin.keyboard.press('Tab')
  await admin.fill('[data-testid="action-comment-input"]', 'M7-10 验收评语：下蹲时重心略靠前，注意脚跟着地')
  await admin.screenshot({ path: join(SHOT_DIR, 'm7-10-03-admin-review.png') })

  await admin.click('[data-testid="action-submit-review"]')
  await admin.waitForSelector('[data-testid="action-review-dialog"]', { state: 'hidden', timeout: 30000 })
  await admin.waitForTimeout(1200)

  const pendingAfter = (await adminApi(admin, 'GET', '/es/admin/action-assessments/pending?pageNo=1&pageSize=20'))
    .body?.data
  check('★★ 评分后从待评分列表消失',
    Number(pendingAfter?.total ?? 0) === Number(pendingApi?.total ?? 0) - 1,
    `${pendingApi?.total} → ${pendingAfter?.total}`)

  // ================================================================
  section('5. 学员端看到结果（★ 与教练给的是同一份）')
  // ================================================================
  const mineApi = (await portalApi(portal, 'GET', `/es/action-assessments/${newest.id}`)).body?.data
  check('★★ 学员端分数 == 教练打的分（72）', mineApi?.score === 72, `score=${mineApi?.score}`)
  check('★★ 学员端评语 == 教练写的原文',
    (mineApi?.comment ?? '').includes('重心略靠前'), (mineApi?.comment ?? '').slice(0, 40))
  check('★ 状态变为「已评分」', mineApi?.status === 2, `status=${mineApi?.status} / ${mineApi?.statusDesc}`)

  await portal.goto(`${PORTAL}/action-assess`, { waitUntil: 'domcontentloaded' })
  await portal.waitForSelector('[data-testid="action-card"]', { timeout: 30000 })
  await portal.locator('[data-testid="action-card"]').first().click()
  await portal.waitForSelector('[data-testid="action-detail-dialog"]', { timeout: 10000 })
  const shownComment = flat(await portal.textContent('[data-testid="action-comment"]'))
  check('★★ 页面上的评语 == 接口原文（前端不做任何改写）',
    shownComment.includes('重心略靠前'), shownComment.slice(0, 40))
  await portal.screenshot({ path: join(SHOT_DIR, 'm7-10-04-portal-reviewed.png') })
  await portal.keyboard.press('Escape')

  // ================================================================
  section('6. 权限边界（★ 学员不能替教练干活）')
  // ★ 接下来这几个 403 是**刻意**打出来的 → 暂停业务错误收集
  collectErrors = false
  // ================================================================
  const r1 = await portalApi(portal, 'GET', '/es/admin/action-assessments/pending?pageNo=1&pageSize=1')
  check('★ 学员调管理端「待评分」→ 403', r1.status === 403, `HTTP ${r1.status}`)
  const r2 = await portalApi(portal, 'PUT', '/es/admin/action-assessments/review', {
    id: newest.id, score: 100,
  })
  check('★★ 学员想自己把分改成 100 → 403', r2.status === 403, `HTTP ${r2.status}`)
  const r3 = await portalApi(portal, 'DELETE', '/ms/medias/999999999')
  check('★ 学员想删媒资 → 403（方法级鉴权：DELETE 要管理员）', r3.status === 403, `HTTP ${r3.status}`)
  const r4 = await portalApi(portal, 'POST', '/ms/medias/upload-signature?filename=x.mp4&size=1')
  check('★★ 但学员**能**申请上传签名（动作考核的前置，方法级放行）',
    r4.status === 200 && r4.body?.code === 200, `HTTP ${r4.status} code=${r4.body?.code}`)
  // ★ 负向探测结束，恢复收集
  collectErrors = true

  // ================================================================
  section('7. 浏览器运行时健康度')
  // ================================================================
  check('全程无 JS 运行时异常（pageerror）', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '))
  check('★ 全程没有「业务码非 200」的响应（这类失败只弹红条，不留日志）',
    businessErrors.length === 0, businessErrors.slice(0, 3).join(' | '))

  await browser.close()

  console.log('')
  console.log('='.repeat(74))
  console.log(`  结果：${pass} 通过 / ${fail} 失败`)
  if (failures.length) console.log('  失败项：' + failures.join('、'))
  console.log('  截图：' + SHOT_DIR)
  console.log('='.repeat(74))
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('验收脚本异常：', e)
  process.exit(1)
})
