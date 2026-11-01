#!/usr/bin/env node
/**
 * ============================================================================
 * M11-2 / P0 验收：计划模块（训练主线）
 * ============================================================================
 * 覆盖（一一对应《M11-2 计划模块开发方案》的验收口径）：
 *   1. 建计划 → 加 5 个训练日        → 直查 plan_day 真有 5 行
 *   2. 加 4 个动作                   → 直查 plan_day_item 真有 4 行且 sort 连续 1..4
 *   3. 空训练日执行                  → 被拒：code=400 + 文案点名是哪一天没动作
 *   4. 执行本计划连点两次            → 直查 user_plan 只有 1 行，且两次返回同一 id
 *   5. 开始训练连点两次              → 直查 workout_session 只有 1 行
 *   6. 记 3 组（第 3 组提交两次）    → 直查 workout_set_log 只有 3 行（覆盖而非累加）
 *   7. 完成训练连点两次              → status=1 且 duration_sec>0、set_log 仍 3 行
 *   8. 回首页                        → 今日/累计分钟数 = 库里 duration_sec 换算值
 *   9. 刷新执行页                    → 已打勾的组还在（每组都落库了）
 *
 * ★★ 本脚本的两条写法纪律（都是踩过坑总结的）：
 *
 *   ① **所有断言按本次新建的 planId / planDayId 限定范围**，绝不写
 *      `SELECT COUNT(*) FROM user_plan`。库里有历史数据，全表 count 第二次跑就假红；
 *      而"为了绿去清表"会让断言失去意义。
 *
 *   ② **查询失败必须抛，不能当成"空"**。mysql 挂了返回空串，断言 `=== '0'` 会失败，
 *      但信息是"数据不对"，真实原因是"数据库连不上" —— 所以 checkCmdResult 一律 throw。
 *
 * 前置：
 *   · 网关模式（默认）：fm-gateway(10010) + fm-plan(8095) + Nacos 都在跑
 *   · 离线模式：`FM_DIRECT=1 node scripts/accept-plan-p0.mjs`
 *     直连 8095，用 `user-info: 1` 模拟网关透传（本机 Docker 没起时用这个）
 *
 * 用法：node scripts/accept-plan-p0.mjs
 * ============================================================================
 */
import { spawnSync } from 'node:child_process'

const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const STUDENT_PHONE = '13800010001'
const STUDENT_PASSWORD = '123456'
const STUDENT_USER_ID = 1

const DIRECT = process.env.FM_DIRECT === '1'
const DIRECT_BASE = 'http://127.0.0.1:8095'
const GATEWAY_BASE = 'http://localhost:10010'
/** 网关模式要带 /pls 前缀（StripPrefix=1 会剥掉）；直连服务时没有这层前缀 */
const PREFIX = DIRECT ? '' : '/pls'
const BASE = DIRECT ? DIRECT_BASE : GATEWAY_BASE

let passed = 0
let failed = 0
const failures = []

function check(name, condition, detail = '') {
  if (condition) {
    passed++
    console.log(`  ✅ ${name}`)
  } else {
    failed++
    failures.push(name + (detail ? ` —— ${detail}` : ''))
    console.log(`  ❌ ${name}${detail ? ` —— ${detail}` : ''}`)
  }
}

function section(title) {
  console.log(`\n【${title}】`)
}

