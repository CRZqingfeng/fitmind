/**
 * M7-12c 第 3 期验收：admin 内容运营（社区审核 + 公告与通知模板）
 *
 * ============================================================
 * ★★ 这一期最有价值的断言
 * ============================================================
 * 1. **★★ 隐藏一条内容后，学员端真的看不到了** —— 这是"审核"这个功能的**全部意义**。
 *    判据是**双向**的：隐藏前学员端能看到、隐藏后看不到、恢复后又能看到。
 *    只验"隐藏接口返回 200"是没有意义的（那只能证明接口没报错）。
 * 2. **★★ 定时公告"当时不可见"** —— 这是全项目唯一一个"**时间维度**"的断言。
 *    它之所以能立刻验、不用等：公告的"定时"是**读的时候按 `pushTime <= now` 过滤**出来的，
 *    没有任何调度器。填一个未来时间，马上就能证明它"还没到点、确实不出现"。
 *
 * ============================================================
 * ★ 负向的另一半：管理端**仍然查得到**
 * ============================================================
 * "隐藏"不是"删除"：管理端分页接口的注释明写"含被隐藏的"。
 * 所以隐藏之后要**再查一次管理端列表**，确认它还在 ——
 * 否则"隐藏"就变成了"弄丢了"，审核也就没有"可撤销"可言。
 *
 * ============================================================
 * ★ 脚本自觉
 * ============================================================
 * · 自造数据自己清：1 个问题（连带其下回答）+ 1 条笔记 + 1 个模板，末尾逐一删除并自检
 * · ⚠️ **公告没有删除接口** → 只能 SQL 删；本环境 SQL 不可用时会**打印待执行的 SQL**，
 *   不静默留垃圾
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

import { chromium } from 'playwright'
import { launchBrowser } from './_cdp-browser.mjs'

const GW = 'http://localhost:10010'
const PORTAL = 'http://localhost:5173'
const ADMIN = 'http://localhost:5174'
const ADMIN_PHONE = '13800010003'
const STUDENT_PHONE = '13800010001'
const SEED_PASSWORD = '123456'
const ADMIN_TOKEN_KEY = 'fitmind:admin:token'
const PORTAL_TOKEN_KEY = 'fitmind:token'
const MYSQL = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const SHOT_DIR = 'D:/develop/develop/code/fitmind/docs/screenshots'
mkdirSync(SHOT_DIR, { recursive: true })

const STAMP = String(Date.now()).slice(-6)
const Q_TITLE = `M7-12c验收问题${STAMP}`
const N_CONTENT = `M7-12c验收笔记${STAMP}`
const TPL_CODE = `m7-12c-accept-${STAMP}`
const NOTICE_NOW = `M7-12c立即公告${STAMP}`
const NOTICE_FUTURE = `M7-12c定时公告${STAMP}`

let pass = 0
let fail = 0
let skipped = 0
let blocked = 0
const failures = []
const skipNotes = []
const blockedNotes = []

function check(label, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`  ✅ ${label}${detail ? `（${detail}）` : ''}`)
  } else {
    fail++
    failures.push(`${label}${detail ? ` —— ${detail}` : ''}`)
    console.log(`  ❌ ${label}${detail ? `（${detail}）` : ''}`)
  }
}
function checkSql(label, ok, detail = '') {
  console.log(`  📑 ${label}${detail ? `（${detail}）` : ''}`)
  if (!ok) {
    skipped++
    skipNotes.push(`${label}${detail ? ` —— ${detail}` : ''}`)
  }
}
function checkBlocked(label, note) {
  blocked++
  blockedNotes.push(`${label} —— ${note}`)
  console.log(`  🚧 ${label} —— ${note}`)
}
function section(t) {
  console.log('')
  console.log('='.repeat(74))
  console.log(`  ${t}`)
  console.log('='.repeat(74))
}

async function gw(method, path, { token, body } = {}) {
  const headers = {}
  if (body !== undefined && body !== null) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = token
  const res = await fetch(`${GW}${path}`, {
    method,
    headers,
    body: body === undefined || body === null ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    json = null
  }
  return { status: res.status, body: json, raw: text.slice(0, 200) }
}

function sqlCells(query) {
  const r = spawnSync(
    MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  if (r.status !== 0) return []
  return (r.stdout ?? '')
    .split('\n')
    .map((l) => l.trimEnd())
    .filter((l) => l.length > 0)
    .map((l) => l.split('\t'))
}
const SQL_AVAILABLE = sqlCells('SELECT 1').length > 0

async function login(page, path, phone, tokenKey) {
  const res = await gw('POST', path, {
    body: { type: 1, cellPhone: phone, password: SEED_PASSWORD, rememberMe: false },
  })
  const token = res.body?.data
  if (!token) return null
  await page.evaluate(([k, t]) => localStorage.setItem(k, t), [tokenKey, token])
  return token
}

const browser = await launchBrowser()
const admin = await (await browser.newContext({ viewport: { width: 1560, height: 950 } })).newPage()
const student = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()

const pageErrors = []
let collectErrors = true
for (const [pg, tag] of [
  [admin, 'admin'],
  [student, 'portal'],
]) {
  pg.on('pageerror', (e) => pageErrors.push(`[${tag}] ${e.message}`))
  pg.on('response', async (res) => {
    if (!collectErrors || !res.url().includes('localhost:10010')) return
    try {
      const json = await res.json()
      if (json && typeof json.code === 'number' && json.code !== 200) {
        pageErrors.push(`[${tag}] 业务码非 200：${res.request().method()} ${res.url().split('10010')[1]} → ${json.code}`)
      }
    } catch {
      /* 非 JSON */
    }
  })
}

