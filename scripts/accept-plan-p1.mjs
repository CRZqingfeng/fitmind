#!/usr/bin/env node
/**
 * ============================================================================
 * M11-3 / P1 验收：饮食模块
 * ============================================================================
 * 覆盖（一一对应《M11-3 饮食模块开发方案》§10 验收口径）：
 *   1. 记体重 130 斤（65kg）        → 预算与三大营养素自洽（4P+4C+9F == 预算）
 *   2. 记「燕麦 50g」早餐           → diet_log 1 行，kcal = 169（不是 100g 的 338）
 *   3. 同一天再记 50g               → 同一行 amount=100 **且 kcal 也翻倍**（六列一起变）
 *   4. 同一天记「1 碗」             → 另一行（单位不同不合并）
 *   5. 回首页                       → 摄入 +169 / 还可以吃 −169 / 碳水前进，三个数同时变
 *   6. 记 30 分钟慢跑               → sport_log 1 行；**「还可以吃」必须不变**（口径 1）
 *   7. 再记 30 分钟                 → 2 条（运动不合并）
 *   8. 删一条饮食记录               → 三个数同时回退；diet_log 真少一行
 *   9. 改 food 单价                 → 历史 diet_log 不变（快照），finally 还原
 *  10. 体重 130 斤 → 140 斤         → 跨天插新行旧行不动；同一天只留一行；预算重算
 *  11. 饮食计划                     → 「每日六餐」靠 meal_index；三层热量自洽
 *  12. 食物库                       → 分类动态（只返回有数据的）；自定义 / 收藏 / 越权 / 单位校验
 *  13. 匿名访问                     → 401（这条靠 includeLoginPaths）
 *  14. 回归                         → P0 的 8 张表行数与 3 个入口接口没被动过
 *
 * ★★ 四条写法纪律（P0 踩出来的，这里一条不落）：
 *
 *   ① **所有断言按本次的 (user, 日期) / id 限定范围**，绝不 `SELECT COUNT(*) FROM diet_log`。
 *      库里有历史数据（P0 验收、手工冒烟都留了行），全表 count 第二次跑就假红。
 *
 *   ② **日期是"本次独占"的**：脚本启动时在 2016~2020 里挑一个三张日志表都为空的日期。
 *      这样"记 50g → 100g"这类**精确值**断言可以重复跑。
 *      不用今天，是因为今天必然被 UI 冒烟/手工点过，合并语义会把 50 变成 150。
 *
 *   ③ **查询失败必须抛，不能当"空"**。mysql 挂了返回空串，断言 `=== '0'` 会失败，
 *      但信息是"数据不对"，真实原因是"数据库连不上"。
 *
 *   ④ **改数据必须 try/finally 还原**。第 9 节要改 food 单价来验快照，
 *      还原失败会污染后面每一次跑（详情页热量全变），比不跑更糟。
 *
 * 前置：
 *   · 网关模式（默认）：fm-gateway(10010) + fm-plan(8095) + Nacos
 *   · 离线模式：`FM_DIRECT=1 node scripts/accept-plan-p1.mjs`（直连 8095）
 *
 * 用法：node scripts/accept-plan-p1.mjs
 * ============================================================================
 */
import { spawnSync } from 'node:child_process'

const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const STUDENT_PHONE = '13800010001'
const STUDENT_PASSWORD = '123456'
const STUDENT_USER_ID = 1

const DIRECT = process.env.FM_DIRECT === '1'
const BASE = DIRECT ? 'http://127.0.0.1:8095' : 'http://localhost:10010'
const PREFIX = DIRECT ? '' : '/pls'

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

/** ⚠️ stdio 的 stdin 必须是 ignore（本机默认 pipe 会 EBUSY，而且不抛异常） */
function sqlRaw(query) {
  return spawnSync(MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
}

function sql(query) {
  const r = sqlRaw(query)
  if (r.error) throw new Error(`SQL 无法启动：${query}\n${r.error.message}`)
  if (r.status !== 0) throw new Error(`SQL 执行失败：${query}\n${(r.stderr || '').trim()}`)
  return (r.stdout || '').trim()
}

/** 期望失败的 SQL（例如验证唯一索引真能挡住重复行） */
function sqlExpectFail(query) {
  const r = sqlRaw(query)
  return { ok: r.status === 0, err: (r.stderr || '').trim() }
}

let token = null

async function call(method, path, body, extraHeaders = {}) {
  const headers = { 'Content-Type': 'application/json', ...extraHeaders }
  if (DIRECT) {
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
  try { json = JSON.parse(text) } catch { /* 非 JSON（比如 401 的纯文本） */ }
  return { status: res.status, json, text }
}

/** 不带任何身份头的请求：验"匿名必须 401" */
async function callAnon(method, path) {
  const res = await fetch(BASE + PREFIX + path, {
    method,
    headers: DIRECT ? { 'request-from': 'gateway' } : {},
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* ignore */ }
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
  if (!data) throw new Error(`登录失败：HTTP ${r.status} ${r.text.slice(0, 200)}`)
  token = data
}

// ==================== 日期与数值工具 ====================

const pad = (n) => String(n).padStart(2, '0')
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x }

const TODAY = ymd(new Date())

/**
 * 挑一个"本次独占"的过去日期（三张日志表都为空）。
 *
 * ★ 为什么不用今天：今天必然被 UI 冒烟和手工点过，
 *   `50g → 100g` 这种精确断言会变成 `150g` 而假红。
 */
function pickFreeDate(startOffset) {
  const base = new Date(2016, 0, 1)
  for (let i = 0; i < 90; i++) {
    const d = ymd(addDays(base, startOffset + i))
    const used = Number(sql(
      `SELECT (SELECT COUNT(*) FROM fm_plan.diet_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${d}')`
      + ` + (SELECT COUNT(*) FROM fm_plan.sport_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${d}')`
      + ` + (SELECT COUNT(*) FROM fm_plan.user_body_metric WHERE user_id=${STUDENT_USER_ID} AND record_date='${d}')`))
    if (used === 0) return d
  }
  throw new Error('找不到未被占用的测试日期（库里 90 天以内都有数据？）')
}

/** 十分之一单位的整数运算，避免 JS 浮点把 289.25 算成 289.2（Java 用 BigDecimal HALF_UP） */
const r1 = (n) => Math.floor(n + 0.5)

/**
 * 复刻 NutritionCalculator.plan()（口径 2：预算由 macro 反推）。
 *
 * ★ 这里**必须**自己算一遍，不能"拿接口返回的预算当期望值" ——
 *   那样接口算错了脚本也会绿（假绿三形态之"客户端自配期望值"）。
 */
function expectPlan(weightKg, targetKg) {
  const loss = targetKg !== null && targetKg !== undefined && Number(targetKg) < Number(weightKg)
  const w10 = r1(Number(weightKg) * 10)
  const protein10 = loss ? r1(w10 * 16 / 10) : r1(w10 * 20 / 10)
  const fat10 = r1(w10 * 8 / 10)
  const kcal0_10 = w10 * (loss ? 28 : 33)
  const carbKcal10 = Math.max(0, kcal0_10 - protein10 * 4 - fat10 * 9)
  const carb10 = r1(carbKcal10 / 4)
  const total10 = protein10 * 4 + carb10 * 4 + fat10 * 9
  return {
    loss,
    kcalBudget: r1(total10 / 10),
    protein10,
    carb10,
    fat10,
  }
}

/** 与 NutritionCalculator.nutrientOf 同口径：per100g × gram / 100，保留 1 位（HALF_UP） */
function nutrient1(per100g, gram) {
  const p10 = r1(Number(per100g) * 10)
  return r1(p10 * Number(gram) / 100) / 10
}

/** 与 NutritionCalculator.kcalOf 同口径：round(per100g × gram / 100) */
function kcalOf(per100g, gram) {
  return r1(Number(per100g) * Number(gram) / 100)
}

const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v))