/** ⚠️ stdio 的 stdin 必须是 ignore（本机默认 pipe 会 EBUSY，而且不抛异常） */
function sql(query) {
  const r = spawnSync(MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (r.error) {
    throw new Error(`SQL 无法启动：${query}\n${r.error.message}`)
  }
  if (r.status !== 0) {
    throw new Error(`SQL 执行失败：${query}\n${(r.stderr || '').trim()}`)
  }
  return (r.stdout || '').trim()
}

let token = null

async function call(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  if (DIRECT) {
    // 模拟网关：透传用户 + 标记来源（否则 fm-common 不会把返回值包成 R）
    headers['user-info'] = String(STUDENT_USER_ID)
    headers['request-from'] = 'gateway'
  } else if (token) {
    headers.Authorization = token
  }
  const res = await fetch(BASE + PREFIX + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: res.status, json, text }
}

async function login() {
  if (DIRECT) {
    console.log('（离线模式：直连 8095，用 user-info 头模拟网关，跳过登录）')
    return
  }
  const r = await call('POST', '/as/accounts/login', {
    body: { type: 1, cellPhone: STUDENT_PHONE, password: STUDENT_PASSWORD, rememberMe: false },
  })
  const data = r.json?.data
  if (!data) {
    throw new Error(`登录失败：HTTP ${r.status} ${r.text.slice(0, 200)}`)
  }
  token = data
}

/** 今天的星期（1=周一 … 7=周日），与 plan_day.weekday 同口径 */
function todayWeekday() {
  const d = new Date().getDay()
  return d === 0 ? 7 : d
}

/** 取 n 个连续的星期（从今天开始往后数），保证「今日训练」一定能命中其中一天 */
function weekdaysFromToday(n) {
  const start = todayWeekday()
  return Array.from({ length: n }, (_, i) => ((start - 1 + i) % 7) + 1)
}

async function main() {
  console.log('='.repeat(78))
  console.log(`M11-2 / P0 验收：计划模块（${DIRECT ? '离线直连 8095' : '经网关 10010'}）`)
  console.log('='.repeat(78))

  await login()

  // 每次跑用唯一标题，避免与历史数据纠缠（断言也全部按 id 限定）
  const stamp = new Date().toISOString().replace(/[-:T.Z]/g, '').slice(0, 14)
  const title = `验收-周一练胸-${stamp}`

  // ==================== 1. 建计划 + 加 5 个训练日 ====================
  section('1. 建计划 → 加 5 个训练日')

  const created = await call('POST', '/plans', { title, goal: '增肌', difficulty: 0 })
  const planId = String(created.json?.data ?? '')
  check('创建计划返回 planId', created.json?.code === 200 && !!planId, `HTTP ${created.status} ${created.text.slice(0, 120)}`)
  if (!planId) {
    throw new Error('没有 planId，后续无法继续')
  }
  const planRow = sql(`SELECT CONCAT(title,'|',source,'|',owner_id) FROM fm_plan.plan WHERE id=${planId}`)
  check('库里 plan 有这一行且 source=1(自建) / owner=1',
    planRow === `${title}|1|${STUDENT_USER_ID}`, `实际 ${planRow}`)

  const weekdays = weekdaysFromToday(5)
  const addDays = await call('POST', `/plans/${planId}/days`, { weekdays })
  check('批量加 5 个训练日返回成功', addDays.json?.code === 200, JSON.stringify(addDays.json))

  const dayCount = sql(`SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id=${planId}`)
  check('★ 直查 plan_day 真有 5 行', dayCount === '5', `实际 ${dayCount} 行`)

  const dayIndexes = sql(`SELECT GROUP_CONCAT(day_index ORDER BY day_index) FROM fm_plan.plan_day WHERE plan_id=${planId}`)
  check('day_index 连续 1..5', dayIndexes === '1,2,3,4,5', `实际 ${dayIndexes}`)

  const weekly = sql(`SELECT train_days_per_week FROM fm_plan.plan WHERE id=${planId}`)
  check('plan.train_days_per_week 回填为 5', weekly === '5', `实际 ${weekly}`)

  // 重复提交同一批星期：应被跳过，不产生第 6 行
  await call('POST', `/plans/${planId}/days`, { weekdays })
  const dayCount2 = sql(`SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id=${planId}`)
  check('同批星期再提交一次，plan_day 仍是 5 行（不会翻倍）', dayCount2 === '5', `实际 ${dayCount2} 行`)

  // ==================== 2. 重命名 + 加 4 个动作 ====================
  section('2. 训练日重命名 → 加 4 个动作')

  const dayId = sql(`SELECT id FROM fm_plan.plan_day WHERE plan_id=${planId} AND weekday=${weekdays[0]}`)
  const rename = await call('PUT', `/plans/days/${dayId}`, { name: '周一练胸' })
  check('训练日重命名返回成功', rename.json?.code === 200, JSON.stringify(rename.json))
  check('库里名字已变成「周一练胸」', sql(`SELECT name FROM fm_plan.plan_day WHERE id=${dayId}`) === '周一练胸')

  // 1=中握距卧推 2=俯卧撑 5=杠铃深蹲 16=平板支撑（都是 sql/fm_plan.sql 的种子动作）
  const addItems = await call('POST', `/plans/days/${dayId}/items`, { exerciseIds: [1, 2, 5, 16] })
  check('批量加 4 个动作返回成功', addItems.json?.code === 200, JSON.stringify(addItems.json))

  const itemCount = sql(`SELECT COUNT(*) FROM fm_plan.plan_day_item WHERE plan_day_id=${dayId}`)
  check('★ 直查 plan_day_item 真有 4 行', itemCount === '4', `实际 ${itemCount} 行`)

  const sorts = sql(`SELECT GROUP_CONCAT(sort ORDER BY sort) FROM fm_plan.plan_day_item WHERE plan_day_id=${dayId}`)
  check('★ sort 连续 1,2,3,4', sorts === '1,2,3,4', `实际 ${sorts}`)

  const est = sql(`SELECT est_minutes FROM fm_plan.plan_day WHERE id=${dayId}`)
  check('est_minutes 已由后端算出（>0）', Number(est) > 0, `实际 ${est}`)

  // 删除第 2 个动作项 → 序号必须重排成 1,2,3
  const item2 = sql(`SELECT id FROM fm_plan.plan_day_item WHERE plan_day_id=${dayId} AND sort=2`)
  const del = await call('DELETE', `/plans/days/${dayId}/items/${item2}`)
  check('删除动作项返回成功', del.json?.code === 200, JSON.stringify(del.json))
  const sortsAfter = sql(`SELECT GROUP_CONCAT(sort ORDER BY sort) FROM fm_plan.plan_day_item WHERE plan_day_id=${dayId}`)
  check('删除后 sort 重排为 1,2,3（不留空洞）', sortsAfter === '1,2,3', `实际 ${sortsAfter}`)

  // 补回一个动作，让这一天有 4 个动作（后面执行要用）
  await call('POST', `/plans/days/${dayId}/items`, { exerciseIds: [4] })

  // ==================== 3. 空训练日执行被拒 ====================
  section('3. 空训练日点「执行本计划」→ 必须被拒（点名是哪一天）')

  const planB = await call('POST', '/plans', { title: `验收-空训练日-${stamp}`, goal: '减脂', difficulty: 0 })
  const planBId = String(planB.json?.data ?? '')
  check('第二个计划创建成功', !!planBId, JSON.stringify(planB.json))

  const wdB = weekdaysFromToday(2)
  await call('POST', `/plans/${planBId}/days`, { weekdays: wdB })
  // 只给第一天加动作，第二天保持空
  const dayB1 = sql(`SELECT id FROM fm_plan.plan_day WHERE plan_id=${planBId} AND weekday=${wdB[0]}`)
  await call('POST', `/plans/days/${dayB1}/items`, { exerciseIds: [1] })

  const execB = await call('POST', `/plans/${planBId}/execute`)
  const emptyDayName = sql(`SELECT name FROM fm_plan.plan_day WHERE plan_id=${planBId} AND weekday=${wdB[1]}`)
  check('★ 空训练日执行被拒：code=400', execB.json?.code === 400, JSON.stringify(execB.json))
  check('★ 文案点名了是哪一天没动作（含训练日名）',
    String(execB.json?.msg || '').includes(emptyDayName) && String(execB.json?.msg || '').includes('还没有动作'),
    `msg=${execB.json?.msg}, 空训练日名=${emptyDayName}`)
  check('被拒后库里没有写出 user_plan',
    sql(`SELECT COUNT(*) FROM fm_plan.user_plan WHERE user_id=${STUDENT_USER_ID} AND plan_id=${planBId}`) === '0')

  // ==================== 3b. 删除训练日 ====================
  section('3b. 删除训练日（P0 补齐）')

  // ★ 必须删**中间**那一天才能验到"重排"：删末尾时剩下的是 1,2 —— 那本来就没洞，
  //   断言"=1,2"必然通过（这就是一条假绿的写法）。
  //   所以先补到 3 天（1,2,3），再删中间的 2 → 第三天必须前移成 2。
  await call('POST', `/plans/${planBId}/days`, { weekdays: [weekdaysFromToday(3)[2]] })
  check('补到 3 个训练日后 day_index = 1,2,3',
    sql(`SELECT GROUP_CONCAT(day_index ORDER BY day_index) FROM fm_plan.plan_day WHERE plan_id=${planBId}`) === '1,2,3',
    `实际 ${sql(`SELECT GROUP_CONCAT(day_index ORDER BY day_index) FROM fm_plan.plan_day WHERE plan_id=${planBId}`)}`)

  const dayB2 = sql(`SELECT id FROM fm_plan.plan_day WHERE plan_id=${planBId} AND weekday=${wdB[1]}`)
  const delDay = await call('DELETE', `/plans/days/${dayB2}`)
  check('删除未练过的训练日返回成功', delDay.json?.code === 200, JSON.stringify(delDay.json))
  check('★ 直查 plan_day 只剩 2 行（动作项也级联删了）',
    sql(`SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id=${planBId}`) === '2',
    `实际 ${sql(`SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id=${planBId}`)} 行`)
  check('★ 删中间那天后 day_index 重排为 1,2（原来的第 3 练前移成第 2 练）',
    sql(`SELECT GROUP_CONCAT(day_index ORDER BY day_index) FROM fm_plan.plan_day WHERE plan_id=${planBId}`) === '1,2',
    `实际 ${sql(`SELECT GROUP_CONCAT(day_index ORDER BY day_index) FROM fm_plan.plan_day WHERE plan_id=${planBId}`)}`)
  check('plan.train_days_per_week 跟着回填成 2',
    sql(`SELECT train_days_per_week FROM fm_plan.plan WHERE id=${planBId}`) === '2',
    `实际 ${sql(`SELECT train_days_per_week FROM fm_plan.plan WHERE id=${planBId}`)}`)

  // ② 已经练过的训练日不允许删（planA 的 dayId 在 §5 练完），断言放在 7b

  // ==================== 4. 执行本计划（连点两次幂等） ====================
  section('4. 执行本计划（连点两次）→ user_plan 只有 1 行')

  // 先把计划 A 其余 4 天也填上动作（否则会被空日拦截，那不是本节要验的）
  const otherDays = sql(`SELECT id FROM fm_plan.plan_day WHERE plan_id=${planId} AND id<>${dayId}`).split('\n').filter(Boolean)
  for (const d of otherDays) {
    await call('POST', `/plans/days/${d}/items`, { exerciseIds: [6] })
  }

  const exec1 = await call('POST', `/plans/${planId}/execute`)
  const exec2 = await call('POST', `/plans/${planId}/execute`)
  check('第一次执行返回 userPlanId', !!exec1.json?.data, JSON.stringify(exec1.json))
  check('第二次执行返回同一个 userPlanId（幂等）',
    String(exec1.json?.data) === String(exec2.json?.data),
    `第一次 ${exec1.json?.data} / 第二次 ${exec2.json?.data}`)

  const userPlanRows = sql(`SELECT COUNT(*) FROM fm_plan.user_plan WHERE user_id=${STUDENT_USER_ID} AND plan_id=${planId}`)
  check('★ 直查 user_plan 只有 1 行', userPlanRows === '1', `实际 ${userPlanRows} 行`)

  // ==================== 5. 今日训练 + 开始训练（连点两次） ====================
  section('5. 今日训练三态 → 开始训练（连点两次）')

  const today = await call('GET', '/plans/today')
  const todayData = today.json?.data || {}
  check('今日训练状态 = 2（有训练）', todayData.status === 2, JSON.stringify(todayData).slice(0, 200))
  check('今日训练带出训练日名与动作数',
    !!todayData.dayName && todayData.exerciseCount > 0,
    `dayName=${todayData.dayName}, exerciseCount=${todayData.exerciseCount}`)
  check('今日训练带出预计分钟（来自后端 est_minutes）', todayData.estMinutes > 0, `estMinutes=${todayData.estMinutes}`)

  const todayDayId = todayData.planDayId
  check('今日训练的训练日就是本次计划的那一天', String(todayDayId) === String(dayId),
    `today=${todayDayId}, 期望=${dayId}`)

  const s1 = await call('POST', '/workouts', { planDayId: todayDayId })
  const s2 = await call('POST', '/workouts', { planDayId: todayDayId })
  const sessionId = String(s1.json?.data ?? '')
  check('开始训练返回 sessionId', !!sessionId, JSON.stringify(s1.json))
  check('连点两次返回同一个 sessionId',
    String(s1.json?.data) === String(s2.json?.data), `第一次 ${s1.json?.data} / 第二次 ${s2.json?.data}`)

  const sessionRows = sql(`SELECT COUNT(*) FROM fm_plan.workout_session
    WHERE user_id=${STUDENT_USER_ID} AND session_date=CURDATE() AND plan_day_id=${todayDayId}`)
  check('★ 直查 workout_session 只有 1 行（同一天同一训练日）', sessionRows === '1', `实际 ${sessionRows} 行`)

  // ==================== 6. 记 3 组（第 3 组重复提交） ====================
  section('6. 记 3 组（第 3 组提交两次）→ 只有 3 行')

  const detail = await call('GET', `/workouts/${sessionId}`)
  const firstItem = detail.json?.data?.items?.[0]
  check('执行页返回动作项', !!firstItem, JSON.stringify(detail.json).slice(0, 200))
  const itemId = firstItem?.itemId
  check('执行页带回组休秒数（倒计时用它，前端不写死 60）', Number(firstItem?.restSec) > 0, `restSec=${firstItem?.restSec}`)

  for (const setNo of [1, 2, 3, 3]) {
    await call('PUT', `/workouts/${sessionId}/sets`, { itemId, setNo, weightKg: 60, reps: 8 })
  }
  const setRows = sql(`SELECT COUNT(*) FROM fm_plan.workout_set_log WHERE session_id=${sessionId}`)
  check('★ 直查 workout_set_log 只有 3 行（第 3 组重复提交是覆盖）', setRows === '3', `实际 ${setRows} 行`)

  const refresh = await call('GET', `/workouts/${sessionId}`)
  const refreshFirst = refresh.json?.data?.items?.[0]
  check('★ 刷新执行页：已打勾的 3 组还在',
    (refreshFirst?.setLogs || []).length === 3, `实际 ${(refreshFirst?.setLogs || []).length} 组`)
  check('doneSets 与库里的组数一致',
    refresh.json?.data?.doneSets === 3, `实际 ${refresh.json?.data?.doneSets}`)

  // ==================== 6b. 取消打勾（删一组 + 组号前移） ====================
  section('6b. 取消打勾（P0 补齐）→ 删第 2 组，第 3 组前移成第 2 组')

  const delSet = await call('DELETE', `/workouts/${sessionId}/items/${itemId}/sets/2`)
  check('取消打勾返回成功', delSet.json?.code === 200, JSON.stringify(delSet.json))
  const setNos = sql(`SELECT GROUP_CONCAT(set_no ORDER BY set_no) FROM fm_plan.workout_set_log WHERE session_id=${sessionId}`)
  check('★ 直查只剩 2 行，且组号重排为 1,2（不留 1,3 空洞）', setNos === '1,2', `实际 ${setNos}`)
  check('★ 删掉的是"第 2 组"这个语义：原第 3 组的重量/次数被前移过来',
    sql(`SELECT CONCAT(set_no,'|',reps) FROM fm_plan.workout_set_log WHERE session_id=${sessionId} AND set_no=2`) === '2|8',
    `实际 ${sql(`SELECT CONCAT(set_no,'|',reps) FROM fm_plan.workout_set_log WHERE session_id=${sessionId} AND set_no=2`)}`)

  // 删一组：删不存在的组要报 400（不能静默成功）
  const delMissing = await call('DELETE', `/workouts/${sessionId}/items/${itemId}/sets/9`)
  check('删不存在的组 → code=400 且有可读文案',
    delMissing.json?.code === 400 && !!delMissing.json?.msg, JSON.stringify(delMissing.json))

  // 补回第 3 组，后面的 total_volume 断言按 3 组算
  await call('PUT', `/workouts/${sessionId}/sets`, { itemId, setNo: 3, weightKg: 60, reps: 8 })
  check('补回后回到 3 组', sql(`SELECT COUNT(*) FROM fm_plan.workout_set_log WHERE session_id=${sessionId}`) === '3')

  // ==================== 7. 完成训练（连点两次） ====================
  section('7. 完成训练（连点两次）→ status=1 且 duration_sec>0')

  const fin1 = await call('POST', `/workouts/${sessionId}/finish`)
  const fin2 = await call('POST', `/workouts/${sessionId}/finish`)
  check('完成训练返回成功', fin1.json?.code === 200, JSON.stringify(fin1.json))
  check('重复完成不报错（幂等）', fin2.json?.code === 200, JSON.stringify(fin2.json))

  const sess = sql(`SELECT CONCAT(status,'|',duration_sec,'|',total_volume,'|',IFNULL(end_time,'')) FROM fm_plan.workout_session WHERE id=${sessionId}`)
  const [status, duration, volume] = sess.split('|')
  check('★ 库里 status=1（已完成）', status === '1', `实际 ${status}`)
  check('★ 库里 duration_sec > 0（秒级跑完也不会是 0，有估算兜底）', Number(duration) > 0, `实际 ${duration}`)
  check('库里 total_volume = 60kg × 8 次 × 3 组 = 1440', Number(volume) === 1440, `实际 ${volume}`)
  check('库里 set_log 仍是 3 行（完成训练不会重写组记录）',
    sql(`SELECT COUNT(*) FROM fm_plan.workout_set_log WHERE session_id=${sessionId}`) === '3')

  // ==================== 7b. 两条「不该允许」的反例 ====================
  section('7b. 反例：练过的训练日删不掉 / 已完成的训练不能改记录')

  const delPracticed = await call('DELETE', `/plans/days/${todayDayId}`)
  check('★ 删"已经练过"的训练日 → code=400', delPracticed.json?.code === 400, JSON.stringify(delPracticed.json))
  check('★ 文案说清了原因（含"练过"与训练日名）',
    String(delPracticed.json?.msg || '').includes('练过') && String(delPracticed.json?.msg || '').includes('周一练胸'),
    `msg=${delPracticed.json?.msg}`)
  check('被拒后 plan_day 没被删掉',
    sql(`SELECT COUNT(*) FROM fm_plan.plan_day WHERE id=${todayDayId}`) === '1')

  const delAfterFinish = await call('DELETE', `/workouts/${sessionId}/items/${itemId}/sets/1`)
  check('已完成训练里取消打勾 → code=400',
    delAfterFinish.json?.code === 400 && String(delAfterFinish.json?.msg || '').includes('已经完成'),
    JSON.stringify(delAfterFinish.json))

  // ==================== 8. 回首页：分钟数 ====================
  section('8. 回首页：今日/累计分钟数 = 库里 duration_sec 换算值')

  const today2 = await call('GET', '/plans/today')
  const t2 = today2.json?.data || {}
  const dbToday = sql(`SELECT COALESCE(SUM(duration_sec),0) FROM fm_plan.workout_session
    WHERE user_id=${STUDENT_USER_ID} AND session_date=CURDATE() AND status=1`)
  const dbTotal = sql(`SELECT COALESCE(SUM(duration_sec),0) FROM fm_plan.workout_session
    WHERE user_id=${STUDENT_USER_ID} AND status=1`)
  const expectToday = Math.round(Number(dbToday) / 60)
  const expectTotal = Math.round(Number(dbTotal) / 60)

  check('★ 今日训练分钟数 = round(库里今日 duration_sec / 60)',
    t2.todayMinutes === expectToday, `接口 ${t2.todayMinutes} / 期望 ${expectToday}（${dbToday}s）`)
  check('★ 累计训练分钟数 = round(库里累计 duration_sec / 60)',
    t2.totalMinutes === expectTotal, `接口 ${t2.totalMinutes} / 期望 ${expectTotal}（${dbTotal}s）`)
  check('今日训练卡标记这一天已完成', t2.sessionFinished === true, JSON.stringify(t2).slice(0, 160))

  // ==================== 9. 详情页数字来自后端 ====================
  section('9. 计划详情：动作数与预计分钟来自后端（不是前端硬算）')

  const planDetail = await call('GET', `/plans/${planId}`)
  const dayVo = (planDetail.json?.data?.days || []).find((d) => String(d.id) === String(dayId))
  const dbItemCount = sql(`SELECT COUNT(*) FROM fm_plan.plan_day_item WHERE plan_day_id=${dayId}`)
  const dbEst = sql(`SELECT est_minutes FROM fm_plan.plan_day WHERE id=${dayId}`)
  check('详情动作数 = 库里 item 行数', String(dayVo?.itemCount) === dbItemCount,
    `接口 ${dayVo?.itemCount} / 库 ${dbItemCount}`)
  check('详情预计分钟 = 库里 est_minutes', String(dayVo?.estMinutes) === dbEst,
    `接口 ${dayVo?.estMinutes} / 库 ${dbEst}`)
  check('详情 summary 就是「N个动作·预计M分钟」',
    dayVo?.summary === `${dbItemCount}个动作·预计${dbEst}分钟`, `实际 ${dayVo?.summary}`)

  check('其余训练日也带动作数与预计分钟',
    (planDetail.json?.data?.days || []).every((d) => Number(d.itemCount) >= 1),
    JSON.stringify((planDetail.json?.data?.days || []).map((d) => d.itemCount)))

  // ==================== 10. 计划完成态（P0 补齐） ====================
  section('10. 计划完成态：只剩一个训练日，练完 → user_plan.status=1')

  const planC = await call('POST', '/plans', { title: `验收-完成态-${stamp}`, goal: '增肌', difficulty: 0 })
  const planCId = String(planC.json?.data ?? '')
  await call('POST', `/plans/${planCId}/days`, { weekdays: [todayWeekday()] })
  const dayC = sql(`SELECT id FROM fm_plan.plan_day WHERE plan_id=${planCId}`)
  await call('POST', `/plans/days/${dayC}/items`, { exerciseIds: [1] })
  const execC = await call('POST', `/plans/${planCId}/execute`)
  const userPlanCId = String(execC.json?.data ?? '')
  check('计划C（1 个训练日）执行成功', !!userPlanCId, JSON.stringify(execC.json))

  const startC = await call('POST', '/workouts', { planDayId: dayC })
  const sessionC = String(startC.json?.data ?? '')
  const detailC = await call('GET', `/workouts/${sessionC}`)
  const itemC = detailC.json?.data?.items?.[0]?.itemId
  check('计划C 能开始训练并拿到动作项', !!itemC, JSON.stringify(detailC.json).slice(0, 160))
  await call('PUT', `/workouts/${sessionC}/sets`, { itemId: itemC, setNo: 1, weightKg: 40, reps: 10 })
  await call('POST', `/workouts/${sessionC}/finish`)

  check('★ 唯一的训练日练完后，user_plan.status=1（已完成）',
    sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanCId}`) === '1',
    `实际 ${sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanCId}`)}`)

  const mine = await call('GET', '/plans/mine')
  const mineC = (mine.json?.data || []).find((m) => String(m.id) === planCId)
  check('「我的计划」里这个计划显示「已完成」', mineC?.statusLabel === '已完成', `实际 ${mineC?.statusLabel}`)
  check('「我的计划」的进度是 1/1 天',
    mineC?.finishedDays === 1 && mineC?.dayCount === 1,
    `实际 ${mineC?.finishedDays}/${mineC?.dayCount}`)

  // 对照：5 天只练了 1 天 → 必须仍是"进行中"（不然上面那条断言就是"只要练一次就算完成"的假绿）
  check('（对照）planA 只练了 5 天里的 1 天 → 仍是进行中（status=0）',
    sql(`SELECT status FROM fm_plan.user_plan WHERE user_id=${STUDENT_USER_ID} AND plan_id=${planId}`) === '0',
    `实际 ${sql(`SELECT status FROM fm_plan.user_plan WHERE user_id=${STUDENT_USER_ID} AND plan_id=${planId}`)}`)

  // ==================== 汇总 ====================
  console.log('\n' + '='.repeat(78))
  console.log(`本次创建：planA=${planId}（训练日 ${dayId}）· planB=${planBId} · session=${sessionId}`)
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

main().catch((e) => {
  console.error('\n脚本异常终止：', e)
  process.exit(2)
})