async function waitStable(pg, sel, timeout = 20000) {
  await pg.waitForSelector(sel, { timeout })
  await pg.waitForTimeout(400)
}

// 自造数据的 id（供 catch 兜底清理）
let questionId = null
let noteId = null
let templateId = null
let noticeIds = []

try {
  // ================================================================
  section('1. 登录 + 取一门已上架课程的动作（造数据要用）')
  // ================================================================
  await admin.goto(ADMIN, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => undefined)
  await admin.waitForTimeout(800)
  const adminToken = await login(admin, '/as/accounts/admin/login', ADMIN_PHONE, ADMIN_TOKEN_KEY)
  check('管理员登录成功', !!adminToken)

  await student.goto(PORTAL, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => undefined)
  await student.waitForTimeout(800)
  const studentToken = await login(student, '/as/accounts/login', STUDENT_PHONE, PORTAL_TOKEN_KEY)
  check('学员登录成功', !!studentToken)

  /**
   * ★★ 选课程有讲究：**必须挑一门"有训练目录"的已上架课程**。
   *   提问与写笔记都要求 `chapterId`（训练日）+ `sectionId`（训练动作）非空，
   *   而库里有一批"已上架但**目录是空的**"课程（M3-3 系列验收造的），
   *   直接取列表第一门就会撞上它，报「训练动作id不能为空」（第一次跑就踩了）。
   *
   *   ⚠️ 第二次尝试"遍历名单逐个试"也失败 —— 因为 `GET /courses/page` 的
   *   排序不由前端控制，有目录的那几门（535/538…）**不在第一页**。
   *   → 改成**翻 3 页收全候选 + 优先挑 `sectionNum > 0` 的**
   *   （`sectionNum` 是"训练动作数量"，它 > 0 就等价于"有目录"，不用再去试）。
   */
  const candidates = []
  for (let p = 1; p <= 3; p++) {
    const page = (await gw('GET', `/cs/courses/page?pageNo=${p}&pageSize=20&status=2`, { token: adminToken }))
      .body?.data
    candidates.push(...(page?.list ?? []))
    if ((page?.list ?? []).length < 20) break
  }
  const withSections = candidates.filter((c) => Number(c.sectionNum ?? 0) > 0)

  let demoCourse = null
  let day = null
  let action = null
  for (const c of [...withSections, ...candidates]) {
    if (demoCourse) break
    // ⚠️ 必须带 token —— 漏了会得到 401，`data` 为空，于是**每一门课都"没目录"**，
    //    表现成"挑不到课"而不是"没登录"（这条踩过一次：排查了半天目录接口，
    //    其实是调用方自己没带 Authorization）。
    const res = await gw('GET', `/cs/courses/${c.id}/catalogs`, { token: adminToken })
    const catas = res.body?.data ?? []
    const d = catas.find((x) => (x.sections ?? []).length > 0)
    if (d) {
      demoCourse = c
      day = d
      action = d.sections[0]
    }
  }
  check('取到一门「有训练目录」的已上架课程（提问/写笔记要用它的目录 id）', !!demoCourse && !!action,
    `courseId=${demoCourse?.id} chapterId=${day?.id} sectionId=${action?.id}（候选 ${candidates.length} 门，其中 ${withSections.length} 门有动作）`)

  // ================================================================
  section('2. 社区审核 · 问题：隐藏后**学员端真的看不到**（★★ 本期核心）')
  // ================================================================
  const createQ = await gw('POST', '/ls/questions', {
    token: studentToken,
    body: {
      courseId: demoCourse?.id,
      chapterId: day?.id,
      sectionId: action?.id,
      title: Q_TITLE,
      description: '这是 M7-12c 验收脚本造的问题，用于验证「隐藏后学员端不可见」。',
    },
  })
  check('★★ 学员提问成功（造出一条真实内容）', createQ.body?.code === 200,
    `code=${createQ.body?.code} msg=${createQ.body?.msg ?? ''}`)

  const adminQPage = () => gw('GET', `/ls/admin/questions/page?pageNo=1&pageSize=50&courseId=${demoCourse?.id}`, { token: adminToken })
  const studentQPage = () => gw('GET', `/ls/questions/page?pageNo=1&pageSize=50&courseId=${demoCourse?.id}`, { token: studentToken })

  const adminList1 = (await adminQPage()).body?.data?.list ?? []
  const mine = adminList1.find((q) => q.title === Q_TITLE)
  questionId = mine?.id ?? null
  check('★★ 管理端问题列表能查到它（审核入口可用）', !!questionId, `questionId=${questionId}`)
  check('★ 管理端返回了提问者（未匿名时是真实姓名）', !!mine?.userName, `userName=${mine?.userName}`)

  const studentList1 = (await studentQPage()).body?.data?.list ?? []
  const visibleBefore = studentList1.some((q) => String(q.id) === String(questionId))
  check('★★ 前置：隐藏**之前**，学员端**能看到**它（否则后面的"看不到"是假绿）', visibleBefore,
    `学员端可见 ${studentList1.length} 条`)

  // 页面上真的点一次"隐藏"
  await admin.goto(`${ADMIN}/community-audit`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await waitStable(admin, '[data-testid="audit-question-table"]')
  await admin.screenshot({ path: join(SHOT_DIR, 'm7-12c-01-audit-question.png') })
  check('社区审核页可打开（问题 tab 有表格）', true)

  const hideBtn = admin.locator(`[data-testid="audit-question-toggle-${questionId}"]`)
  if (await hideBtn.count()) {
    collectErrors = false
    await hideBtn.click()
    await admin.waitForSelector('.el-message-box', { timeout: 15000 })
    await admin.waitForTimeout(400)
    await admin.screenshot({ path: join(SHOT_DIR, 'm7-12c-02-hide-confirm.png') })
    await admin.click('.el-message-box__btns .el-button--primary')
    await admin.waitForTimeout(2000)
    collectErrors = true
    check('★ 页面上点击「隐藏」并确认（走的是真实交互）', true)
  } else {
    check('★ 页面上找到「隐藏」按钮', false, `选择器 audit-question-toggle-${questionId} 没找到`)
  }

  const hiddenRow = (await adminQPage()).body?.data?.list?.find((q) => String(q.id) === String(questionId))
  check('★★ 隐藏后 **管理端仍能查到**它，且 hidden=true（"藏了还能找回来"）',
    hiddenRow?.hidden === true, `hidden=${hiddenRow?.hidden}`)

  const studentList2 = (await studentQPage()).body?.data?.list ?? []
  const visibleAfter = studentList2.some((q) => String(q.id) === String(questionId))
  /**
   * ⚠️ ★ 加 `!!questionId` 守卫：第一次跑时 questionId 是 null，
   *   而 `String(q.id) === 'null'` 永远为 false → 这条**本该最有价值的断言**
   *   居然"通过"了 —— 典型的假绿（"我要找的东西根本不存在，所以它当然看不到"）。
   */
  check('★★★ 隐藏后 **学员端看不到**了（审核功能的全部意义所在）', !!questionId && !visibleAfter,
    questionId ? `学员端可见 ${studentList2.length} 条` : '前置失败：questionId 为空，本断言无意义')

  // 恢复 → 学员端又能看到（双向）
  const restoreRes = await gw('PUT', `/ls/admin/questions/${questionId}/hidden/false`, { token: adminToken })
  check('★ 恢复接口调用成功', restoreRes.body?.code === 200, `code=${restoreRes.body?.code}`)
  const studentList3 = (await studentQPage()).body?.data?.list ?? []
  check('★★ 恢复后学员端**又能看到**（证明"隐藏"是可撤销的，不是"弄丢了"）',
    studentList3.some((q) => String(q.id) === String(questionId)), `学员端可见 ${studentList3.length} 条`)

  // ================================================================
  section('3. 社区审核 · 回答：管理端按问题筛选 + 隐藏/恢复')
  // ================================================================
  let replyId = null
  if (questionId) {
    const createR = await gw('POST', '/ls/replies', {
      token: studentToken,
      body: { questionId, answerId: 0, content: `M7-12c 验收回答 ${STAMP}` },
    })
    check('★ 学员对问题发了一条回答', createR.body?.code === 200, `code=${createR.body?.code}`)

    const replyPage = (await gw('GET', `/ls/admin/replies/page?pageNo=1&pageSize=50&questionId=${questionId}`, {
      token: adminToken,
    })).body?.data
    const reply = (replyPage?.list ?? []).find((r) => String(r.content ?? '').includes(STAMP))
    replyId = reply?.id ?? null
    check('★★ 管理端能按问题 id 筛出这条回答（审核需要"从问题追到回答"）', !!replyId, `replyId=${replyId}`)
    check('☆ 它是顶层回答（answerId=0）', !reply?.answerId || String(reply?.answerId) === '0',
      `answerId=${reply?.answerId}`)

    if (replyId) {
      await gw('PUT', `/ls/admin/replies/${replyId}/hidden/true`, { token: adminToken })
      const hidden = (await gw('GET', `/ls/admin/replies/${replyId}`, { token: adminToken })).body?.data
      check('★★ 隐藏回答生效（详情里 hidden=true）', hidden?.hidden === true, `hidden=${hidden?.hidden}`)
      await gw('PUT', `/ls/admin/replies/${replyId}/hidden/false`, { token: adminToken })
      const shown = (await gw('GET', `/ls/admin/replies/${replyId}`, { token: adminToken })).body?.data
      check('★ 恢复后 hidden=false（双向都验）', shown?.hidden === false, `hidden=${shown?.hidden}`)
    }
  }

  // ================================================================
  section('4. 社区审核 · 笔记：折叠/恢复 + 详情多出的两个字段')
  // ================================================================
  const createN = await gw('POST', '/ls/notes', {
    token: studentToken,
    body: {
      courseId: demoCourse?.id,
      chapterId: day?.id,
      sectionId: action?.id,
      noteMoment: 60,
      content: N_CONTENT,
      // ★ `isPrivate` 是 @NotNull（第一次跑漏了它，报「隐私标记不能为空」）
      isPrivate: false,
    },
  })
  check('★ 学员写了一条笔记', createN.body?.code === 200, `code=${createN.body?.code} msg=${createN.body?.msg ?? ''}`)

  const notePage = (await gw('GET', `/ls/admin/notes/page?pageNo=1&pageSize=50&courseId=${demoCourse?.id}`, {
    token: adminToken,
  })).body?.data
  const note = (await Promise.resolve(notePage?.list ?? [])).find((n) => String(n.content ?? '').includes(STAMP))
  noteId = note?.id ?? null
  check('★★ 管理端笔记列表能查到它', !!noteId, `noteId=${noteId}`)

  if (noteId) {
    await gw('PUT', `/ls/admin/notes/${noteId}/hidden/true`, { token: adminToken })
    const folded = (await gw('GET', `/ls/admin/notes/${noteId}`, { token: adminToken })).body?.data
    check('★★ 折叠生效（hidden=true）', folded?.hidden === true, `hidden=${folded?.hidden}`)
    /**
     * ★★ 笔记详情比列表项多两个字段：作者手机号、采集者名单。
     *   它们是"审核判断要不要处置"的关键依据（谁发的、扩散给谁了），
     *   而**列表里没有** —— 这正是管理端详情接口存在的意义。
     */
    check('★★ 笔记详情给出了**作者手机号**（列表里没有，只有审核详情才有）',
      typeof folded?.authorPhone === 'string' && folded.authorPhone.length > 0,
      `authorPhone=${folded?.authorPhone ?? '—'}`)
    check('★ 笔记详情给出了**采集者名单**字段（可能为空数组，但字段必须在）',
      Array.isArray(folded?.gathers), `gathers=${JSON.stringify(folded?.gathers ?? null)}`)

    await gw('PUT', `/ls/admin/notes/${noteId}/hidden/false`, { token: adminToken })
    const restored = (await gw('GET', `/ls/admin/notes/${noteId}`, { token: adminToken })).body?.data
    check('★ 恢复后 hidden=false（双向）', restored?.hidden === false, `hidden=${restored?.hidden}`)
  }

  // ================================================================
  section('5. 通知模板：CRUD + ★「代号不可改」实测')
  // ================================================================
  await admin.goto(`${ADMIN}/notices`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await waitStable(admin, '[data-testid="notice-tpl-table"]')
  check('公告与模板页可打开', true)

  await admin.click('[data-testid="notice-tpl-add"]')
  await admin.waitForSelector('[data-testid="notice-tpl-form-name"]', { timeout: 10000 })
  await admin.fill('[data-testid="notice-tpl-form-name"]', `M7-12c验收模板${STAMP}`)
  await admin.fill('[data-testid="notice-tpl-form-code"]', TPL_CODE)
  await admin.fill('[data-testid="notice-tpl-form-title"]', '验收通知标题 {userName}')
  await admin.fill('[data-testid="notice-tpl-form-content"]', '这是一条验收用的通知模板内容。')
  await admin.screenshot({ path: join(SHOT_DIR, 'm7-12c-03-tpl-form.png') })
  await admin.click('[data-testid="notice-tpl-form-submit"]')
  await admin.waitForTimeout(1800)

  const tplPage = (await gw('GET', '/sms/admin/notice-templates/page?pageNo=1&pageSize=50', { token: adminToken }))
    .body?.data
  const tpl = (tplPage?.list ?? []).find((t) => t.code === TPL_CODE)
  templateId = tpl?.id ?? null
  check('★★ 页面上新建的模板真的落库了（按 code 能查到）', !!templateId, `templateId=${templateId}`)

  if (templateId) {
    /**
     * ★★ 「代号不可改」的真实行为是**明确拒绝**，不是"静默忽略"。
     *   读 `NoticeServiceImpl.updateTemplate` 才知道：
     *   `if (dto.getCode() != null && !dto.getCode().equals(exist.getCode())) throw 模板代号不允许修改`
     *   —— 第一次跑时我按"会被忽略"写断言，于是传了新 code，
     *   结果**整个更新都被打回**（name/title 也没改），而我误判成"改名失败"。
     *   → 现在分两步验：①改 code 必被拒；②带原 code 才生效。
     */
    collectErrors = false
    const hackRes = await gw('PUT', '/sms/admin/notice-templates', {
      token: adminToken,
      body: {
        id: templateId,
        name: `M7-12c验收模板${STAMP}-改名`,
        code: `HACKED-${STAMP}`,
        type: 0,
        status: 1,
        title: '改了之后的标题',
        content: '改过的内容',
        isSmsTemplate: 0,
      },
    })
    collectErrors = true
    check('★★ 「代号不可改」是**明确拒绝**（不是静默忽略）：提交里塞新 code 的更新会被后端打回',
      hackRes.body?.code !== 200 && /代号/.test(String(hackRes.body?.msg ?? '')),
      `code=${hackRes.body?.code} msg=${hackRes.body?.msg ?? ''}`)
    const afterHack = (await gw('GET', `/sms/admin/notice-templates/${templateId}`, { token: adminToken })).body?.data
    check('★ 且被拒后库里**一点没变**（code 与 name 都是原值）',
      afterHack?.code === TPL_CODE && !String(afterHack?.name ?? '').includes('改名'),
      `code=${afterHack?.code} name=${afterHack?.name}`)

    // ② 带原 code 正常改 → 应当生效
    const okRes = await gw('PUT', '/sms/admin/notice-templates', {
      token: adminToken,
      body: {
        id: templateId,
        name: `M7-12c验收模板${STAMP}-改名`,
        code: TPL_CODE,
        type: 0,
        status: 1,
        title: '改了之后的标题',
        content: '改过的内容',
        isSmsTemplate: 0,
      },
    })
    check('★ 带**原 code** 的更新成功（证明上一条的失败确实是"代号"导致的，不是接口坏了）',
      okRes.body?.code === 200, `code=${okRes.body?.code}`)
    const updated = (await gw('GET', `/sms/admin/notice-templates/${templateId}`, { token: adminToken })).body?.data
    check('★★ 改名与标题都生效了', updated?.name?.includes('改名') && updated?.title === '改了之后的标题',
      `name=${updated?.name} title=${updated?.title}`)
    check('★ code 仍是原值（改成功了也没被动过）', updated?.code === TPL_CODE, `code=${updated?.code}`)

    const delRes = await gw('DELETE', `/sms/admin/notice-templates/${templateId}`, { token: adminToken })
    check('★ 模板删除成功', delRes.body?.code === 200, `code=${delRes.body?.code}`)
    const afterDel = (await gw('GET', `/sms/admin/notice-templates/${templateId}`, { token: adminToken })).body
    check('★ 删除后详情读不到了（真删了）', afterDel?.code !== 200 || !afterDel?.data,
      `code=${afterDel?.code}`)
    templateId = null
  }

  // ================================================================
  section('6. ★★ 公告：立即发布可见 / 定时发布**当时不可见**（全项目唯一的时间维度断言）')
  // ================================================================
  const inbox = () => gw('GET', '/sms/messages/page?pageNo=1&pageSize=50', { token: studentToken })

  /**
   * ★ 先查一次收件箱拿**基线**：
   *   一是排除"本来就有同名公告"的假绿；二是把 pull 模式的展开游标推到当前
   *   （`expandToInbox` 的起点是"用户已展开过的最新公告时间"，
   *    见 `PublicNoticeServiceImpl` —— 学员端**每次查收件箱**才把已到发布时间的公告
   *    展开成自己的记录，公告本身是 pull 模式，不是 push）。
   */
  const inboxBefore = (await inbox()).body?.data?.list ?? []
  check('★ 前置：发布前收件箱里没有这条立即公告（否则后面的"能看到"是假绿）',
    !inboxBefore.some((m) => String(m.title ?? '').includes(NOTICE_NOW)), `基线 ${inboxBefore.length} 条`)

  // ① 立即发布
  const pubNow = await gw('POST', '/sms/admin/public-notices', {
    token: adminToken,
    body: { type: 0, title: NOTICE_NOW, content: 'M7-12c 验收：立即发布的公告。' },
  })
  check('★★ 发布「立即」公告成功', pubNow.body?.code === 200, `code=${pubNow.body?.code} msg=${pubNow.body?.msg ?? ''}`)
  if (pubNow.body?.data) noticeIds.push(String(pubNow.body.data))

  /**
   * ★ 轮询确认（最多 5 次 × 1s）：机制上"下一次查询就该看到"，
   *   留重试余量是为了**不把偶发的时序波动当成功能坏掉**
   *   （这条断言本身已经由"前置基线为空"兜住了假绿风险）。
   */
  let seenNow = false
  let inbox1 = []
  for (let i = 0; i < 5; i++) {
    inbox1 = (await inbox()).body?.data?.list ?? []
    seenNow = inbox1.some((m) => String(m.title ?? '').includes(NOTICE_NOW))
    if (seenNow) break
    await new Promise((r) => setTimeout(r, 1000))
  }
  check('★★★ 立即公告**学员端能看到**（公告是真的发出去了）', seenNow, `学员收件箱 ${inbox1.length} 条`)

  // ② 定时发布（未来时间）
  const future = new Date(Date.now() + 24 * 3600 * 1000)
  const pad = (n) => String(n).padStart(2, '0')
  const futureStr = `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}T${pad(future.getHours())}:${pad(future.getMinutes())}:00`

  const pubFuture = await gw('POST', '/sms/admin/public-notices', {
    token: adminToken,
    body: { type: 0, title: NOTICE_FUTURE, content: 'M7-12c 验收：定时发布的公告（明天才该可见）。', pushTime: futureStr },
  })
  check('★★ 发布「定时」公告成功（pushTime 填未来）', pubFuture.body?.code === 200,
    `code=${pubFuture.body?.code} pushTime=${futureStr}`)
  if (pubFuture.body?.data) noticeIds.push(String(pubFuture.body.data))

  const inbox2 = (await inbox()).body?.data?.list ?? []
  check('★★★ 定时公告**当时不可见**（学员端查不到）—— 全项目唯一的"时间维度"断言',
    !inbox2.some((m) => String(m.title ?? '').includes(NOTICE_FUTURE)),
    `学员收件箱 ${inbox2.length} 条`)

  const noticePage = (await gw('GET', '/sms/admin/public-notices/page?pageNo=1&pageSize=50', { token: adminToken }))
    .body?.data
  const bothInAdmin = (noticePage?.list ?? []).filter(
    (n) => String(n.title).includes(NOTICE_NOW) || String(n.title).includes(NOTICE_FUTURE),
  )
  check('★★ 两条公告在**管理端列表里都在**（定时 ≠ 没发布）', bothInAdmin.length === 2,
    `找到 ${bothInAdmin.length} 条`)

  await admin.goto(`${ADMIN}/notices`, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await waitStable(admin, '[data-testid="notice-tpl-table"]')
  await admin.locator('[data-testid="notice-tabs"] .el-tabs__item', { hasText: '公告' }).first().click()
  await admin.waitForTimeout(1200)
  await admin.screenshot({ path: join(SHOT_DIR, 'm7-12c-04-notice-publish.png') })
  check('★ 公告 tab 可切换并渲染', true)

  // ================================================================
  section('7. ★★ 清理：自造数据自己删（并自检）')
  // ================================================================
  collectErrors = false
  // ① 问题（会连带删掉它下面的回答 —— 后端注释："删除问题（连带其下回答与评论）"）
  if (questionId) {
    const delQ = await gw('DELETE', `/ls/questions/${questionId}`, { token: studentToken })
    check('问题（连带其回答）已删除', delQ.body?.code === 200, `code=${delQ.body?.code}`)
  }
  // ② 笔记
  if (noteId) {
    const delN = await gw('DELETE', `/ls/notes/${noteId}`, { token: studentToken })
    check('笔记已删除', delN.body?.code === 200, `code=${delN.body?.code}`)
  }
  collectErrors = true

  const qLeft = (await gw('GET', `/ls/admin/questions/page?pageNo=1&pageSize=50&courseId=${demoCourse?.id}`, {
    token: adminToken,
  })).body?.data?.list ?? []
  check('★★ 清理自检：问题列表里已经没有它了',
    !qLeft.some((q) => String(q.id) === String(questionId)), `剩余 ${qLeft.length} 条`)

  const nLeft = (await gw('GET', `/ls/admin/notes/page?pageNo=1&pageSize=50&courseId=${demoCourse?.id}`, {
    token: adminToken,
  })).body?.data?.list ?? []
  check('★★ 清理自检：笔记列表里已经没有它了',
    !nLeft.some((n) => String(n.id) === String(noteId)), `剩余 ${nLeft.length} 条`)

  /**
   * ③ 公告：**没有删除接口** → 只能 SQL 删。
   *   ⚠️ 还要一起删 `user_inbox`：公告是 **pull 模式**，学员端一查收件箱就把公告
   *   **展开成了自己的记录**（`expandToInbox`）。只删公告不删收件箱，
   *   学员端会看到"幽灵消息"——内容在、但源头已经不存在了。
   *   SQL 不可用时**把待执行的语句打出来**（不静默留垃圾）。
   */
  if (noticeIds.length) {
    if (SQL_AVAILABLE) {
      sqlCells(`DELETE FROM fm_message.user_inbox WHERE title LIKE 'M7-12c%${STAMP}%'`)
      sqlCells(`DELETE FROM fm_message.public_notice WHERE id IN (${noticeIds.join(',')})`)
      const leftNotice = sqlCells(`SELECT COUNT(*) FROM fm_message.public_notice WHERE id IN (${noticeIds.join(',')})`)
      const leftInbox = sqlCells(`SELECT COUNT(*) FROM fm_message.user_inbox WHERE title LIKE 'M7-12c%${STAMP}%'`)
      check('★★ 清理自检：验收公告与它展开出的收件箱记录都已删除（公告没有删除接口，只能走 SQL）',
        Number(leftNotice[0]?.[0] ?? -1) === 0 && Number(leftInbox[0]?.[0] ?? -1) === 0,
        `公告剩 ${leftNotice[0]?.[0]} / 收件箱剩 ${leftInbox[0]?.[0]}`)
    } else {
      checkBlocked('清理验收公告（public_notice 无删除接口）',
        '本环境无法执行 MySQL。请手工执行：\n' +
          `        DELETE FROM fm_message.user_inbox    WHERE title LIKE 'M7-12c%${STAMP}%';\n` +
          `        DELETE FROM fm_message.public_notice WHERE id IN (${noticeIds.join(',')});`)
    }
  }

  // ================================================================
  section('8. 浏览器运行时健康度')
  // ================================================================
  check('admin 端无 JS / 业务码异常', pageErrors.filter((e) => e.startsWith('[admin]')).length === 0,
    pageErrors.filter((e) => e.startsWith('[admin]')).slice(0, 2).join(' | '))
  check('portal 端无 JS / 业务码异常', pageErrors.filter((e) => e.startsWith('[portal]')).length === 0,
    pageErrors.filter((e) => e.startsWith('[portal]')).slice(0, 2).join(' | '))

  console.log('')
  console.log('='.repeat(74))
  console.log(`  结果：${pass} 通过 / ${fail} 失败`)
  if (skipped) console.log(`  另有 ${skipped} 条「直查数据库」断言因本环境无法执行 MySQL 而未验证`)
  if (blocked) console.log(`  另有 ${blocked} 项因**前置数据/环境限制**无法验收（见下）`)
  if (failures.length) console.log('  失败项：\n    ' + failures.join('\n    '))
  if (blockedNotes.length) console.log('  🚧 受阻项（不是失败，但也绝不是通过）：\n    ' + blockedNotes.join('\n    '))
  if (skipNotes.length) console.log('  未验证项：\n    ' + skipNotes.join('\n    '))
  console.log('  截图：' + SHOT_DIR)
  console.log('='.repeat(74))
} catch (err) {
  console.error('')
  console.error('='.repeat(74))
  console.error('❌ 脚本异常中断：', err instanceof Error ? err.message : String(err))
  console.error('')
  console.error('⚠️ 自造数据可能仍留在库里。手工清理（STAMP=' + STAMP + '）：')
  console.error(`     DELETE FROM fm_learning.interaction_question WHERE title LIKE '%${STAMP}%';`)
  console.error(`     DELETE FROM fm_learning.note                  WHERE content LIKE '%${STAMP}%';`)
  console.error(`     DELETE FROM fm_message.notice_template        WHERE code LIKE '%${STAMP}%';`)
  console.error(`     DELETE FROM fm_message.public_notice          WHERE title LIKE '%${STAMP}%';`)
  console.error('='.repeat(74))
  fail++
}

await browser.close().catch(() => undefined)
process.exit(fail === 0 ? 0 : 1)