async function main() {
  console.log('='.repeat(78))
  console.log(`M11-3 / P1 验收：饮食模块（${DIRECT ? '离线直连 8095' : '经网关 10010'}）`)
  console.log('='.repeat(78))

  await login()

  // ==================== 0. 前置 ====================
  section('0. 前置：服务可用 / 种子数据在')

  const ping = await call('GET', '/diet/today')
  check('服务可达（GET /diet/today 200）', ping.status === 200 && ping.json?.code === 200,
    `HTTP ${ping.status} ${ping.text.slice(0, 120)}`)
  if (ping.status !== 200) {
    throw new Error('服务不可用：先起 fm-plan（离线模式见脚本头注释）')
  }

  const oat = sql("SELECT CONCAT(id,'|',kcal_per_100g,'|',protein_per_100g,'|',carb_per_100g,'|',fat_per_100g) "
    + "FROM fm_plan.food WHERE name='燕麦' LIMIT 1")
  if (!oat) throw new Error('库里没有「燕麦」，先执行 sql/fm_plan.sql')
  const [OAT_ID, OAT_KCAL, OAT_P, OAT_C, OAT_F] = oat.split('|')
  console.log(`  燕麦：id=${OAT_ID} ${OAT_KCAL}kcal/100g P${OAT_P} C${OAT_C} F${OAT_F}`)

  const oatUnits = sql(`SELECT units_json FROM fm_plan.food WHERE id=${OAT_ID}`)
  check('燕麦配了「克」与「碗」两种单位（否则下面「单位不同不合并」验不了）',
    oatUnits.includes('"克"') && oatUnits.includes('"碗"'), oatUnits)

  const jog = sql("SELECT CONCAT(id,'|',kcal_per_30min) FROM fm_plan.sport WHERE name LIKE '慢跑%' LIMIT 1")
  if (!jog) throw new Error('库里没有「慢跑」，先执行 sql/fm_plan.sql')
  const [JOG_ID, JOG_KCAL] = jog.split('|')
  console.log(`  慢跑：id=${JOG_ID} ${JOG_KCAL}kcal/30min`)

  // 数据库里的 P0 表基线（最后要证明一个都没被动）
  const p0Baseline = {
    plan: sql('SELECT COUNT(*) FROM fm_plan.plan'),
    plan_day: sql('SELECT COUNT(*) FROM fm_plan.plan_day'),
    plan_day_item: sql('SELECT COUNT(*) FROM fm_plan.plan_day_item'),
    user_plan: sql('SELECT COUNT(*) FROM fm_plan.user_plan'),
    workout_session: sql('SELECT COUNT(*) FROM fm_plan.workout_session'),
  }
  console.log(`  P0 表基线：${JSON.stringify(p0Baseline)}`)

  // 本次独占的日期
  const offset = Math.floor(Date.now() / 1000) % 1500
  const D = pickFreeDate(offset)
  const D2 = pickFreeDate(offset + 1)
  console.log(`  本次使用测试日期：diet/sport=${D}，第二个体征日=${D2}`)
  check('两个测试日期不重合（否则体征"跨天插新行"验不出来）', D !== D2, `${D} / ${D2}`)

  // ==================== 1. 预算自洽 ====================
  section('1. 记体重 130 斤（65kg）→ 预算与三大营养素自洽')

  const m65 = await call('POST', '/body/metrics', { heightCm: 180, weightKg: 65, targetWeightKg: 60 })
  const m65d = m65.json?.data
  check('记体重成功', m65.json?.code === 200 && !!m65d, m65.text.slice(0, 140))
  check('BMI = 20.1（65kg / 1.8m²，实读数据库同口径）',
    Math.abs(num(m65d?.bmi) - 20.1) < 0.05, String(m65d?.bmi))

  const exp65 = expectPlan(65, 60)
  check('目标体重 < 当前体重 → 走减脂口径（蛋白 1.6g/kg）',
    exp65.loss && num(m65d?.proteinG) === exp65.protein10 / 10,
    `接口 ${m65d?.proteinG} / 期望 ${exp65.protein10 / 10}`)
  check('预算 == 公式算出来的值（不是随手拍一个数）',
    num(m65d?.kcalBudget) === exp65.kcalBudget, `接口 ${m65d?.kcalBudget} / 期望 ${exp65.kcalBudget}`)

  const dbMetric = sql(`SELECT CONCAT(weight_kg,'|',kcal_budget,'|',protein_g,'|',carb_g,'|',fat_g) `
    + `FROM fm_plan.user_body_metric WHERE user_id=${STUDENT_USER_ID} AND record_date='${TODAY}'`)
  const [w65, b65, p65, c65, f65] = dbMetric.split('|')
  console.log(`  库里今天这行：${dbMetric}`)

  const sum65 = r1((Number(p65) * 4 + Number(c65) * 4 + Number(f65) * 9) * 10) / 10
  check('★ 库里 4P+4C+9F == kcal_budget（预算与 macro 是同一件事的两种表述）',
    Math.abs(sum65 - Number(b65)) < 0.05, `${sum65} vs ${b65}`)

  const today65 = (await call('GET', '/diet/today')).json?.data
  check('首页预算 = 库里 kcal_budget', num(today65?.budgetKcal) === Number(b65),
    `接口 ${today65?.budgetKcal} / 库 ${b65}`)
  const macroTargets = (today65?.macros || []).map((m) => `${m.key}:${m.target}`)
  check('★ 三大营养素目标来自体征（不是 0/0 的假环）',
    num(today65?.macros?.find((m) => m.key === 'carb')?.target) === Number(c65)
    && num(today65?.macros?.find((m) => m.key === 'protein')?.target) === Number(p65)
    && num(today65?.macros?.find((m) => m.key === 'fat')?.target) === Number(f65),
    macroTargets.join(' '))
  check('「还可以吃」= 预算 − 摄入（读时算，接口自己给）',
    num(today65?.remainKcal) === num(today65?.budgetKcal) - num(today65?.intakeKcal),
    `${today65?.remainKcal} = ${today65?.budgetKcal} − ${today65?.intakeKcal}`)
  const remainCol = sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='fm_plan' "
    + "AND TABLE_NAME='diet_log' AND COLUMN_NAME LIKE '%remain%'")
  check('★ diet_log 里没有 remain 列（"还可以吃"不落库，口径 4）', remainCol === '0', `匹配 ${remainCol} 列`)

  // ==================== 2. 记一份燕麦 50g ====================
  section(`2. 记「燕麦 50g」早餐（日期 ${D}）`)

  const before = (await call('GET', `/diet/today?date=${D}`)).json?.data
  const beforeIntake = num(before?.intakeKcal)
  const beforeRemain = num(before?.remainKcal)
  const beforeCarb = num(before?.macros?.find((m) => m.key === 'carb')?.current)
  check('测试日初始为空（摄入 0）', beforeIntake === 0, String(beforeIntake))

  const log1 = await call('POST', '/diet/logs', { date: D, mealType: 1, foodId: Number(OAT_ID), amount: 50, unit: '克' })
  const log1d = log1.json?.data
  check('记餐成功', log1.json?.code === 200 && !!log1d, log1.text.slice(0, 140))

  const expKcal50 = kcalOf(OAT_KCAL, 50)
  check('★ 热量按 50g 折算（169），不是 100g 的单价 338',
    num(log1d?.kcal) === expKcal50 && expKcal50 !== Number(OAT_KCAL),
    `接口 ${log1d?.kcal} / 期望 ${expKcal50}，单价 ${OAT_KCAL}`)
  check('折算克数 = 50', num(log1d?.gram) === 50, String(log1d?.gram))
  check('第一次不是合并（merged=false）', log1d?.merged === false, String(log1d?.merged))

  const rowWhere = `user_id=${STUDENT_USER_ID} AND log_date='${D}' AND meal_type=1 AND food_id=${OAT_ID} AND unit='克'`
  check('★ 直查 diet_log：该 (用户,日期,餐次,食物,单位) 只有 1 行',
    sql(`SELECT COUNT(*) FROM fm_plan.diet_log WHERE ${rowWhere}`) === '1')
  const row1 = sql(`SELECT CONCAT(amount,'|',gram,'|',kcal,'|',protein_g,'|',carb_g,'|',fat_g) FROM fm_plan.diet_log WHERE ${rowWhere}`)
  const [a1, g1, k1, p1, c1, f1] = row1.split('|')
  check('库里 amount/gram/kcal = 50/50/169', a1 === '50.0' || a1 === '50', `${a1}|${g1}|${k1}`)
  check('库里 kcal ≠ 单价（防止"存的是每 100g 价"）', Number(k1) === expKcal50, k1)
  check('★ 库里三大营养素也按 50g 折算（快照四件套齐全）',
    Number(p1) === nutrient1(OAT_P, 50) && Number(c1) === nutrient1(OAT_C, 50) && Number(f1) === nutrient1(OAT_F, 50),
    `P${p1} C${c1} F${f1} / 期望 P${nutrient1(OAT_P, 50)} C${nutrient1(OAT_C, 50)} F${nutrient1(OAT_F, 50)}`)

  // ==================== 3. 再记 50g → 合并 ====================
  section('3. 同一天再记 50g → 同一行，六列一起翻倍')

  const log2 = await call('POST', '/diet/logs', { date: D, mealType: 1, foodId: Number(OAT_ID), amount: 50, unit: '克' })
  const log2d = log2.json?.data
  check('第二次返回 merged=true', log2d?.merged === true, JSON.stringify(log2d))
  check('第二次回传总量 100（让用户看见"已累加"）', num(log2d?.totalAmount) === 100, String(log2d?.totalAmount))
  check('文案说清累加结果（"已累加为 100克"）', String(log2d?.message || '').includes('累加'),
    String(log2d?.message))

  check('★ 合并靠唯一索引，不是插新行：仍然只有 1 行',
    sql(`SELECT COUNT(*) FROM fm_plan.diet_log WHERE ${rowWhere}`) === '1')
  const row2 = sql(`SELECT CONCAT(amount,'|',gram,'|',kcal,'|',protein_g,'|',carb_g,'|',fat_g) FROM fm_plan.diet_log WHERE ${rowWhere}`)
  const [a2, g2, k2, p2, c2, f2] = row2.split('|')
  check('★ amount 变成 100', Number(a2) === 100, a2)
  check('★★ kcal 也翻倍 = 338（只翻 amount 不翻 kcal 是最典型的错法）',
    Number(k2) === expKcal50 * 2, `${k2}（期望 ${expKcal50 * 2}）`)
  check('gram 也翻倍 = 100', Number(g2) === 100, g2)
  // ★ 为什么允许 ±0.15 的偏差：合并是"每份的折算值累加"（口径 §3.3 的 SQL 就是这么写的），
  //   50g 的蛋白 10.1×0.5=5.05 先四舍五入成 5.1，两份就是 10.2；而按 100g 一次算出来是 10.1。
  //   这个漂移是**刻意接受的**：改成"按当前单价 × 总克数重算"会让食物库调价时
  //   把当天早先那几份的历史值一起改写 —— 那就破坏了 diet_log 的快照语义。
  //   kcal 因为单价是整数、50g 正好整除，所以能吃精确值（下面单独断言）。
  check('★★ 蛋白/碳水/脂肪三列同样翻倍（六列一起重算，允许 ≤0.1 的舍入漂移）',
    Math.abs(Number(p2) - nutrient1(OAT_P, 100)) <= 0.15
    && Math.abs(Number(p1) - nutrient1(OAT_P, 50)) <= 0.15
    && Number(p2) > Number(p1),
    `P ${p1}→${p2}（按 100g 一次算应为 ${nutrient1(OAT_P, 100)}）`)
  check('碳水/脂肪列同样按 100g 口径（漂移同理）',
    Math.abs(Number(c2) - nutrient1(OAT_C, 100)) <= 0.15
    && Math.abs(Number(f2) - nutrient1(OAT_F, 100)) <= 0.15,
    `C ${c2}（期望 ${nutrient1(OAT_C, 100)}）F ${f2}（期望 ${nutrient1(OAT_F, 100)}）`)

  // ==================== 4. 记 1 碗 → 另一行 ====================
  section('4. 同一天记「1 碗」→ 单位不同，另起一行')

  const log3 = await call('POST', '/diet/logs', { date: D, mealType: 1, foodId: Number(OAT_ID), amount: 1, unit: '碗' })
  const log3d = log3.json?.data
  check('1 碗记成功且不是合并', log3.json?.code === 200 && log3d?.merged === false, JSON.stringify(log3d))
  check('1 碗折算成 40g（units_json 里 碗=40）', num(log3d?.gram) === 40, String(log3d?.gram))
  check('1 碗热量 = 135（338×0.4 四舍五入）', num(log3d?.kcal) === kcalOf(OAT_KCAL, 40),
    `${log3d?.kcal} / 期望 ${kcalOf(OAT_KCAL, 40)}`)
  check('★ 该餐次共 2 行（克 / 碗 各一行，单位不同不合并）',
    sql(`SELECT COUNT(*) FROM fm_plan.diet_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${D}' AND meal_type=1 AND food_id=${OAT_ID}`) === '2')

  // ==================== 5. 首页三个数字同时变 ====================
  section('5. 回首页：摄入 / 还可以吃 / 碳水 三个数同时变')

  const after = (await call('GET', `/diet/today?date=${D}`)).json?.data
  const afterIntake = num(after?.intakeKcal)
  const afterRemain = num(after?.remainKcal)
  const afterCarb = num(after?.macros?.find((m) => m.key === 'carb')?.current)

  const delta = expKcal50 * 2 + kcalOf(OAT_KCAL, 40)
  check('摄入 = 169+169+135 = 473（两行记录合计）', afterIntake === delta, `${afterIntake} / 期望 ${delta}`)
  check('★ 还可以吃 = 预算 − 473（不是原地不动，也不是减了两遍）',
    afterRemain === beforeRemain - delta, `${afterRemain} / 期望 ${beforeRemain - delta}`)
  check('★ 碳水进度前进 = 按 100g + 40g 折算',
    Math.abs(afterCarb - (beforeCarb + nutrient1(OAT_C, 100) + nutrient1(OAT_C, 40))) < 0.05,
    `${afterCarb} / 期望 ${beforeCarb + nutrient1(OAT_C, 100) + nutrient1(OAT_C, 40)}`)
  check('整体等式仍成立：remain == budget − intake',
    afterRemain === num(after?.budgetKcal) - afterIntake, `${afterRemain} vs ${after?.budgetKcal}−${afterIntake}`)

  const breakfast = (after?.meals || []).find((m) => m.mealType === 1)
  check('早餐卡合计 = 473 千卡', num(breakfast?.kcal) === delta, String(breakfast?.kcal))
  check('早餐卡有 2 条明细（克 / 碗）', (breakfast?.items || []).length === 2,
    JSON.stringify((breakfast?.items || []).map((i) => `${i.amount}${i.unit}`)))
  check('明细里带 foodName 快照（不是只存 id）',
    (breakfast?.items || []).every((i) => i.foodName === '燕麦'),
    JSON.stringify((breakfast?.items || []).map((i) => i.foodName)))

  // ==================== 6. 记运动：不抵扣 ====================
  section('6. 记 30 分钟慢跑 → 有记录，但「还可以吃」必须不变')

  const s1 = await call('POST', '/sport/logs', { date: D, sportId: Number(JOG_ID), durationMin: 30 })
  check('记运动成功', s1.json?.code === 200 && !!s1.json?.data, s1.text.slice(0, 140))
  check('30 分钟消耗 = kcal_per_30min（260）', num(s1.json?.data?.kcal) === Number(JOG_KCAL),
    `${s1.json?.data?.kcal} / 期望 ${JOG_KCAL}`)

  const afterSport = (await call('GET', `/diet/today?date=${D}`)).json?.data
  check('sport_log 真有 1 行',
    sql(`SELECT COUNT(*) FROM fm_plan.sport_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${D}'`) === '1')
  check('★ 首页显示运动消耗 260', num(afterSport?.sportKcal) === Number(JOG_KCAL), String(afterSport?.sportKcal))
  check('★★ 记完运动「还可以吃」一点都没变（口径 1：不抵扣预算）',
    num(afterSport?.remainKcal) === afterRemain, `${afterSport?.remainKcal} vs 运动前 ${afterRemain}`)
  check('★ 文案说明"不计入预算"（否则用户以为功能坏了）',
    String(afterSport?.sportNote || '').includes('不计入预算'), String(afterSport?.sportNote))

  const s2 = await call('POST', '/sport/logs', { date: D, sportId: Number(JOG_ID), durationMin: 30 })
  check('再记 30 分钟：运动不合并 → 2 行',
    s2.json?.code === 200
    && sql(`SELECT COUNT(*) FROM fm_plan.sport_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${D}'`) === '2')
  const afterSport2 = (await call('GET', `/diet/today?date=${D}`)).json?.data
  check('运动消耗累加到 520，但「还可以吃」依然不变',
    num(afterSport2?.sportKcal) === Number(JOG_KCAL) * 2 && num(afterSport2?.remainKcal) === afterRemain,
    `sport ${afterSport2?.sportKcal} / remain ${afterSport2?.remainKcal}`)

  // ==================== 7. 删一条饮食记录 ====================
  section('7. 删一条饮食记录 → 三个数字同时回退')

  const bowlId = sql(`SELECT id FROM fm_plan.diet_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${D}' `
    + `AND meal_type=1 AND food_id=${OAT_ID} AND unit='碗'`)
  const del = await call('DELETE', `/diet/logs/${bowlId}`)
  check('删除成功', del.json?.code === 200, del.text.slice(0, 120))
  check('★ 库里真少一行（碗那行没了，克那行还在）',
    sql(`SELECT COUNT(*) FROM fm_plan.diet_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${D}'`) === '1'
    && sql(`SELECT COUNT(*) FROM fm_plan.diet_log WHERE id=${bowlId}`) === '0')

  const afterDel = (await call('GET', `/diet/today?date=${D}`)).json?.data
  check('★ 摄入回退到 338', num(afterDel?.intakeKcal) === expKcal50 * 2, String(afterDel?.intakeKcal))
  check('★ 还可以吃回退（= 运动前 + 135）',
    num(afterDel?.remainKcal) === afterRemain + kcalOf(OAT_KCAL, 40),
    `${afterDel?.remainKcal} / 期望 ${afterRemain + kcalOf(OAT_KCAL, 40)}`)
  check('★ 碳水进度回退', Math.abs(num(afterDel?.macros?.find((m) => m.key === 'carb')?.current)
    - (beforeCarb + nutrient1(OAT_C, 100))) < 0.05,
  String(afterDel?.macros?.find((m) => m.key === 'carb')?.current))
  check('删完等式仍成立（remain == budget − intake）',
    num(afterDel?.remainKcal) === num(afterDel?.budgetKcal) - num(afterDel?.intakeKcal))

  const delAgain = await call('DELETE', `/diet/logs/${bowlId}`)
  check('删同一条两次：第二次给明确文案（不是 500）',
    delAgain.json?.code === 400 && String(delAgain.json?.msg || '').includes('不在了'),
    `${delAgain.json?.code} ${delAgain.json?.msg}`)

  // 越权：必须造探针（"库里全是我的" → 看不到别人的，是必然通过的假验证）
  sql('INSERT INTO fm_plan.diet_log (user_id, log_date, meal_type, food_id, food_name, amount, unit, gram, kcal, '
    + `protein_g, carb_g, fat_g) VALUES (999, '${D}', 1, ${OAT_ID}, '探针-别人的记录', 50, '克', 50, 169, 5.1, 38.7, 0.1)`)
  const probeId = sql(`SELECT id FROM fm_plan.diet_log WHERE user_id=999 AND log_date='${D}' LIMIT 1`)
  const probeDel = await call('DELETE', `/diet/logs/${probeId}`)
  check('★ 删别人的记录被拒（403 + 「这不是你的饮食记录」）',
    probeDel.json?.code === 403 && String(probeDel.json?.msg || '').includes('不是你的'),
    `${probeDel.json?.code} ${probeDel.json?.msg}`)
  check('越权删除后探针行仍在库里', sql(`SELECT COUNT(*) FROM fm_plan.diet_log WHERE id=${probeId}`) === '1')
  sql(`DELETE FROM fm_plan.diet_log WHERE id=${probeId}`)

  // ==================== 8. 快照：改食物库不影响历史 ====================
  section('8. 改 food 单价 → 历史记录不变（快照），最后必须还原')

  try {
    sql(`UPDATE fm_plan.food SET kcal_per_100g = kcal_per_100g + 100 WHERE id=${OAT_ID}`)
    check('（前置）单价已改为 ' + (Number(OAT_KCAL) + 100), sql(`SELECT kcal_per_100g FROM fm_plan.food WHERE id=${OAT_ID}`) === String(Number(OAT_KCAL) + 100))

    const logAfterPrice = sql(`SELECT kcal FROM fm_plan.diet_log WHERE ${rowWhere}`)
    check('★ 历史 diet_log.kcal 仍是 338（没有跟着食物库变）', Number(logAfterPrice) === expKcal50 * 2, logAfterPrice)

    const todayAfterPrice = (await call('GET', `/diet/today?date=${D}`)).json?.data
    check('★ 首页摄入仍是 338（快照，不是现算）', num(todayAfterPrice?.intakeKcal) === expKcal50 * 2,
      String(todayAfterPrice?.intakeKcal))

    const newLog = await call('POST', '/diet/logs', { date: D, mealType: 2, foodId: Number(OAT_ID), amount: 50, unit: '克' })
    check('改价后新记的一份按新单价算（438×0.5 = 219，证明改价确实生效）',
      num(newLog.json?.data?.kcal) === kcalOf(Number(OAT_KCAL) + 100, 50),
      `${newLog.json?.data?.kcal} / 期望 ${kcalOf(Number(OAT_KCAL) + 100, 50)}`)
    sql(`DELETE FROM fm_plan.diet_log WHERE user_id=${STUDENT_USER_ID} AND log_date='${D}' AND meal_type=2`)
  } finally {
    sql(`UPDATE fm_plan.food SET kcal_per_100g = ${Number(OAT_KCAL)} WHERE id=${OAT_ID}`)
  }
  check('★ 单价已还原（否则后面每一次跑都是脏的）',
    sql(`SELECT kcal_per_100g FROM fm_plan.food WHERE id=${OAT_ID}`) === String(Number(OAT_KCAL)))

  // ==================== 9. 体重：跨天插新行 / 同天只留一行 ====================
  section('9. 体重 130 斤 → 140 斤：跨天插新行（旧行不动），同一天只留一行')

  const mD1 = await call('POST', '/body/metrics', { date: D, heightCm: 180, weightKg: 65, targetWeightKg: 60 })
  const mD2 = await call('POST', '/body/metrics', { date: D2, heightCm: 180, weightKg: 70, targetWeightKg: 60 })
  check('两个不同日期各记一次都成功',
    mD1.json?.code === 200 && mD2.json?.code === 200, `${mD1.status}/${mD2.status}`)

  const twoRows = sql(`SELECT COUNT(*) FROM fm_plan.user_body_metric WHERE user_id=${STUDENT_USER_ID} `
    + `AND record_date IN ('${D}','${D2}')`)
  check('★ user_body_metric 真有 2 行（体重是时序）', twoRows === '2', `实际 ${twoRows} 行`)

  const d1Row = sql(`SELECT CONCAT(weight_kg,'|',kcal_budget) FROM fm_plan.user_body_metric `
    + `WHERE user_id=${STUDENT_USER_ID} AND record_date='${D}'`)
  const d2Row = sql(`SELECT CONCAT(weight_kg,'|',kcal_budget) FROM fm_plan.user_body_metric `
    + `WHERE user_id=${STUDENT_USER_ID} AND record_date='${D2}'`)
  const exp70 = expectPlan(70, 60)
  check('★ 旧行（65kg）没被改动', d1Row.split('|')[0] === '65.0' && Number(d1Row.split('|')[1]) === exp65.kcalBudget,
    d1Row)
  check('新行（70kg）预算按新体重重算', d2Row.split('|')[0] === '70.0' && Number(d2Row.split('|')[1]) === exp70.kcalBudget,
    `${d2Row} / 期望 ${exp70.kcalBudget}`)

  const latest = (await call('GET', '/body/metrics/latest')).json?.data
  check('★ 「当前体重」取 record_date 最新那条（不是最后插入的那条）',
    latest?.recordDate === TODAY && num(latest?.weightKg) === 65,
    `${latest?.recordDate} ${latest?.weightKg}kg`)

  // 同一天改体重：唯一索引 uk_body_user_date 决定了只能更新当天那行
  const m70 = await call('POST', '/body/metrics', { weightKg: 70 })
  check('同一天改成 70kg 成功（不是 500）', m70.json?.code === 200, m70.text.slice(0, 120))
  check('★ 同一天仍是 1 行（uk_body_user_date 保留当天唯一，不重复插）',
    sql(`SELECT COUNT(*) FROM fm_plan.user_body_metric WHERE user_id=${STUDENT_USER_ID} AND record_date='${TODAY}'`) === '1')
  check('预算按 70kg 重算（1820 → 1960）', num(m70.json?.data?.kcalBudget) === exp70.kcalBudget,
    `${m70.json?.data?.kcalBudget} / 期望 ${exp70.kcalBudget}`)

  const afterWeight = (await call('GET', `/diet/today?date=${D}`)).json?.data
  check('★ 首页预算跟着变', num(afterWeight?.budgetKcal) === exp70.kcalBudget, String(afterWeight?.budgetKcal))
  check('★ 三大营养素目标也跟着变大（蛋白 104 → 112）',
    num(afterWeight?.macros?.find((m) => m.key === 'protein')?.target) === exp70.protein10 / 10,
    `接口 ${afterWeight?.macros?.find((m) => m.key === 'protein')?.target} / 期望 ${exp70.protein10 / 10}`)
  check('体重变了但摄入不变（摄入是日志，不随体征重算）',
    num(afterWeight?.intakeKcal) === expKcal50 * 2, String(afterWeight?.intakeKcal))

  // 还原到 65kg，让"预算基线"回到 1820（干净收尾）
  await call('POST', '/body/metrics', { weightKg: 65 })
  check('体重记回 65kg → 预算复原到 1820',
    num((await call('GET', '/diet/today')).json?.data?.budgetKcal) === exp65.kcalBudget)

  // ==================== 10. 饮食计划（六餐结构 + 三层热量） ====================
  section('10. 饮食计划：推荐 / 详情 / 「每日六餐」/ 三层热量')

  const rec = await call('GET', '/diet/plans/recommend')
  const recList = rec.json?.data || []
  check('推荐饮食计划 ≥ 3 个', recList.length >= 3, `${recList.length} 个`)
  check('卡片字段够用（title + subtitle + 周期 + 千卡）',
    recList.every((p) => p.title && p.subtitle && p.cycleDays > 0 && p.kcalPerDay > 0),
    JSON.stringify(recList[0] || {}).slice(0, 160))

  const planId = recList[0]?.id
  const detail = await call('GET', `/diet/plans/${planId}?day=1`)
  const detailData = detail.json?.data
  check('详情可查', detail.json?.code === 200 && !!detailData, detail.text.slice(0, 140))
  check('周期文案是「一周期N天」', String(detailData?.cycleText || '').startsWith('一周期'), String(detailData?.cycleText))
  check('返回了全部 5 天（前端要做 第1天~第5天 tab）', (detailData?.days || []).length === 5,
    String((detailData?.days || []).length))

  // ★ 各天不能是"同一天复制 N 份"：详情页有 第1天~第N天 的 tab，
  //   如果 5 天热量完全一样，点哪个 tab 都一样 —— 那不是计划，是复制粘贴。
  const dayTotals = (detailData?.days || []).map((d) => d.kcalTotal)
  check('★ 各天热量不完全相同（不是把同一天复制 5 份）',
    new Set(dayTotals).size > 1, dayTotals.join('/'))

  const day1 = (detailData?.days || [])[0]
  check('★ 第 1 天是「每日六餐」（早/午/晚 + 三次加餐）', (day1?.meals || []).length === 6,
    `${(day1?.meals || []).length} 餐`)
  const snacks = (day1?.meals || []).filter((m) => m.mealType === 4)
  check('★ 三顿加餐靠 meal_index 区分（1/2/3，不是同一条记录）', snacks.length === 3,
    JSON.stringify(snacks.map((m) => m.mealIndex)))
  check('★ 三顿加餐的标题都带序号（加餐① ② ③，第一顿也不例外）',
    snacks.every((m) => /加餐[①②③]/.test(m.label)), JSON.stringify(snacks.map((m) => m.label)))
  check('meal_time 已格式化成 HH:mm（不是 07:30:00）',
    (day1?.meals || []).every((m) => /^\d{2}:\d{2}$/.test(String(m.mealTime || ''))),
    JSON.stringify((day1?.meals || []).map((m) => m.mealTime)))

  const sumMealKcal = (day1?.meals || []).reduce((s, m) => s + num(m.kcal), 0)
  check('★ 第 1 天合计 = Σ 各餐（同一事务算出来的冗余，不是手填）',
    num(day1?.kcalTotal) === sumMealKcal, `${day1?.kcalTotal} vs ${sumMealKcal}`)

  const items = (day1?.meals || []).flatMap((m) => m.items || [])
  check('食物项总数 > 0', items.length > 0, `${items.length} 项`)
  const itemKcalOk = (day1?.meals || []).every((m) =>
    num(m.kcal) === (m.items || []).reduce((s, i) => s + num(i.kcal), 0))
  check('★ 每餐 kcal = Σ 该餐食物项 kcal', itemKcalOk)

  const priceMap = new Map(
    sql(`SELECT CONCAT(id,'|',kcal_per_100g) FROM fm_plan.food`).split('\n')
      .filter(Boolean).map((line) => line.split('|')).map(([id, k]) => [id, Number(k)]),
  )
  const eachItemOk = items.every((i) => num(i.kcal) === kcalOf(priceMap.get(String(i.foodId)), num(i.gram)))
  check('★ 每个食物项 kcal = food 现价 × 折算克数（详情页按现价算，不用导入快照）', eachItemOk,
    items.filter((i) => num(i.kcal) !== kcalOf(priceMap.get(String(i.foodId)), num(i.gram)))
      .slice(0, 3).map((i) => `${i.foodName}:${i.kcal}≠${kcalOf(priceMap.get(String(i.foodId)), num(i.gram))}`).join(','))

  const db3 = sql(`SELECT CONCAT(
      (SELECT COUNT(*) FROM (SELECT d.id FROM fm_plan.diet_plan_day d LEFT JOIN fm_plan.diet_plan_meal m ON m.diet_plan_day_id=d.id
        GROUP BY d.id, d.kcal_total HAVING d.kcal_total <> COALESCE(SUM(m.kcal),0)) a),'/',
      (SELECT COUNT(*) FROM (SELECT m.id FROM fm_plan.diet_plan_meal m LEFT JOIN fm_plan.diet_plan_meal_item i ON i.meal_id=m.id
        GROUP BY m.id, m.kcal HAVING m.kcal <> COALESCE(SUM(i.kcal),0)) b),'/',
      (SELECT COUNT(*) FROM (SELECT i.id FROM fm_plan.diet_plan_meal_item i JOIN fm_plan.food f ON f.id=i.food_id
        WHERE i.kcal <> ROUND(f.kcal_per_100g * i.amount / 100)) c))`)
  check('★ SQL 直查三层热量：天vs餐 / 餐vs食物项 / 食物项vs单价 全部 0 不一致', db3 === '0/0/0', db3)

  const badDay = await call('GET', `/diet/plans/${planId}?day=99`)
  check('查第 99 天 → 400 + 文案点名是哪一天', badDay.json?.code === 400
    && String(badDay.json?.msg || '').includes('99'), `${badDay.json?.code} ${badDay.json?.msg}`)

  // 唯一索引结构：必须是 (day, meal_type, meal_index) 三列，否则"三次加餐"插不进去
  const ukCols = sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS "
    + "WHERE TABLE_SCHEMA='fm_plan' AND TABLE_NAME='diet_plan_meal' AND INDEX_NAME='uk_diet_meal'")
  check('★ uk_diet_meal 是 3 列（day + meal_type + meal_index）',
    ukCols === 'diet_plan_day_id,meal_type,meal_index', ukCols)

  const someDay = sql(`SELECT diet_plan_day_id FROM fm_plan.diet_plan_meal WHERE meal_type=4 GROUP BY diet_plan_day_id LIMIT 1`)
  const dup = sqlExpectFail(`INSERT INTO fm_plan.diet_plan_meal (diet_plan_day_id, meal_type, meal_index, meal_time, kcal) `
    + `VALUES (${someDay}, 4, 2, '16:00:00', 100)`)
  check('（负向）同一天同餐次同序号的重复插入被唯一索引挡住', !dup.ok && /Duplicate/i.test(dup.err),
    dup.err.split('\n')[0])

  const fourth = sqlExpectFail(`INSERT INTO fm_plan.diet_plan_meal (diet_plan_day_id, meal_type, meal_index, meal_time, kcal) `
    + `VALUES (${someDay}, 4, 9, '23:00:00', 88)`)
  check('★（正向）同一天能插第 4 顿加餐（index=9）—— 证明索引不是 (day, meal_type) 两列',
    fourth.ok, fourth.err.split('\n')[0])
  if (fourth.ok) {
    sql(`DELETE FROM fm_plan.diet_plan_meal WHERE diet_plan_day_id=${someDay} AND meal_type=4 AND meal_index=9`)
    check('（清理）临时插入的第 4 顿加餐已删除',
      sql(`SELECT COUNT(*) FROM fm_plan.diet_plan_meal WHERE diet_plan_day_id=${someDay} AND meal_type=4`) === '3')
  }

  // ==================== 11. 食物库 ====================
  section('11. 食物库：分类动态 / 自定义 / 收藏 / 单位校验')

  const dict = await call('GET', '/foods/dict')
  const dictData = dict.json?.data
  check('字典可查', dict.json?.code === 200 && !!dictData, dict.text.slice(0, 140))
  const cats = dictData?.categories || []
  check('★ 分类只返回库里真有数据的（不留死入口）', cats.length > 0 && cats.length <= 8,
    `${cats.length} 个：${cats.join('/')}`)
  const emptyCats = cats.filter((c) => sql(`SELECT COUNT(*) FROM fm_plan.food WHERE category='${c}' AND status=1`) === '0')
  check('★★ 每个分类都能查出食物（这是"动态分类"的真正判据）', emptyCats.length === 0,
    emptyCats.join('/'))
  check('餐次枚举走接口（前端不硬编码）',
    (dictData?.mealTypes || []).length === 4
    && (dictData?.mealTypes || []).map((m) => m.label).join('') === '早餐午餐晚餐加餐',
    JSON.stringify((dictData?.mealTypes || []).map((m) => m.label)))
  // ★ 左侧 12 项里只有最后 N 项是真分类，「常见/我的收藏/我添加的/搜索」是**入口**（过滤 / 搜索）。
  //   字典必须把这件事说清楚，前端才不会把"我的收藏"当成一个 category 去查。
  const entriesAll = dictData?.entries || []
  const catEntries = entriesAll.filter((e) => e.kind === 'category')
  const frontEntries = entriesAll.filter((e) => e.kind !== 'category')
  check('★ 左侧入口标明 kind：前 4 个是入口（entry/search），不是 category',
    frontEntries.length === 4 && frontEntries.every((e) => e.key === 'search' || e.kind === 'entry'),
    JSON.stringify(frontEntries.map((e) => `${e.key}:${e.kind}`)))
  check('★ 分类项 kind=category 且与 categories 一一对应',
    catEntries.length === cats.length && catEntries.every((e) => cats.includes(e.key)),
    `${catEntries.length} vs ${cats.length}`)

  const page1 = await call('GET', '/foods?scope=common&pageNo=1&pageSize=5')
  const page1d = page1.json?.data
  check('食物分页可用', page1.json?.code === 200 && (page1d?.list || []).length > 0,
    `total=${page1d?.total}`)
  check('食物卡字段齐全（emoji / 每 100g 热量文案 / 单位表）',
    (page1d?.list || []).every((f) => f.emoji && f.kcalText && Array.isArray(f.units) && f.units.length > 0),
    JSON.stringify((page1d?.list || [])[0] || {}).slice(0, 160))

  const stamp = new Date().toISOString().replace(/[-:T.Z]/g, '').slice(0, 14)
  const customName = `验收红烧肉-${stamp}`
  const created = await call('POST', '/foods', {
    name: customName, category: '中式菜肴', kcalPer100g: 280,
    proteinPer100g: 12, carbPer100g: 8, fatPer100g: 22,
    units: [{ unit: '碗', gram: 200 }],
  })
  const customId = String(created.json?.data ?? '')
  check('自定义食物创建成功', created.json?.code === 200 && !!customId, created.text.slice(0, 140))

  const mine = await call('GET', '/foods?scope=mine&pageSize=50')
  check('★ scope=mine 能查到自己加的（owner_id>0）',
    (mine.json?.data?.list || []).some((f) => String(f.id) === customId),
    `total=${mine.json?.data?.total}`)

  await call('POST', `/foods/${customId}/favorite`)
  const fav = await call('GET', '/foods?scope=favorite&pageSize=50')
  check('★ scope=favorite 能查到刚收藏的，且 favorite=true',
    (fav.json?.data?.list || []).some((f) => String(f.id) === customId && f.favorite === true),
    `total=${fav.json?.data?.total}`)

  const dictAfter = await call('GET', '/foods/dict')
  check('★ 新增「中式菜肴」食物后，字典里分类动态多出这一类（不是写死的 8 个）',
    (dictAfter.json?.data?.categories || []).includes('中式菜肴'),
    (dictAfter.json?.data?.categories || []).join('/'))

  await call('DELETE', `/foods/${customId}/favorite`)
  const favAfter = await call('GET', '/foods?scope=favorite&pageSize=50')
  check('取消收藏后收藏列表里没了',
    !(favAfter.json?.data?.list || []).some((f) => String(f.id) === customId))

  const delFood = await call('DELETE', `/foods/${customId}`)
  check('删除自定义食物成功', delFood.json?.code === 200, delFood.text.slice(0, 120))

  // ★ 这里**不**断言 "GET /foods/{id} 报 400"：P1 根本没有这个接口（详情页不存在），
  //   打过去会落到静态资源处理器 → 500。要验的是"食物没了之后还能不能记它"。
  const logDeletedFood = await call('POST', '/diet/logs',
    { date: D, mealType: 1, foodId: Number(customId), amount: 1, unit: '碗' })
  check('★ 删掉的食物不能再被记进 diet_log（400 + 「可能已被下架」，不是写进去一条孤儿记录）',
    logDeletedFood.json?.code === 400 && String(logDeletedFood.json?.msg || '').includes('下架'),
    `${logDeletedFood.json?.code} ${logDeletedFood.json?.msg}`)

  const dictFinal = await call('GET', '/foods/dict')
  check('删掉后分类又回到原样（动态分类不是一次性快照）',
    !(dictFinal.json?.data?.categories || []).includes('中式菜肴'),
    (dictFinal.json?.data?.categories || []).join('/'))

  const badUnit = await call('POST', '/diet/logs', { date: D, mealType: 1, foodId: Number(OAT_ID), amount: 1, unit: '勺' })
  check('★ 乱填单位被拒（400 + 「不支持该单位」，不做任意单位换算）',
    badUnit.json?.code === 400 && String(badUnit.json?.msg || '').includes('单位'),
    `${badUnit.json?.code} ${badUnit.json?.msg}`)

  const zeroAmount = await call('POST', '/diet/logs', { date: D, mealType: 1, foodId: Number(OAT_ID), amount: 0, unit: '克' })
  check('份量为 0 被拒（400）', zeroAmount.json?.code === 400, `${zeroAmount.json?.code} ${zeroAmount.json?.msg}`)

  const badMeal = await call('POST', '/diet/logs', { date: D, mealType: 9, foodId: Number(OAT_ID), amount: 50, unit: '克' })
  check('餐次 9 被拒（400 + 明确说明只能是 1~4）', badMeal.json?.code === 400
    && String(badMeal.json?.msg || '').includes('餐次'), `${badMeal.json?.code} ${badMeal.json?.msg}`)

  // 时长 0 命中的是 DTO 上的 @Min(1)（"时长最少 1 分钟"），601 命中的是 @Max(600)。
  // 两条都要有，且都要是 400 + 说清范围 —— 只验一条会漏掉另一半边界。
  const badDuration = await call('POST', '/sport/logs', { date: D, sportId: Number(JOG_ID), durationMin: 0 })
  check('运动时长 0 被拒（400 + 「最少 1 分钟」）', badDuration.json?.code === 400
    && String(badDuration.json?.msg || '').includes('分钟'), `${badDuration.json?.code} ${badDuration.json?.msg}`)
  const tooLong = await call('POST', '/sport/logs', { date: D, sportId: Number(JOG_ID), durationMin: 601 })
  check('运动时长 601 被拒（400 + 「最多 600 分钟」）', tooLong.json?.code === 400
    && String(tooLong.json?.msg || '').includes('600'), `${tooLong.json?.code} ${tooLong.json?.msg}`)

  // ==================== 12. 匿名访问 ====================
  section('12. 匿名访问：必须 401（这条靠 includeLoginPaths 加了 4 个前缀）')

  const anon1 = await callAnon('GET', '/diet/today')
  check('★ 匿名 GET /pls/diet/today → 401（不是 200 + 一片空数据）', anon1.status === 401,
    `HTTP ${anon1.status} ${anon1.text.slice(0, 100)}`)
  const anon2 = await callAnon('GET', '/sports')
  check('★ 匿名 GET /pls/sports → 401', anon2.status === 401, `HTTP ${anon2.status}`)
  const anon3 = await callAnon('POST', '/diet/logs')
  check('★ 匿名 POST /pls/diet/logs → 401（写接口更危险）', anon3.status === 401, `HTTP ${anon3.status}`)
  check('401 后库里没有 user_id=null 的脏数据',
    sql('SELECT COUNT(*) FROM fm_plan.diet_log WHERE user_id IS NULL') === '0')

  // ==================== 13. 结构约束与回归 ====================
  section('13. 结构约束 + P0 回归（不动 P0 的表和接口）')

  const createDtoFields = sql("SHOW COLUMNS FROM fm_plan.diet_log")
  check('diet_log 有 food_name / kcal 快照列（不是只存 food_id）',
    createDtoFields.includes('food_name') && createDtoFields.includes('kcal'), '')

  const p0After = {
    plan: sql('SELECT COUNT(*) FROM fm_plan.plan'),
    plan_day: sql('SELECT COUNT(*) FROM fm_plan.plan_day'),
    plan_day_item: sql('SELECT COUNT(*) FROM fm_plan.plan_day_item'),
    user_plan: sql('SELECT COUNT(*) FROM fm_plan.user_plan'),
    workout_session: sql('SELECT COUNT(*) FROM fm_plan.workout_session'),
  }
  check('★ P0 的 5 张表行数一个没变（diet_plan ≠ plan、food ≠ exercise）',
    JSON.stringify(p0After) === JSON.stringify(p0Baseline), `${JSON.stringify(p0After)}`)

  const t1 = await call('GET', '/plans/today')
  const t2 = await call('GET', '/plans/recommend')
  check('P0 的「今日训练」接口仍然 200', t1.json?.code === 200, String(t1.status))
  check('P0 的「推荐计划」接口仍然 200（饮食没污染训练推荐）', t2.json?.code === 200,
    `${t2.json?.data?.length} 个`)
  check('训练计划详情里没有出现饮食字段（两个域没混）',
    !JSON.stringify((await call('GET', '/plans/1')).json?.data || {}).includes('kcalPerDay'))

  const exLib = await call('GET', '/exercises/dict')
  check('P0 的动作库字典仍然 200（exercise 与 sport 分表）', exLib.json?.code === 200
    && exLib.json?.data?.categories?.length > 0, `${exLib.json?.data?.categories?.length} 类`)

  // ==================== 汇总 ====================
  console.log('\n' + '='.repeat(78))
  console.log(`本次测试日期：diet/sport=${D} · 体征=${TODAY}/${D}/${D2}`)
  console.log(`燕麦 id=${OAT_ID} · 慢跑 id=${JOG_ID} · 计划 id=${planId}`)
  console.log(`库内：diet_log=${sql(`SELECT COUNT(*) FROM fm_plan.diet_log WHERE user_id=${STUDENT_USER_ID}`)} 行`
    + ` · sport_log=${sql(`SELECT COUNT(*) FROM fm_plan.sport_log WHERE user_id=${STUDENT_USER_ID}`)} 行`
    + ` · user_body_metric=${sql(`SELECT COUNT(*) FROM fm_plan.user_body_metric WHERE user_id=${STUDENT_USER_ID}`)} 行`
    + ` · food=${sql('SELECT COUNT(*) FROM fm_plan.food')} 行`)
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
  // 兜底还原食物单价（中途抛异常也不能把库留在脏状态）
  try {
    spawnSync(MYSQL, ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e',
      "UPDATE fm_plan.food SET kcal_per_100g = 338 WHERE name='燕麦'"],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  } catch { /* ignore */ }
  process.exit(2)
})
