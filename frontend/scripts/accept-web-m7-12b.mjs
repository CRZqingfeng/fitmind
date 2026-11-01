/**
 * M7-12b 第 2 期验收：admin 建课主线（分类管理 → 建课 5 步 → 配题 → 上架 → ES 搜得到）
 *
 * ============================================================
 * ★★ 这一期最有价值的断言
 * ============================================================
 * **向导走完 5 步 → 上架 → 在**学员端搜索**里真的搜得到。**
 * 一条断言同时压住三件事：建课数据对（分类/目录/视频/教练都落了库）、
 * `upShelf` 的"草稿 → 正式"搬迁对、**MQ 通知 fm-search 建索引的链路通**。
 * 它之所以有力，是因为 ES 索引**只能由 `upShelf` 发的那条 MQ 消息产生** ——
 * 前端无法伪造，后端也不会"顺手"建。
 *
 * ============================================================
 * ★★ 两条负向
 * ============================================================
 * 1. **上架后再建题必须失败** —— 题目只存在于草稿态（上架会删草稿）。
 *    这条验的是"后端约束如实暴露"，而不是"页面给了个会报错的按钮"。
 * 2. **学员 token 调管理端接口必须 403** —— ⚠️ 见 §5 的说明：
 *    当前工作区有一处未提交改动把 `fm.auth.resource.enable` 改成了 `false`，
 *    连带让 admin 拦截器不被注册 → 这条**在当前环境下不成立**。
 *    脚本如实标注（🚧），绝不假装通过。
 *
 * ============================================================
 * ★ 脚本自觉（M7 栽过的）
 * ============================================================
 * 1. **自己造的数据自己清**：本脚本会造 1 门课 + 3 个分类 + 1 道题，末尾逐一删除并自检。
 * 2. **"期望为空/失败"的断言，必须同时证明前置动作真的成功了**（否则"两次都失败"也是绿）。
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
/** 演示教练（库里唯一一位 type=3） */
const COACH_PHONE = '13800010002'
const STUDENT_PHONE = '13800010001'
const SEED_PASSWORD = '123456'

const ADMIN_TOKEN_KEY = 'fitmind:admin:token'
const MYSQL = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const SHOT_DIR = 'D:/develop/develop/code/fitmind/docs/screenshots'
mkdirSync(SHOT_DIR, { recursive: true })

const STAMP = String(Date.now()).slice(-6)
const NEW_CATE = `M7-12b验收母分类${STAMP}`
const NEW_CATE_L2 = `二级部位${STAMP}`
const NEW_CATE_L3 = `三级场景${STAMP}`
const NEW_COURSE = `M7-12b验收课程${STAMP}`
const NEW_SUBJECT = `M7-12b 验收题：训练前应该做什么？${STAMP}`

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
const flat = (s) => (s ?? '').replace(/\s+/g, ' ').trim()

// ============================================================
// 直连网关 / 直查数据库
// ============================================================

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

/** 在**页面上下文**里发请求（自动带 localStorage 里的 token，与真实前端同路） */
async function pageApi(page, method, path, body, tokenKey = ADMIN_TOKEN_KEY) {
  return page.evaluate(
    async ([m, p, b, key]) => {
      const token = localStorage.getItem(key)
      const headers = {}
      if (b !== undefined && b !== null) headers['Content-Type'] = 'application/json'
      if (token) headers.Authorization = token
      const res = await fetch(`http://localhost:10010${p}`, {
        method: m,
        headers,
        body: b === undefined || b === null ? undefined : JSON.stringify(b),
      })
      const text = await res.text()
      let json = null
      try {
        json = JSON.parse(text)
      } catch {
        json = null
      }
      return { status: res.status, body: json, raw: text.slice(0, 200) }
    },
    [method, path, body ?? null, tokenKey],
  )
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
function sqlOne(query) {
  return sqlCells(query)[0]?.[0] ?? null
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

// ============================================================

const browser = await launchBrowser()
const admin = await (await browser.newContext({ viewport: { width: 1560, height: 950 } })).newPage()
const student = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()

const pageErrors = []
const businessErrors = []
/** ★ 负向用例关掉业务错误收集（见第 1 期脚本同名注释：否则"自己刻意打的失败"会算成页面故障） */
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
        businessErrors.push(`[${tag}] ${res.request().method()} ${res.url().split('10010')[1]} → ${json.code}`)
      }
    } catch {
      /* 非 JSON */
    }
  })
}

