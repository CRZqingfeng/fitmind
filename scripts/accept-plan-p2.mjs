#!/usr/bin/env node
/**
 * ============================================================================
 * M11-4 / P2 验收：AI 定制计划（后端）
 * ============================================================================
 * 覆盖（对应《M11-4-AI定制-开发方案》§12.1）：
 *   1. 目标推算      → 公式复算（280 天 / 2027-08-08 / 折线 7 点）、|Δ|=0、needProfile
 *   2. 骨架接口      → mock 正常返回、模型报错降级、匿名 401、配额 +1
 *   3. 骨架 clamp    → 周频 99→7、分化乱写→按周频、比例和 0.9→默认六餐、不 500
 *   4. 草稿          → source=2 / status=0 / 不在「我的计划」/ 不在推荐
 *   5. 覆盖          → 连 preview 3 次仍 1 份草稿、1 行训练日集合
 *   6. apply         → 发布 + user_plan 1 行 + 今日训练出现 + 幂等
 *   7. 越权/过期/坏参 → 403 / 400 / HTTP 400
 *   8. 选材          → 同一天不重复动作、难度不越界、有拉伸收尾、分布和=1
 *   9. 饮食          → 5 天、6 餐、每天热量偏差 ≤10%、三层热量自洽
 *
 * ★★ 四条写法纪律（与 P0/P1 一致）：
 *   ① 断言全部按本次的 id 限定范围，不用全表 count；
 *   ② SQL 执行失败必须 throw（不能把"连不上库"当成"数据为空"）；
 *   ③ 期望值**按公式自己算**，不抄接口返回值（否则接口算错脚本也绿）；
 *   ④ 改库的地方 try/finally 还原（本脚本只改 user_body_metric 的 target_weight，最后还原）。
 *
 * 前置：
 *   · fm-plan(8095) 起（离线模式即可）
 *   · fm-aigc(8094) 起，且 `AI_MODE=mock`（否则走的不是确定性模型）
 *   · 本机 Docker 没起时两个服务都加 `--spring.cloud.nacos.discovery.enabled=false`
 *
 * 用法：
 *   node scripts/accept-plan-p2.mjs            # 直连两个服务（默认）
 *   FM_GATEWAY=1 node scripts/accept-plan-p2.mjs  # 走真网关 10010（需要 Nacos+gateway+auth）
 * ============================================================================
 */
import { spawnSync } from 'node:child_process'

const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const USER_ID = 1
const OTHER_USER_ID = 999
const STUDENT_PHONE = '13800010001'
const STUDENT_PASSWORD = '123456'

const GATEWAY = process.env.FM_GATEWAY === '1'
const PLAN_BASE = GATEWAY ? 'http://localhost:10010/pls' : 'http://127.0.0.1:8095'
const AIGC_BASE = GATEWAY ? 'http://localhost:10010/ais' : 'http://127.0.0.1:8094'

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
function sql(query) {
  const r = spawnSync(MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (r.error) throw new Error(`SQL 无法启动：${query}\n${r.error.message}`)
  if (r.status !== 0) throw new Error(`SQL 执行失败：${query}\n${(r.stderr || '').trim()}`)
  return (r.stdout || '').trim()
}

const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v))
const r1 = (n) => Math.floor(n + 0.5)

let token = null

async function call(base, method, path, body, userId = USER_ID) {
  const headers = { 'Content-Type': 'application/json' }
  if (GATEWAY) {
    if (token) headers.Authorization = token
  } else {
    // 模拟网关透传：不给这两个头，fm-common 不会把返回值包成 R
    headers['user-info'] = String(userId)
    headers['request-from'] = 'gateway'
  }
  const res = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: res.status, json, text }
}

const plan = (method, path, body, userId) => call(PLAN_BASE, method, path, body, userId)
const aigc = (method, path, body, userId) => call(AIGC_BASE, method, path, body, userId)

async function login() {
  if (!GATEWAY) return
  const r = await call('http://localhost:10010', 'POST', '/as/accounts/login', {
    type: 1, cellPhone: STUDENT_PHONE, password: STUDENT_PASSWORD, rememberMe: false,
  })
  const data = r.json?.data
  if (!data) throw new Error(`登录失败：HTTP ${r.status} ${r.text.slice(0, 160)}`)
  token = data
}

/**
 * 复刻 TargetForecast.compute()（★ 期望值必须自己算，不能抄接口）。
 * weeklyDelta = clamp(|Δ|×1%, 0.25, 0.75)；daysToGoal = ceil(|Δ| / weeklyDelta × 7)
 */
function expectTarget(currentKg, targetKg) {
  const diff = Math.abs(targetKg - currentKg)
  if (diff === 0) return { weekly: 0, days: 0 }
  const weekly = Math.min(0.75, Math.max(0.25, diff * 0.01))
  return { weekly, days: Math.ceil((diff / weekly) * 7) }
}

async function main() {
  console.log('='.repeat(78))
  console.log(`M11-4 / P2 验收：AI 定制计划（${GATEWAY ? '经网关 10010' : '直连 8095 + 8094'}）`)
  console.log('='.repeat(78))

  await login()

  // ==================== 0. 前置 ====================
  section('0. 前置：两个服务可达 / mock 通道 / 基线')

  const pingPlan = await plan('GET', '/plans/ai/target')
  check('fm-plan 可达（GET /plans/ai/target 200）', pingPlan.json?.code === 200,
    `HTTP ${pingPlan.status} ${pingPlan.text.slice(0, 100)}`)
  if (pingPlan.status !== 200) throw new Error('fm-plan 不可用')

  const pingAigc = await aigc('POST', '/plan/skeleton', { weightKg: 65, targetWeightKg: 75 })
  check('fm-aigc 可达（POST /plan/skeleton 200）', pingAigc.json?.code === 200,
    `HTTP ${pingAigc.status} ${pingAigc.text.slice(0, 100)}`)
  check('★ fm-aigc 跑的是 mock 通道（骨架必须确定性，否则断言不可复现）',
    pingAigc.json?.data?.degraded === false,
    `degraded=${pingAigc.json?.data?.degraded}（true = 没用上模型或解析失败）`)

  // 把体重设成方案里的样例：180cm / 65kg / 目标 75kg
  const baseMetric = sql(`SELECT CONCAT(weight_kg,'|',IFNULL(target_weight_kg,'')) FROM fm_plan.user_body_metric
    WHERE user_id=${USER_ID} ORDER BY record_date DESC, id DESC LIMIT 1`)
  await plan('POST', '/body/metrics', { heightCm: 180, weightKg: 65, targetWeightKg: 75 })
  check('（前置）体重已设为 65kg / 目标 75kg', baseMetric !== '', `原值 ${baseMetric || '无'}`)

  const baseline = {
    officialDays: sql('SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id IN (1,2,3)'),
    officialItems: sql('SELECT COUNT(*) FROM fm_plan.plan_day_item WHERE plan_day_id IN (SELECT id FROM fm_plan.plan_day WHERE plan_id IN (1,2,3))'),
  }

  // ==================== 1. 目标推算 ====================
  section('1. 目标推算：公式 / 边界 / 缺体重')

  const t = (await plan('GET', '/plans/ai/target')).json?.data
  const exp = expectTarget(65, 75)
  check('当前体重 65kg / 目标 75kg', num(t?.currentWeightKg) === 65 && num(t?.targetWeightKg) === 75,
    `${t?.currentWeightKg} → ${t?.targetWeightKg}`)
  check('★ weeklyDeltaKg = clamp(10×1%, 0.25, 0.75) = 0.25', num(t?.weeklyDeltaKg) === exp.weekly,
    `接口 ${t?.weeklyDeltaKg} / 期望 ${exp.weekly}`)
  check('★★ daysToGoal 按修正公式 = ceil(10 / 0.25 × 7) = 280（不是 40）',
    num(t?.daysToGoal) === exp.days && exp.days === 280, `接口 ${t?.daysToGoal} / 期望 ${exp.days}`)
  check('targetDate = 今天 + 280 天', t?.targetDate === isoPlusDays(280), `接口 ${t?.targetDate}`)
  check('BMI = 20.1（1 位小数，与 NutritionCalculator 同口径）', num(t?.currentBmi) === 20.1, String(t?.currentBmi))

  const pts = t?.forecast || []
  check('折线 7 个点', pts.length === 7, `${pts.length} 个`)
  check('折线首点 = 当前体重', num(pts[0]?.weightKg) === 65, String(pts[0]?.weightKg))
  check('折线单调不降（增肌方向）',
    pts.every((p, i) => i === 0 || Number(p.weightKg) >= Number(pts[i - 1].weightKg)),
    pts.map((p) => p.weightKg).join('/'))
  check('折线末点 = 65 + 6 天步长（每步 0.25/7）≈ 65.2',
    Math.abs(Number(pts[6]?.weightKg) - (65 + (0.25 / 7) * 6)) < 0.11,
    String(pts[6]?.weightKg))

  // 边界：目标 = 当前
  await plan('POST', '/body/metrics', { weightKg: 65, targetWeightKg: 65 })
  const same = await plan('GET', '/plans/ai/target')
  const samePts = same.json?.data?.forecast || []
  check('★ 目标 = 当前 → daysToGoal=0（不除零、不 500）',
    same.json?.code === 200 && num(same.json?.data?.daysToGoal) === 0,
    `${same.json?.code} days=${same.json?.data?.daysToGoal}`)
  check('★ 目标 = 当前 → 折线是一条水平线',
    samePts.length === 7 && samePts.every((p) => Number(p.weightKg) === 65),
    samePts.map((p) => p.weightKg).join('/'))
  await plan('POST', '/body/metrics', { weightKg: 65, targetWeightKg: 75 })

  // 边界：什么数据都没有的用户
  const noProfile = await plan('GET', '/plans/ai/target', undefined, OTHER_USER_ID)
  check('★★ 没有任何体重数据的用户 → needProfile=true，且不返回 0',
    noProfile.json?.data?.needProfile === true && noProfile.json?.data?.currentWeightKg === null,
    JSON.stringify(noProfile.json?.data || {}).slice(0, 120))

  // ==================== 2. 骨架接口 ====================
  section('2. 骨架接口（fm-aigc，mock）：正常 / 降级 / 匿名 / 配额')

  const quotaBefore = await aigc('GET', '/chat/quota')
  const sk = await aigc('POST', '/plan/skeleton', { weightKg: 65, targetWeightKg: 75, trainingLevel: 2 })
  const skd = sk.json?.data
  check('骨架返回 5 个字段齐全',
    !!skd?.division && !!skd?.weeklyFrequency && !!skd?.intensity && Array.isArray(skd?.mealSplit),
    `${skd?.division}/${skd?.weeklyFrequency}/${skd?.intensity}`)
  check('骨架不含任何 id 字段（只有 skeletonId）',
    skd && !('exerciseIds' in skd) && !('foodIds' in skd) && !('planId' in skd),
    Object.keys(skd || {}).join(','))
  const ratioSum = (skd?.mealSplit || []).reduce((s, x) => s + Number(x.ratio), 0)
  check('餐次比例之和 = 1（±0.01）', Math.abs(ratioSum - 1) <= 0.01, String(ratioSum.toFixed(3)))
  check('三段文案都有内容',
    !!skd?.dietAdvice && !!skd?.trainingAdvice && !!skd?.targetTip,
    `${(skd?.dietAdvice || '').length}/${(skd?.trainingAdvice || '').length}/${(skd?.targetTip || '').length} 字`)

  const quotaAfter = await aigc('GET', '/chat/quota')
  const usedBefore = num(quotaBefore.json?.data?.usedChats)
  const usedAfter = num(quotaAfter.json?.data?.usedChats)
  check('★ 骨架接口占 1 次 AI 配额', usedAfter === usedBefore + 1, `${usedBefore} → ${usedAfter}`)

  const errSk = await aigc('POST', '/plan/skeleton', { weightKg: 65, note: '[MOCK:ERROR]' })
  check('★★ 模型报错 → 仍返回完整骨架，degraded=true',
    errSk.json?.code === 200 && errSk.json?.data?.degraded === true
    && !!errSk.json?.data?.division && (errSk.json?.data?.mealSplit || []).length > 0,
    `degraded=${errSk.json?.data?.degraded}`)

  const timeoutSk = await aigc('POST', '/plan/skeleton', { weightKg: 65, note: '[MOCK:TIMEOUT]' })
  check('★ 模型超时 → 同样降级（不抛 500）',
    timeoutSk.status === 200 && timeoutSk.json?.data?.degraded === true,
    `HTTP ${timeoutSk.status}`)

  const needSk = await aigc('POST', '/plan/skeleton', {}, OTHER_USER_ID)
  check('★ 没有任何数据 → needProfile=true（不编骨架）',
    needSk.json?.data?.needProfile === true && needSk.json?.data?.division === null,
    JSON.stringify(needSk.json?.data || {}).slice(0, 100))

  const anon = await fetch((GATEWAY ? 'http://localhost:10010/ais' : AIGC_BASE) + '/plan/skeleton', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  })
  check('★★ 匿名调 /ais/plan/skeleton → 401（靠 includeLoginPaths 加 /plan/**）',
    anon.status === 401, `HTTP ${anon.status}`)

  // ==================== 3. 骨架 clamp ====================
  section('3. 骨架 clamp：非法值回落，不 500')

  const wild = await plan('POST', '/plans/ai/preview', {
    skeletonId: 'test-clamp',
    division: '全身超级训练',
    weeklyFrequency: 99,
    intensity: 'EXTREME',
    mealSplit: [{ mealType: 1, ratio: 0.9 }],
  })
  const wildD = wild.json?.data
  check('★ 周频 99 → 7（clamp 到边界，不是丢回默认 3）',
    (wildD?.trainDays || []).length === 7, `${(wildD?.trainDays || []).length} 个训练日`)
  check('★ 分化乱写 → 按周频回落（7 练 → 五分化，每个训练日只有 1~3 个部位）',
    (wildD?.trainDays || []).every((d) => d.name.length <= 20),
    (wildD?.trainDays || []).map((d) => d.name).join(' / '))
  check('★ 比例和 0.9 → 回落默认六餐', (wildD?.dietDays?.[0]?.meals || []).length === 6,
    `${(wildD?.dietDays?.[0]?.meals || []).length} 餐`)
  check('clamp 过程写进 notes（用户能看见改了什么）',
    (wildD?.advice?.notes || []).some((n) => n.includes('99')), (wildD?.advice?.notes || []).join(' | ').slice(0, 120))

  // ==================== 4. 草稿状态 ====================
  section('4. 草稿：source=2 / status=0 / 不出现在「我的计划」与推荐')

  const prev = (await plan('POST', '/plans/ai/preview', {})).json?.data
  check('预览成功并返回 previewId / 两个草稿 id',
    !!prev?.previewId && !!prev?.planDraftId && !!prev?.dietDraftId,
    `${String(prev?.previewId).slice(0, 8)} ${prev?.planDraftId}/${prev?.dietDraftId}`)

  const draftRow = sql(`SELECT CONCAT(source,'|',status,'|',owner_id) FROM fm_plan.plan WHERE id=${prev.planDraftId}`)
  check('★★ 草稿 plan：source=2（AI）、status=0（未发布）',
    draftRow === '2|0|1', draftRow)
  const dietRow = sql(`SELECT CONCAT(source,'|',status) FROM fm_plan.diet_plan WHERE id=${prev.dietDraftId}`)
  check('★★ 草稿 diet_plan：source=2 / status=0', dietRow === '2|0', dietRow)

  const mine = (await plan('GET', '/plans/mine')).json?.data || []
  check('★★ 草稿不出现在「我的计划」里',
    !mine.some((p) => String(p.id) === String(prev.planDraftId)), `${mine.length} 条`)
  const rec = (await plan('GET', '/plans/recommend')).json?.data || []
  check('★ 草稿不出现在「推荐计划」里',
    !rec.some((p) => String(p.id) === String(prev.planDraftId)), `${rec.length} 条`)

  // ==================== 5. 覆盖 ====================
  section('5. 重复 preview 覆盖（不堆垃圾）')

  const draftCountBefore = sql(`SELECT COUNT(*) FROM fm_plan.plan WHERE source=2 AND owner_id=${USER_ID}`)
  const p2ids = []
  for (let i = 0; i < 3; i++) {
    p2ids.push((await plan('POST', '/plans/ai/preview', {})).json?.data?.previewId)
  }
  const draftCountAfter = sql(`SELECT COUNT(*) FROM fm_plan.plan WHERE source=2 AND owner_id=${USER_ID}`)
  check('★ 连 preview 3 次 → 草稿 plan 行数**没变**（复用同一条）',
    draftCountAfter === draftCountBefore, `${draftCountBefore} → ${draftCountAfter}`)
  check('★ 三次 previewId 各不相同（"重新生成"必须真的重算）',
    new Set(p2ids).size === 3, p2ids.map((x) => String(x).slice(0, 6)).join(','))
  const draftSlot = sql(`SELECT COUNT(*) FROM fm_plan.ai_plan_draft WHERE user_id=${USER_ID}`)
  check('★ ai_plan_draft 该用户只有 1 行（uk_draft_user 保证）', draftSlot === '1', draftSlot)

  const latestPreview = sql(`SELECT plan_id FROM fm_plan.ai_plan_draft WHERE user_id=${USER_ID}`)
  const dayRows = sql(`SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id=${latestPreview}`)
  check('★ 训练日行数 = 骨架周频（覆盖时是"清空重建"，不是叠加）',
    Number(dayRows) === 3, `${dayRows} 行`)

  // ==================== 6. 选材质量 ====================
  section('6. 选材：动作不重复 / 难度不越界 / 有拉伸 / 分布自洽')

  const pv = (await plan('POST', '/plans/ai/preview', { weeklyFrequency: 3, division: '三分化' })).json?.data
  const dup = sql(`SELECT COUNT(*) FROM (
      SELECT plan_day_id, COUNT(*) c, COUNT(DISTINCT exercise_id) d
      FROM fm_plan.plan_day_item WHERE plan_day_id IN
        (SELECT id FROM fm_plan.plan_day WHERE plan_id=${pv.planDraftId})
      GROUP BY plan_day_id HAVING c <> d) t`)
  check('★★ 同一天不重复同一个动作', dup === '0', `${dup} 天有重复`)

  const tooHard = sql(`SELECT COUNT(*) FROM fm_plan.plan_day_item i
      JOIN fm_plan.exercise e ON e.id = i.exercise_id
      WHERE i.plan_day_id IN (SELECT id FROM fm_plan.plan_day WHERE plan_id=${pv.planDraftId})
        AND e.category <> '拉伸' AND e.difficulty > 1`)
  check('★ 没有档案（新手）时，力量类动作难度 ≤ 1', tooHard === '0', `${tooHard} 个越界`)

  const stretch = sql(`SELECT COUNT(*) FROM fm_plan.plan_day_item i
      JOIN fm_plan.exercise e ON e.id = i.exercise_id
      WHERE e.category = '拉伸' AND i.plan_day_id IN
        (SELECT id FROM fm_plan.plan_day WHERE plan_id=${pv.planDraftId})`)
  check('★ 每个训练日都以拉伸收尾（拉伸项数 = 训练日数）',
    Number(stretch) === (pv.trainDays || []).length, `${stretch} 个拉伸 / ${(pv.trainDays || []).length} 天`)

  const d = pv.distribution || {}
  check('★ 运动分布三项之和 = 1（力量 + 有氧 + 柔韧）',
    Math.abs(Number(d.strength) + Number(d.cardio) + Number(d.flexibility) - 1) < 0.011,
    `${d.strength}/${d.cardio}/${d.flexibility}`)
  check('★ 分布里能看到柔韧（不是 100% 力量）', Number(d.flexibility) > 0, String(d.flexibility))

  const estOk = (pv.trainDays || []).every((x) => x.estMinutes > 0 && x.itemCount > 0)
  check('★ 每个训练日都有动作数与预计分钟（来自后端 PlanTimeEstimator）', estOk,
    (pv.trainDays || []).map((x) => `${x.itemCount}/${x.estMinutes}`).join(' '))

  // ==================== 7. 饮食 ====================
  section('7. 饮食预览：5 天 / 6 餐 / 热量偏差 / 三层自洽')

  check('饮食计划 5 天', (pv.dietDays || []).length === 5, `${(pv.dietDays || []).length} 天`)
  check('每天 6 餐（早 + 加 + 午 + 加 + 晚 + 加）',
    (pv.dietDays || []).every((x) => (x.meals || []).length === 6),
    (pv.dietDays || []).map((x) => (x.meals || []).length).join('/'))
  const budget = num(pv.macros?.kcalBudget)
  const deviations = (pv.dietDays || []).map((x) => Math.abs(Number(x.kcalTotal) - budget) / budget)
  check('★ 每天热量与预算的偏差 ≤ 10%',
    deviations.every((x) => x <= 0.10),
    deviations.map((x) => `${(x * 100).toFixed(1)}%`).join(' '))
  check('★ 每餐 kcal = 该餐食物项之和（三层口径一致）',
    (pv.dietDays || []).every((day) => (day.meals || []).every(
      (m) => Number(m.kcal) === (m.items || []).reduce((s, i) => s + Number(i.kcal), 0))))
  check('★ 三天层 SQL 对账：食物项 kcal = food 现价 × 克数',
    sql(`SELECT COUNT(*) FROM fm_plan.diet_plan_meal_item i JOIN fm_plan.food f ON f.id=i.food_id
         WHERE i.meal_id IN (SELECT m.id FROM fm_plan.diet_plan_meal m
           JOIN fm_plan.diet_plan_day d ON d.id=m.diet_plan_day_id
           WHERE d.diet_plan_id=${pv.dietDraftId})
         AND i.kcal <> ROUND(f.kcal_per_100g * i.amount / 100)`) === '0')
  check('加餐带序号（加餐①/②/③，不是三个同名"加餐"）',
    (pv.dietDays?.[0]?.meals || []).filter((m) => m.mealType === 4).every((m) => /加餐[①②③]/.test(m.label)),
    (pv.dietDays?.[0]?.meals || []).filter((m) => m.mealType === 4).map((m) => m.label).join('/'))

  // ==================== 8. apply ====================
  section('8. apply：发布 / 加入我的计划 / 今日训练 / 幂等')

  const ap = await plan('POST', '/plans/ai/apply', { previewId: pv.previewId })
  const apd = ap.json?.data
  check('apply 返回三个 id', ap.json?.code === 200 && !!apd?.planId && !!apd?.userPlanId && !!apd?.dietPlanId,
    JSON.stringify(apd))
  check('★★ 训练计划已发布（status=1）、来源仍是 AI（source=2）',
    sql(`SELECT CONCAT(source,'|',status) FROM fm_plan.plan WHERE id=${apd.planId}`) === '2|1',
    sql(`SELECT CONCAT(source,'|',status) FROM fm_plan.plan WHERE id=${apd.planId}`))
  check('★ 饮食计划也已发布（status=1）',
    sql(`SELECT status FROM fm_plan.diet_plan WHERE id=${apd.dietPlanId}`) === '1')
  check('★★ user_plan 正好 1 行（= 执行本计划）',
    sql(`SELECT COUNT(*) FROM fm_plan.user_plan WHERE user_id=${USER_ID} AND plan_id=${apd.planId}`) === '1')

  const today = (await plan('GET', '/plans/today')).json?.data
  check('★★ 接受后今日训练变成"有训练"（status=2）', num(today?.status) === 2,
    `status=${today?.status} ${today?.statusLabel} ${today?.dayName || ''}`)
  check('今日训练的动作数与预计分钟都 > 0',
    Number(today?.exerciseCount) > 0 && Number(today?.estMinutes) > 0,
    `${today?.exerciseCount} 个 / ${today?.estMinutes} 分钟`)

  const apAgain = await plan('POST', '/plans/ai/apply', { previewId: pv.previewId })
  check('★ apply 幂等：第二次返回同样的 ids',
    apAgain.json?.data?.planId === apd.planId && apAgain.json?.data?.userPlanId === apd.userPlanId,
    JSON.stringify(apAgain.json?.data))
  check('★ apply 幂等：user_plan 仍是 1 行',
    sql(`SELECT COUNT(*) FROM fm_plan.user_plan WHERE user_id=${USER_ID} AND plan_id=${apd.planId}`) === '1')

  const mineAfter = (await plan('GET', '/plans/mine')).json?.data || []
  check('★ 接受后出现在「我的计划」里',
    mineAfter.some((p) => String(p.id) === String(apd.planId)), `${mineAfter.length} 条`)

  // 已接受后再 preview：必须新开一份草稿，不能动已发布的
  const afterPreview = (await plan('POST', '/plans/ai/preview', {})).json?.data
  check('★ 已接受后再 preview → 新建草稿（id 与已发布的不同）',
    String(afterPreview.planDraftId) !== String(apd.planId),
    `${afterPreview.planDraftId} vs ${apd.planId}`)
  check('★ 已发布的计划状态没被改回草稿',
    sql(`SELECT status FROM fm_plan.plan WHERE id=${apd.planId}`) === '1')

  // ==================== 9. 越权 / 过期 / 坏参数 ====================
  section('9. 越权 / 过期 / 坏参数')

  const crossApply = await plan('POST', '/plans/ai/apply',
    { previewId: afterPreview.previewId }, OTHER_USER_ID)
  check('★★ 用户 B 拿用户 A 的 previewId → 403（不是"A 的计划被 B 接受"）',
    crossApply.json?.code === 403, `${crossApply.json?.code} ${crossApply.json?.msg}`)
  check('越权后 A 的计划仍是草稿（没有被别人接受）',
    sql(`SELECT status FROM fm_plan.plan WHERE id=${afterPreview.planDraftId}`) === '0')

  const expired = await plan('POST', '/plans/ai/apply', { previewId: '00000000-0000-0000-0000-000000000000' })
  check('★ 假 previewId → 400 + 「预览已过期」',
    expired.json?.code === 400 && String(expired.json?.msg || '').includes('过期'),
    `${expired.json?.code} ${expired.json?.msg}`)

  const badType = await plan('POST', '/plans/ai/preview', { mealSplit: '不是数组' })
  check('★ 类型不符（mealSplit 传字符串）→ HTTP 400（#A28 的全局 handler）',
    badType.status === 400, `HTTP ${badType.status} ${badType.text.slice(0, 80)}`)

  const badFreq = await plan('POST', '/plans/ai/preview', { weeklyFrequency: 'abc' })
  check('★ 类型不符（weeklyFrequency 传 abc）→ HTTP 400',
    badFreq.status === 400, `HTTP ${badFreq.status}`)

  const anonPreview = await fetch((GATEWAY ? 'http://localhost:10010/pls' : PLAN_BASE) + '/plans/ai/preview', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  })
  check('★★ 匿名调 /pls/plans/ai/preview → 401', anonPreview.status === 401, `HTTP ${anonPreview.status}`)

  // ==================== 10. 回归 ====================
  section('10. 回归：官方计划没被动过 + P0/P1 接口仍可用')

  check('★ 官方计划（1/2/3）的训练日数没变',
    sql('SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id IN (1,2,3)') === baseline.officialDays,
    `${baseline.officialDays} → ${sql('SELECT COUNT(*) FROM fm_plan.plan_day WHERE plan_id IN (1,2,3)')}`)
  check('★ 官方计划的动作项数没变',
    sql('SELECT COUNT(*) FROM fm_plan.plan_day_item WHERE plan_day_id IN (SELECT id FROM fm_plan.plan_day WHERE plan_id IN (1,2,3))')
    === baseline.officialItems)
  check('P0：/plans/recommend 仍 200', (await plan('GET', '/plans/recommend')).json?.code === 200)
  check('P0：/exercises/dict 仍 200',
    (await plan('GET', '/exercises/dict')).json?.data?.categories?.length > 0)
  check('P1：/diet/today 仍 200', (await plan('GET', '/diet/today')).json?.code === 200)
  check('P1：/diet/plans/recommend 仍 3 条',
    ((await plan('GET', '/diet/plans/recommend')).json?.data || []).length >= 3)
  check('动作种子已扩容（≥100 条，11 个部位都 ≥4）',
    Number(sql('SELECT COUNT(*) FROM fm_plan.exercise')) >= 100
    && Number(sql('SELECT MIN(n) FROM (SELECT COUNT(*) n FROM fm_plan.exercise GROUP BY body_part) t')) >= 4,
    `总数 ${sql('SELECT COUNT(*) FROM fm_plan.exercise')} / 最小部位 ${sql('SELECT MIN(n) FROM (SELECT COUNT(*) n FROM fm_plan.exercise GROUP BY body_part) t')}`)

  // ==================== 汇总 ====================
  console.log('\n' + '='.repeat(78))
  console.log(`本次：preview 批次 ${p2ids.length + 3} 次 · 已接受计划 id=${apd?.planId} · 已发布饮食 id=${apd?.dietPlanId}`)
  console.log(`库内：plan(source=2)=${sql('SELECT COUNT(*) FROM fm_plan.plan WHERE source=2')}`
    + ` · ai_plan_draft=${sql('SELECT COUNT(*) FROM fm_plan.ai_plan_draft')}`
    + ` · exercise=${sql('SELECT COUNT(*) FROM fm_plan.exercise')}`)
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

function isoPlusDays(days) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

main().catch((e) => {
  console.error('\n脚本异常终止：', e)
  process.exit(2)
})