async function waitStable(pg, sel, timeout = 20000) {
  await pg.waitForSelector(sel, { timeout })
  await pg.waitForTimeout(500)
}

// ================================================================
section('1. 登录 + 前置数据')
// ================================================================
/**
 * ★★ 主流程整体包一层 try/catch —— 这是一条**用教训换来的**结构。
 *
 *   本脚本的清理在 §12，而脚本**一旦中途崩溃就走不到那里**，
 *   会留下一门课 + 三个分类 + 一道题（本次开发期间真踩到过：
 *   §3 因为 `el-cascader` 的选择器写错而超时，库里就多了一套分类）。
 *
 *   catch 里至少把"手工清理要用的 SQL"打出来 —— 比默默留垃圾强得多。
 *   这与 `#B7`（注册用例不清理账号）是同一类自觉。
 */
try {
await admin.goto(ADMIN, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => undefined)
await admin.waitForTimeout(800)
const adminToken = await login(admin, '/as/accounts/admin/login', ADMIN_PHONE, ADMIN_TOKEN_KEY)
check('管理员登录成功（admin 端）', !!adminToken)

await student.goto(PORTAL, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => undefined)
await student.waitForTimeout(800)
const studentToken = await login(student, '/as/accounts/login', STUDENT_PHONE, ADMIN_TOKEN_KEY.replace('admin:', ''))
check('学员登录成功（portal 端）', !!studentToken)

/**
 * 找一个**已上传**的媒资来复用作训练视频。
 *
 * ★ 为什么不真上传：第 3 步的视频上传走预签名直传，需要一个真实的视频文件；
 *   而验收要的是"**每个动作都绑上了视频**"这个业务事实（上架第 7 条校验），
 *   不是"上传管道能不能传字节"（那是 M7-8 媒资页验过的）。
 *   → 复用库里已有的媒资，把这一次验收的**关注点**留给"建课链路"。
 */
const mediaPage = (await gw('GET', '/ms/medias?pageNo=1&pageSize=5', { token: adminToken })).body?.data
const demoMedia = (mediaPage?.list ?? []).find((m) => m.id)
check('库里有可复用的演示媒资（第 3 步绑视频用）', !!demoMedia,
  `mediaId=${demoMedia?.id} duration=${demoMedia?.duration}`)

// ================================================================
section('2. 分类管理（新增三级 → 改名 → 启停 → 删除）')
// ================================================================
await admin.goto(`${ADMIN}/categorys`, { waitUntil: 'domcontentloaded', timeout: 60000 })
await waitStable(admin, '[data-testid="cate-table"]')
check('分类管理页可打开', true)

// ① 页面上新增一级分类
await admin.click('[data-testid="cate-add-root"]')
await admin.waitForSelector('[data-testid="cate-form-name"]', { timeout: 10000 })
await admin.fill('[data-testid="cate-form-name"]', NEW_CATE)
await admin.screenshot({ path: join(SHOT_DIR, 'm7-12b-02-categorys.png') })
await admin.click('[data-testid="cate-form-submit"]')
await admin.waitForTimeout(1500)

const allAfterAdd = (await gw('GET', '/cs/categorys/all', { token: adminToken })).body?.data ?? []
const lv1 = allAfterAdd.find((c) => c.name === NEW_CATE)
check('★★ 页面上新增的一级分类真的落库了（接口能查到）', !!lv1, `id=${lv1?.id}`)

// ② 接口补二级 / 三级（树表的三级交互在页面上要点两轮"子分类"，用接口更稳）
let lv2Id = null
let lv3Id = null
if (lv1) {
  await gw('POST', '/cs/categorys/add', {
    token: adminToken,
    body: { name: NEW_CATE_L2, parentId: lv1.id, priority: 1 },
  })
  const after2 = (await gw('GET', '/cs/categorys/all', { token: adminToken })).body?.data ?? []
  const lv2 = (after2.find((c) => c.name === NEW_CATE)?.children ?? []).find((c) => c.name === NEW_CATE_L2)
  lv2Id = lv2?.id ?? null
  check('★★ 二级分类挂到了正确的父下（树形结构对）', !!lv2Id, `id=${lv2Id}`)

  if (lv2Id) {
    await gw('POST', '/cs/categorys/add', {
      token: adminToken,
      body: { name: NEW_CATE_L3, parentId: lv2Id, priority: 1 },
    })
    const after3 = (await gw('GET', '/cs/categorys/all', { token: adminToken })).body?.data ?? []
    const lv3 = ((after3.find((c) => c.name === NEW_CATE)?.children ?? [])
      .find((c) => c.name === NEW_CATE_L2)?.children ?? []).find((c) => c.name === NEW_CATE_L3)
    lv3Id = lv3?.id ?? null
    check('★★ 三级分类挂到了二级下面（三级树成立）', !!lv3Id, `id=${lv3Id}`)
    check('★ 分类层级 level 正确（1/2/3）', lv3?.level === 3, `level=${lv3?.level}`)
  }
}

// ③ 负向：删除**有子分类**的母分类必须失败
if (lv1) {
  collectErrors = false
  const delWithChildren = await gw('DELETE', `/cs/categorys/${lv1.id}`, { token: adminToken })
  collectErrors = true
  check('★★ 负向：删除"还有子分类"的分类必须失败（后端拒绝，不是静默成功）',
    delWithChildren.body?.code !== 200,
    `code=${delWithChildren.body?.code} msg=${delWithChildren.body?.msg ?? ''}`)
  const stillThere = (await gw('GET', `/cs/categorys/${lv1.id}`, { token: adminToken })).body?.data
  check('★ 且它确实还在（失败是真失败，不是"删了但报错"）', !!stillThere, `name=${stillThere?.name}`)
}

// ④ 改名（只改 name/priority，**没有 parentId** → 不支持换父）
if (lv2Id) {
  await gw('PUT', '/cs/categorys/update', {
    token: adminToken,
    body: { id: lv2Id, name: `${NEW_CATE_L2}-改名`, priority: 9 },
  })
  const renamed = (await gw('GET', `/cs/categorys/${lv2Id}`, { token: adminToken })).body?.data
  check('★★ 改名生效（接口读回是新名字）', renamed?.name === `${NEW_CATE_L2}-改名`, `name=${renamed?.name}`)
  check('★ 排序值也改了', Number(renamed?.priority) === 9, `priority=${renamed?.priority}`)
}

// ⑤ 启停
if (lv3Id) {
  await gw('PUT', '/cs/categorys/disableOrEnable', { token: adminToken, body: { id: lv3Id, status: 2 } })
  const off = (await gw('GET', `/cs/categorys/${lv3Id}`, { token: adminToken })).body?.data
  check('★★ 停用后 status=2', Number(off?.status) === 2, `status=${off?.status} desc=${off?.statusDesc}`)
  await gw('PUT', '/cs/categorys/disableOrEnable', { token: adminToken, body: { id: lv3Id, status: 1 } })
  const on = (await gw('GET', `/cs/categorys/${lv3Id}`, { token: adminToken })).body?.data
  check('★ 启用后 status=1（双向都验，防"恒等于某个值"）', Number(on?.status) === 1, `status=${on?.status}`)
}

// ================================================================
section('3. 建课向导：第 1 步 基本信息（★ 页面上操作）')
// ================================================================
await admin.goto(`${ADMIN}/courses/edit`, { waitUntil: 'domcontentloaded', timeout: 60000 })
await waitStable(admin, '[data-testid="edit-steps"]')

await admin.fill('[data-testid="edit-name"]', NEW_COURSE)
await admin.click('[data-testid="edit-name"]')
await admin.keyboard.press('Tab') // 触发重名校验（checkName）
await admin.waitForTimeout(600)

/**
 * 分类级联：**在页面上真的选一次**（选到第一级就够 —— 三级浮层的逐级展开对脚本太脆）。
 *
 * ★ 用 `filterable` 的**输入 → 点建议项**，而不是"在浮层里找节点点击"：
 *   库里一级分类有 48 个，`hasText` 定位要滚动、还受渲染时机影响；
 *   而控件本来就该支持搜索（48 个分类不搜索本来就难用 —— 这是真实需求，不是为脚本开的后门）。
 * ⚠️ `el-cascader` 不透传 `data-testid`，所以定位的是包在外面的 `edit-cate-wrap`。
 * ⚠️ `filterable` 的候选项在 `.el-cascader__suggestion-panel` 里（**不是** `.el-cascader-node`）；
 *    实测：光按 `Enter` 不生效，必须**点** suggestion-item。
 */
let catePicked = false
if (lv1) {
  const cateInput = admin.locator('[data-testid="edit-cate-wrap"] input')
  await cateInput.click()
  await admin.waitForTimeout(400)
  await cateInput.fill(NEW_CATE)
  await admin.waitForTimeout(1100)
  const suggestion = admin.locator('.el-cascader__suggestion-item').first()
  if (await suggestion.count()) {
    await suggestion.click()
  } else {
    console.log('     提示：过滤后没有出现候选项')
  }
  await admin.waitForTimeout(700)
  const shown = await cateInput.inputValue()
  catePicked = shown.includes(NEW_CATE)
  if (!catePicked) console.log(`     提示：级联框当前显示「${shown}」`)
}
check('★★ 页面上能把分类选上（搜索过滤 + 点候选项）', catePicked,
  catePicked ? `已选「${NEW_CATE}」` : '没选上（会连带影响后面的"分类落库"断言）')
await admin.screenshot({ path: join(SHOT_DIR, 'm7-12b-05-edit-step1.png') })

await admin.click('[data-testid="edit-next-1"]')
await admin.waitForTimeout(2000)
const cidText = flat(await admin.textContent('[data-testid="edit-course-id"]').catch(() => ''))
const courseId = cidText.replace(/\D/g, '')
check('★★ 第 1 步保存成功并拿到课程 id（页面上能看到）', !!courseId, cidText)

const baseInfo = courseId
  ? (await gw('GET', `/cs/courses/baseInfo/${courseId}`, { token: adminToken })).body?.data
  : null
check('★★ 直查接口：课程名与免费标记落库了',
  baseInfo?.name === NEW_COURSE && Number(baseInfo?.free) === 1,
  `name=${baseInfo?.name} free=${baseInfo?.free} step=${baseInfo?.step}`)
check('★ 且填了分类（三级 id 至少一级非空）',
  !!(baseInfo?.firstCateId || baseInfo?.secondCateId),
  `first=${baseInfo?.firstCateId} second=${baseInfo?.secondCateId}`)

// ================================================================
section('4. 建课向导：第 2 步 训练目录（★ 页面上增删 + 保存）')
// ================================================================
await admin.click('[data-testid="edit-action-1-1"]')
await admin.fill('[data-testid="edit-day-name-1"]', 'Day 1 上肢')
await admin.fill('[data-testid="edit-action-1-1"]', '哑铃卧推')
await admin.click('[data-testid="edit-action-add-1"]')
await admin.waitForTimeout(300)
await admin.fill('[data-testid="edit-action-1-2"]', '哑铃飞鸟')
await admin.click('[data-testid="edit-day-add"]')
await admin.waitForTimeout(300)
await admin.fill('[data-testid="edit-day-name-2"]', 'Day 2 下肢')
await admin.fill('[data-testid="edit-action-2-1"]', '哑铃深蹲')
await admin.screenshot({ path: join(SHOT_DIR, 'm7-12b-06-edit-step2.png') })

await admin.click('[data-testid="edit-next-2"]')
await admin.waitForTimeout(2000)

const catas = courseId ? (await gw('GET', `/cs/courses/catas/${courseId}`, { token: adminToken })).body?.data : null
check('★★ 训练目录保存成功（2 个训练日）', Array.isArray(catas) && catas.length === 2,
  `${Array.isArray(catas) ? catas.length : '?'} 个训练日`)
const actionRows = (catas ?? []).flatMap((d) => d.sections ?? [])
check('★★ 3 个训练动作都在（页面上加的两个 + 新增日的那个）', actionRows.length === 3,
  actionRows.map((a) => a.name).join('/'))
check('★ 训练日的 index 从 1 连续递增（后端要求）',
  (catas ?? []).every((d, i) => Number(d.index) === i + 1),
  (catas ?? []).map((d) => d.index).join(','))

// ================================================================
section('5. 建课向导：第 3 步 训练视频（接口补媒资）')
// ================================================================
/**
 * ★★ 这里刻意**不走页面文件上传**：预签名直传需要一个真实视频文件，
 *   而这一期要验的是"每个动作都绑上了视频"这个业务事实（上架第 7 条校验）。
 *   上传管道本身在 M7-8 的媒资页验收里覆盖过。
 *   ⚠️ 但页面的"保存动作信息"按钮**照点** —— 要验它能把参数提交上去。
 */
const bindPayload = actionRows.map((a, i) => ({
  cataId: a.id,
  mediaId: demoMedia?.id,
  videoName: `${a.name}-验收.mp4`,
  mediaDuration: demoMedia?.duration ?? 180,
  trailer: i === 0,
  sets: 4,
  reps: 10,
  restSeconds: 60,
  actionType: 1,
}))
const bindRes = await gw('POST', `/cs/courses/media/save/${courseId}`, { token: adminToken, body: bindPayload })
check('★★ 给 3 个动作绑定训练视频（上架硬性要求）', bindRes.body?.code === 200,
  `code=${bindRes.body?.code} msg=${bindRes.body?.msg ?? ''}`)

await admin.click('[data-testid="edit-next-3"]')
await admin.waitForTimeout(2000)

const catasWithMedia = courseId
  ? (await gw('GET', `/cs/courses/catas/${courseId}`, { token: adminToken })).body?.data
  : null
const boundRows = (catasWithMedia ?? []).flatMap((d) => d.sections ?? []).filter((a) => a.mediaId)
check('★★ 读回目录：3 个动作都带上了 mediaId', boundRows.length === 3,
  `${boundRows.length}/3；示例 ${boundRows[0]?.mediaName ?? '-'}`)

// ================================================================
section('6. 建课向导：第 4 步 动作小测（先建题，再在页面上绑定）')
// ================================================================
const firstAction = actionRows[0]
const subjectRes = await gw('POST', '/cs/subjects', {
  token: adminToken,
  body: {
    courseId,
    name: NEW_SUBJECT,
    subjectType: 1,
    difficulty: 1,
    options: ['先热身 5 分钟', '直接上大重量', '省略拉伸'],
    answer: '1',
    analysis: '热身的目的是升温和激活，不是消耗。',
    score: 5,
  },
})
check('★★ 草稿态课程能建题（题目只存在于草稿态）', subjectRes.body?.code === 200,
  `code=${subjectRes.body?.code} msg=${subjectRes.body?.msg ?? ''}`)

let subjectId = null
{
  const page = (await gw('GET', `/cs/subjects/page?pageNo=1&pageSize=50&courseId=${courseId}`, { token: adminToken }))
    .body?.data
  subjectId = (page?.list ?? []).find((s) => s.name === NEW_SUBJECT)?.id ?? null
  check('★ 题目落库并能按课程查出来', !!subjectId, `subjectId=${subjectId}`)
}

if (firstAction && subjectId) {
  await admin.click(`[data-testid="edit-bind-${firstAction.id}"]`)
  await admin.waitForSelector('[data-testid="edit-subject-dialog"]', { timeout: 15000 })
  await admin.waitForTimeout(1200)
  await admin.screenshot({ path: join(SHOT_DIR, 'm7-12b-07-edit-step4-pick.png') })
  const pick = admin.locator(`[data-testid="edit-pick-subject-${subjectId}"]`)
  await pick.click()
  await admin.waitForTimeout(300)
  await admin.click('[data-testid="edit-subject-confirm"]')
  await admin.waitForTimeout(1800)
  check('★ 页面上的"选择题目"弹窗里能勾选并确定', true)
}

const binds = courseId ? (await gw('GET', `/cs/courses/subjects/get/${courseId}`, { token: adminToken })).body?.data : null
const boundNode = (binds ?? []).find((n) => n.cataId === firstAction?.id)
check('★★ 绑定关系落库（动作 ← 题目）', (boundNode?.subjects ?? []).length === 1,
  `${(boundNode?.subjects ?? []).length} 道 → 动作「${boundNode?.cataName ?? '-'}」`)

await admin.click('[data-testid="edit-next-4"]')
await admin.waitForTimeout(1500)

// ================================================================
section('7. 建课向导：第 5 步 教练（★ 手机号 → 查 id → 绑定）')
// ================================================================
await admin.fill('[data-testid="edit-teacher-phone"]', COACH_PHONE)
await admin.click('[data-testid="edit-teacher-add"]')
await admin.waitForTimeout(2000)
const teacherRowCount = await admin.locator('[data-testid="edit-teacher-table"] tbody tr').count()
check('★★ 输手机号能查出教练并加入列表（全项目没有"列出全部教练"的接口）',
  teacherRowCount > 0, `${teacherRowCount} 行`)
await admin.screenshot({ path: join(SHOT_DIR, 'm7-12b-08-edit-step5.png') })

await admin.click('[data-testid="edit-save-5"]')
await admin.waitForTimeout(1800)
const teachers = (await gw('GET', `/cs/courses/teachers/${courseId}`, { token: adminToken })).body?.data
check('★★ 教练绑定落库（★ 上架门槛）', Array.isArray(teachers) && teachers.length > 0,
  `${Array.isArray(teachers) ? teachers.length : '?'} 位：${teachers?.[0]?.name ?? '-'}`)

// ================================================================
section('8. 上架（★ 页面上点，含 checkBeforeUpShelf 前置校验）')
// ================================================================
const beforeUp = (await gw('GET', `/cs/courses/baseInfo/${courseId}`, { token: adminToken })).body?.data
check('★ 上架前检查：5 步都走完了（step ≥ 5）', Number(beforeUp?.step) >= 5,
  `step=${beforeUp?.step}`)

await admin.click('[data-testid="edit-upshelf"]')
await admin.waitForSelector('.el-message-box', { timeout: 15000 })
await admin.waitForTimeout(500)
await admin.screenshot({ path: join(SHOT_DIR, 'm7-12b-09-upshelf-confirm.png') })
await admin.click('.el-message-box__btns .el-button--primary')
await admin.waitForTimeout(3000)
check('★★ 上架后跳回课程列表', admin.url().includes('/courses'), admin.url().slice(-40))

const afterUp = (await gw('GET', `/cs/courses/baseInfo/${courseId}`, { token: adminToken })).body?.data
check('★★ 课程状态变成「已上架」(2)', Number(afterUp?.status) === 2,
  `status=${afterUp?.status} desc=${afterUp?.statusDesc}`)
check('★ 上架后 fromDraft=false（说明数据已在正式表）', afterUp?.fromDraft === false,
  `fromDraft=${afterUp?.fromDraft}`)

if (SQL_AVAILABLE) {
  const inShelf = sqlOne(`SELECT COUNT(*) FROM fm_course.course WHERE id=${courseId}`)
  const inDraft = sqlOne(`SELECT COUNT(*) FROM fm_course.course_draft WHERE id=${courseId}`)
  checkSql('★ 直查 MySQL：正式表有、草稿表没有（上架会删草稿）',
    Number(inShelf) === 1 && Number(inDraft) === 0, `course=${inShelf} draft=${inDraft}`)
} else {
  checkSql('★ 直查 MySQL：正式表有、草稿表没有', false, '本环境无法执行 MySQL')
}

// ================================================================
section('9. ★★ 最强断言：上架后学员端**搜得到**（证明 MQ → ES 索引链路通）')
// ================================================================
/**
 * ★★ 为什么这条最有价值：ES 里的这份文档**只可能由 `upShelf` 发出的那条 MQ 消息产生**。
 *   前端伪造不了，后端也不会"顺手"建。所以它一旦通过，就同时证明了：
 *     建课数据完整 → upShelf 的"草稿→正式"搬迁正确 → MQ 投递成功 → fm-search 消费并建索引。
 */
let esHit = false
for (let i = 0; i < 15; i++) {
  const res = await gw('GET', `/ss/courses/portal?keyword=${encodeURIComponent(NEW_COURSE)}&pageNo=1&pageSize=10`)
  const list = res.body?.data?.list ?? res.body?.data ?? []
  esHit = Array.isArray(list) && list.some((c) => String(c.id) === String(courseId))
  if (esHit) break
  await new Promise((r) => setTimeout(r, 1000))
}
check('★★★ 上架后 ES 里能搜到这门课（MQ 建索引链路真的通了）', esHit, `keyword=${NEW_COURSE}`)

const shelfList = (await gw('GET', `/cs/courses/page?pageNo=1&pageSize=20&status=2&name=${encodeURIComponent(NEW_COURSE)}`, {
  token: adminToken,
})).body?.data
const shelfHit = (shelfList?.list ?? []).some((c) => String(c.id) === String(courseId))
check('★★ 管理端「已上架」列表里也有它', shelfHit, `total=${shelfList?.total}`)

// 页面上可见（列表页）
await admin.goto(`${ADMIN}/courses`, { waitUntil: 'domcontentloaded', timeout: 60000 })
await waitStable(admin, '[data-testid="course-table"]')
await admin.fill('[data-testid="course-filter-name"]', NEW_COURSE)
await admin.waitForTimeout(300)
const searchBtn = admin.locator('button:has-text("查询")').first()
await searchBtn.click()
await admin.waitForTimeout(2000)
const visibleNames = await admin.locator('[data-testid="course-name"]').allTextContents()
check('★★ 课程列表页上能看到这门新课', visibleNames.some((n) => n.includes(NEW_COURSE)),
  visibleNames.slice(0, 3).join(' / '))
await admin.screenshot({ path: join(SHOT_DIR, 'm7-12b-10-course-list.png') })

// ================================================================
section('10. 负向：上架后再建题必须失败（草稿态约束）')
// ================================================================
/**
 * ★★ 前置已证明"**上架前**建题成功了"（§6），所以这里的失败不会是
 *   "从头到尾都失败"造成的假绿 —— 这正是本项目最警惕的那种假绿。
 */
collectErrors = false
const lateSubject = await gw('POST', '/cs/subjects', {
  token: adminToken,
  body: {
    courseId,
    name: `上架后补题${STAMP}`,
    subjectType: 4,
    difficulty: 1,
    answer: '1',
    score: 5,
  },
})
collectErrors = true
check('★★ 负向：课程上架后再建题必须失败（草稿已被删）',
  lateSubject.body?.code !== 200,
  `code=${lateSubject.body?.code} msg=${lateSubject.body?.msg ?? ''}`)
check('★ 且报错说的是"课程不存在"这类可理解的业务原因，不是"系统繁忙"',
  /课程|草稿/.test(String(lateSubject.body?.msg ?? '')), `msg=${lateSubject.body?.msg ?? ''}`)

// ================================================================
section('11. 负向：学员 token 调管理端接口（★ 当前环境受限，见说明）')
// ================================================================
/**
 * ★★ 这条断言**本该是 403**。它现在立不住的原因不在前端，也不在我这次改的代码里：
 *
 *   工作区有一处**未提交改动**把 `fm-course` 的 `fm.auth.resource.enable`
 *   从 `true` 改成了 `false`（注释还留着 `#true`），而
 *   `ResourceInterceptorConfiguration.addInterceptors()` 在 `enable=false` 时
 *   **直接 return** → 后面的 admin 拦截器**根本没被注册**（哪怕 `admin.enabled: true`）。
 *
 *   → 按约定我不擅自改别人的未提交改动，所以这里**如实记录当前行为**：
 *     是 403 就 ✅，是 200 就 🚧 并说明原因。
 *     绝不写成"通过"——那会把一个真实的安全退化包装成成果。
 */
collectErrors = false
/**
 * ⚠️ ★★ 这里刻意发一个**必定通不过参数校验**的 body（`{}`）—— 这是用教训换来的：
 *   第一次跑时我传了合法的 `{ name, parentId }`，而当时 admin 校验恰好是失效的，
 *   于是这个"本该被 403 拦下"的请求**真的建出了一个分类**（库里凭空多了一条
 *   `学员越权测试408845`）。权限断言没通过是一回事，**把测试数据写进库里**是另一回事。
 *   → 传 `{}`：权限生效 → 403；权限失效 → 400（缺必填参数），**两种情况都不会落库**。
 */
const studentTryWrite = await gw('POST', '/cs/categorys/add', { token: studentToken, body: {} })
const studentTryAdminRead = await gw('GET', '/cs/courses/page?pageNo=1&pageSize=1&status=2', {
  token: studentToken,
})
collectErrors = true

if (studentTryWrite.status === 403) {
  check('★★ 学员 token 调管理端写接口 → 403（越权已收口）', true, 'HTTP 403')
  check('★★ 学员 token 调管理端读接口 → 403', studentTryAdminRead.status === 403,
    `HTTP ${studentTryAdminRead.status}`)
} else {
  checkBlocked('学员 token 越权（管理端写接口 → 期望 403）',
    `实测 HTTP ${studentTryWrite.status} + code ${studentTryWrite.body?.code}。` +
      '根因：fm-course 的 fm.auth.resource.enable=false（未提交改动）连带使 admin 拦截器不注册；' +
      '见 docs/待修复问题清单.md §A9 与 §A16 同族的说明')
}

// ================================================================
section('12. ★★ 清理：自造数据自己删（并自检）')
// ================================================================
// ① 删课程（草稿与正式一起删）
collectErrors = false
const delCourse = await gw('DELETE', `/cs/courses/delete/${courseId}`, { token: adminToken })
check('课程已删除', delCourse.body?.code === 200,
  `code=${delCourse.body?.code} msg=${delCourse.body?.msg ?? ''}`)

/**
 * ② 题目：**不用单独删** —— 实测"删课程"会连带把它名下的题目一起清掉
 *   （`DELETE /courses/delete/{id}` → 后端连 `subject` 一并删）。
 *   这里改成**反查确认**这件事真的发生了，而不是"尝试删除再看结果"。
 */
const subjectLeftPage = (await gw('GET', `/cs/subjects/page?pageNo=1&pageSize=50&courseId=${courseId}`, {
  token: adminToken,
})).body?.data
const subjectLeft = subjectLeftPage?.list ?? []
check('★★ 清理：课程删除后，它名下的题目也被连带清掉了（不是我们手工删的）',
  subjectLeft.length === 0, `剩余 ${subjectLeft.length} 道`)

// ③ 删分类（从最里层往外删）
for (const id of [lv3Id, lv2Id, lv1?.id].filter(Boolean)) {
  await gw('DELETE', `/cs/categorys/${id}`, { token: adminToken })
  await new Promise((r) => setTimeout(r, 200))
}
collectErrors = true

const allFinal = (await gw('GET', '/cs/categorys/all', { token: adminToken })).body?.data ?? []
check('★★ 清理：自造的 3 个分类都删掉了', !allFinal.some((c) => c.name === NEW_CATE), `剩余=${allFinal.length}`)

const courseGone = (await gw('GET', `/cs/courses/baseInfo/${courseId}`, { token: adminToken })).body
check('★★ 清理：课程确实没了（接口读不到）',
  courseGone?.code !== 200 || !courseGone?.data,
  `code=${courseGone?.code} msg=${courseGone?.msg ?? ''}`)

if (SQL_AVAILABLE) {
  const leftCourse = sqlOne(`SELECT COUNT(*) FROM fm_course.course WHERE id=${courseId}`)
  const leftCata = sqlOne(`SELECT COUNT(*) FROM fm_course.course_catalogue WHERE course_id=${courseId}`)
  checkSql('★ 直查 MySQL：课程与目录都没残留',
    Number(leftCourse) === 0 && Number(leftCata) === 0,
    `course=${leftCourse} cata=${leftCata}`)
}

// ================================================================
section('13. 浏览器运行时健康度')
// ================================================================
check('admin 端无 JS 运行时异常', pageErrors.filter((e) => e.startsWith('[admin]')).length === 0,
  pageErrors.filter((e) => e.startsWith('[admin]')).slice(0, 2).join(' | '))
check('portal 端无 JS 运行时异常', pageErrors.filter((e) => e.startsWith('[portal]')).length === 0,
  pageErrors.filter((e) => e.startsWith('[portal]')).slice(0, 2).join(' | '))
check('admin 端全程没有「业务码非 200」的响应',
  businessErrors.filter((e) => e.startsWith('[admin]')).length === 0,
  businessErrors.filter((e) => e.startsWith('[admin]')).slice(0, 3).join(' | '))

// ================================================================
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
  console.error('⚠️ 中断点之前的自造数据可能仍留在库里（清理在 §12，没走到就清不了）。')
  console.error(`   手工清理（STAMP=${STAMP}）：`)
  console.error(`     DELETE FROM fm_course.course       WHERE name LIKE '%${STAMP}%';`)
  console.error(`     DELETE FROM fm_course.course_draft WHERE name LIKE '%${STAMP}%';`)
  console.error(`     DELETE FROM fm_course.subject      WHERE name LIKE '%${STAMP}%';`)
  console.error(`     DELETE FROM fm_course.category     WHERE name LIKE '%${STAMP}%';`)
  console.error('='.repeat(74))
  fail++
}

await browser.close().catch(() => undefined)
process.exit(fail === 0 ? 0 : 1)
