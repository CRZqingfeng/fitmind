#!/usr/bin/env node
/**
 * ============================================================================
 * M11-5 / P3 验收：数据全量导入（后端）
 * ============================================================================
 * 覆盖（对应《M11-5-数据导入-开发方案》§9）：
 *   1. 动作条数与"老 id 不动"      → 873 / id<=108 仍是 108 行
 *   2. 分类分布 + **不存在"热身"**  → 736 / 123 / 14
 *   3. 部位分布 12 值（含"其他"）   → 胸84…其他8
 *   4. 器械分布 6 值 + 无史密斯/悍马机
 *   5. 数据质量                    → steps_json 全 JSON_VALID / '[]' 恰 5 条 / name 无首尾空格
 *   6. **幂等**                    → 重跑 --write：行数不变、id:name 的 MD5 不变
 *   7. ★★ AI 选材不掉链子          → 每个训练日除最后 1 条拉伸外全是"力量"，拉伸数 = 训练日数
 *   8. 字典动态化                  → categories=2（无热身/无力量）/ bodyParts=12 / equipments=6
 *   9. 图片 5 张                   → 200 / image/jpeg / >10000 字节（三个都要断）
 *  10. DB 里 image_url             → 无 ghfast.top、无 http 开头
 *  11. 食物条数 + 跳过的 16 条     → 1638 / 那 16 条不在库里
 *  12. alias                       → 379 条非空；**用接口**搜"江米"能搜到"糯米［江米］"
 *  13. "常见"推荐位                → 第一位不是牛油/猪油；sort<10000 恰好 30 行
 *  14. 孤儿引用                    → plan_day_item / workout_set_log 指向的 exercise 全部存在
 *  15. ★★ 食物池"能按份吃"门槛     → 5 天偏差 ≤10%；奶项 ≤100 千卡/100g；主食 ≥200
 *
 * ★★ 四条写法纪律（与 P0/P1/P2 一致）：
 *   ① 断言直查库，不看接口返回码；
 *   ② SQL 失败必须 throw（不能把"连不上库"当成"数据为空"）；
 *   ③ 期望值**能推导**（分布来自三张映射表，不是抄接口返回）；
 *   ④ 第 6 条会重跑导入脚本（写库），第 15 条会生成草稿 —— 都是幂等/可重复的操作。
 *
 * 前置：
 *   · fm-plan(8095) 起（本机通常用离线模式：--spring.cloud.nacos.discovery.enabled=false）
 *   · 动作/食物/图片三个导入脚本已跑过（否则第 1/9/11 条必红）
 *
 * 用法：
 *   node scripts/accept-plan-p3.mjs
 *   FM_GATEWAY=1 node scripts/accept-plan-p3.mjs   # 走真网关（需 Nacos+gateway+auth）
 * ============================================================================
 */
import { spawnSync } from 'node:child_process'

const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const PYTHON = 'python'
const USER_ID = 1

const GATEWAY = process.env.FM_GATEWAY === '1'
const PLAN_BASE = GATEWAY ? 'http://localhost:10010/pls' : 'http://127.0.0.1:8095'

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
function run(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (r.error) throw new Error(`${cmd} 无法启动：${r.error.message}`)
  return r
}

function sql(query) {
  const r = run(MYSQL, ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306',
    '-uroot', '-p1234', '-N', '-e', query])
  if (r.status !== 0) throw new Error(`SQL 执行失败：${query}\n${(r.stderr || '').trim()}`)
  return (r.stdout || '').trim()
}

/** 单行多列：返回数组 */
function row(query) {
  return sql(query).split('\t')
}

/** 多行 → 每组按 tab 切 */
function rows(query) {
  const out = sql(query)
  if (!out) return []
  return out.split('\n').map((l) => l.replace(/\r$/, '').split('\t'))
}

let token = null

async function call(method, path, body, userId = USER_ID) {
  const headers = { 'Content-Type': 'application/json' }
  if (GATEWAY) {
    if (token) headers.Authorization = token
  } else {
    headers['user-info'] = String(userId)
    headers['request-from'] = 'gateway'
  }
  const res = await fetch(PLAN_BASE + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: res.status, text, json }
}

async function main() {
  console.log('M11-5 / P3 验收：数据全量导入')
  console.log(`  模式：${GATEWAY ? '走网关 10010' : '直连 8095'}　用户：${USER_ID}`)

  // ==================== 1. 动作条数 ====================
  section('1. 动作库：873 条，且老 id 全部保留')
  const total = Number(sql('SELECT COUNT(*) FROM fm_plan.exercise'))
  check('★★ exercise 总行数 = 873', total === 873, String(total))
  const oldSeg = Number(sql('SELECT COUNT(*) FROM fm_plan.exercise WHERE id <= 108'))
  check('★ 老种子 id 段（1~108）仍是 108 行（id 没被重排）', oldSeg === 108, String(oldSeg))
  const minId = Number(sql('SELECT MIN(id) FROM fm_plan.exercise'))
  check('最小 id = 1（没有 TRUNCATE 重建）', minId === 1, String(minId))

  // ==================== 2. 分类分布 ====================
  section('2. 分类分布：力量 736 / 拉伸 123 / 有氧 14，且**不存在热身**')
  const catMap = Object.fromEntries(rows('SELECT category, COUNT(*) FROM fm_plan.exercise GROUP BY category')
    .map(([k, v]) => [k, Number(v)]))
  check('力量 = 736', catMap['力量'] === 736, String(catMap['力量']))
  check('拉伸 = 123', catMap['拉伸'] === 123, String(catMap['拉伸']))
  check('有氧 = 14', catMap['有氧'] === 14, String(catMap['有氧']))
  // ★ 反向断言：数据集没有热身类，P3 也不许"补 20 条凑数"
  check('★★ **不存在"热身"分类**（数据集没有，且不许编）', catMap['热身'] === undefined,
    Object.keys(catMap).join('/'))
  check('分类值恰好 3 种', Object.keys(catMap).length === 3, Object.keys(catMap).join('/'))

  // ==================== 3. 部位分布 ====================
  section('3. 部位分布：12 个值（含"其他"）')
  const EXPECT_PART = {
    胸: 84, 背: 99, 腿: 240, 肩: 127, 二头: 53, 三头: 71,
    小腿: 28, 前臂: 25, 臀部: 30, 腹部: 93, 斜方肌: 15, 其他: 8,
  }
  const partMap = Object.fromEntries(rows('SELECT body_part, COUNT(*) FROM fm_plan.exercise GROUP BY body_part')
    .map(([k, v]) => [k, Number(v)]))
  let partBad = []
  for (const [k, v] of Object.entries(EXPECT_PART)) {
    if (partMap[k] !== v) partBad.push(`${k}:${partMap[k]}≠${v}`)
  }
  check('★★ 12 个部位逐一对上（胸84/背99/腿240/肩127/二头53/三头71/小腿28/前臂25/臀部30/腹部93/斜方肌15/其他8）',
    partBad.length === 0, partBad.join(' ') || `${Object.keys(partMap).length} 个值`)
  check('部位值恰好 12 种（多一个少一个都说明映射表变了）',
    Object.keys(partMap).length === 12, String(Object.keys(partMap).length))
  check('★ "其他" = 8（neck 那 8 条没有变成孤儿）', partMap['其他'] === 8, String(partMap['其他']))

  // ==================== 4. 器械分布 ====================
  section('4. 器械分布：6 个值，**无史密斯 / 无悍马机**')
  const EXPECT_EQUIP = { 徒手: 111, 哑铃: 123, 杠铃: 179, 固定器械: 148, 弹力带: 20, 其他: 292 }
  const equipMap = Object.fromEntries(rows('SELECT equipment, COUNT(*) FROM fm_plan.exercise GROUP BY equipment')
    .map(([k, v]) => [k, Number(v)]))
  let equipBad = []
  for (const [k, v] of Object.entries(EXPECT_EQUIP)) {
    if (equipMap[k] !== v) equipBad.push(`${k}:${equipMap[k]}≠${v}`)
  }
  check('★★ 6 类器械逐一对上（徒手111/哑铃123/杠铃179/固定器械148/弹力带20/其他292）',
    equipBad.length === 0, equipBad.join(' ') || `${Object.keys(equipMap).length} 个值`)
  check('器械值恰好 6 种', Object.keys(equipMap).length === 6, Object.keys(equipMap).join('/'))
  check('★★ **没有"史密斯"**（数据集里根本没有这类机器，不许编）', equipMap['史密斯'] === undefined)
  check('★★ **没有"悍马机"**（同上）', equipMap['悍马机'] === undefined)

  // ==================== 5. 数据质量 ====================
  section('5. 数据质量：JSON 合法 / 空步骤如实 / 名字无空格')
  const badJson = Number(sql(
    "SELECT COUNT(*) FROM fm_plan.exercise WHERE steps_json IS NOT NULL AND JSON_VALID(steps_json) = 0"))
  check('★★ steps_json 全表 JSON_VALID（防"字符串里带未转义引号"）', badJson === 0, String(badJson))
  const emptySteps = Number(sql("SELECT COUNT(*) FROM fm_plan.exercise WHERE steps_json = '[]'"))
  check('★ steps_json = "[]" 恰好 5 条（如实存空，不补假步骤）', emptySteps === 5, String(emptySteps))
  const trimmedBad = Number(sql('SELECT COUNT(*) FROM fm_plan.exercise WHERE name <> TRIM(name)'))
  check('★ name 无首尾空格（3 条脏值已 strip）', trimmedBad === 0, String(trimmedBad))
  const secondaryBad = Number(sql(
    "SELECT COUNT(*) FROM fm_plan.exercise WHERE secondary_muscles_json IS NOT NULL "
    + 'AND JSON_VALID(secondary_muscles_json) = 0'))
  check('secondary_muscles_json 全部合法', secondaryBad === 0, String(secondaryBad))

  // ==================== 6. 幂等 ====================
  section('6. 幂等：再跑一次 --write，行数与 id 都不变')
  const hashBefore = sql("SELECT MD5(GROUP_CONCAT(CONCAT(id,':',name) ORDER BY id)) FROM fm_plan.exercise")
  const imp = run(PYTHON, ['scripts/import-exercise-db.py', '--write'])
  const impOk = imp.status === 0
  check('重跑 import-exercise-db.py --write 退出码 0', impOk, impOk ? '' : (imp.stderr || '').slice(0, 120))
  const totalAfter = Number(sql('SELECT COUNT(*) FROM fm_plan.exercise'))
  check('★★ 重跑后仍是 873 行（"重跑不重复"才是真幂等）', totalAfter === 873, String(totalAfter))
  const hashAfter = sql("SELECT MD5(GROUP_CONCAT(CONCAT(id,':',name) ORDER BY id)) FROM fm_plan.exercise")
  check('★★ 重跑后 id:name 的 MD5 完全一致（id 没被重建）',
    hashBefore === hashAfter, `${hashBefore.slice(0, 8)} → ${hashAfter.slice(0, 8)}`)

  // ==================== 7. AI 选材 ====================
  section('7. ★★ AI 选材：训练日的"主项"只出力量，拉伸只做收尾')
  // 先保证有体征（不然 preview 会因"先记录体重"被拒）
  await call('POST', '/body/metrics', { heightCm: 180, weightKg: 65, targetWeightKg: 75 })
  const pv = (await call('POST', '/plans/ai/preview', { weeklyFrequency: 3, division: '三分化' })).json?.data
  check('preview 成功（拿到了草稿）', !!pv?.planDraftId, String(pv?.planDraftId))
  if (pv?.planDraftId) {
    const dayIds = sql(`SELECT GROUP_CONCAT(id) FROM fm_plan.plan_day WHERE plan_id=${pv.planDraftId}`)
    check('训练日有 3 个', (dayIds || '').split(',').length === 3, String(dayIds))
    // 非拉伸动作里出现"力量"以外的分类 = 池子没按分类过滤（§7.1 没改）
    const notStrength = Number(sql(
      `SELECT COUNT(*) FROM fm_plan.plan_day_item i JOIN fm_plan.exercise e ON e.id = i.exercise_id
       WHERE i.plan_day_id IN (${dayIds}) AND e.category <> '拉伸' AND e.category <> '力量'`))
    check('★★ 除拉伸收尾外，动作 category **全是"力量"**（有氧/弹跳没混进来）',
      notStrength === 0, `${notStrength} 个越界`)
    const stretchCount = Number(sql(
      `SELECT COUNT(*) FROM fm_plan.plan_day_item i JOIN fm_plan.exercise e ON e.id = i.exercise_id
       WHERE i.plan_day_id IN (${dayIds}) AND e.category = '拉伸'`))
    check('★★ 拉伸项数 = 训练日数（每天恰好 1 条收尾）', stretchCount === 3, String(stretchCount))
    // 拉伸必须是每天的**最后一条**（sort 最大）
    const stretchLastBad = Number(sql(
      `SELECT COUNT(*) FROM (
         SELECT i.plan_day_id, MAX(i.sort) AS mx
         FROM fm_plan.plan_day_item i JOIN fm_plan.exercise e ON e.id = i.exercise_id
         WHERE i.plan_day_id IN (${dayIds}) AND e.category = '拉伸' GROUP BY i.plan_day_id) t
       WHERE t.mx <> (SELECT MAX(sort) FROM fm_plan.plan_day_item WHERE plan_day_id = t.plan_day_id)`))
    check('★ 拉伸是每个训练日的最后一条（sort 最大）', stretchLastBad === 0, `${stretchLastBad} 天不是`)
    const dist = pv.distribution || {}
    check('★ 运动分布三项和 = 1 且柔韧 > 0',
      Math.abs(Number(dist.strength) + Number(dist.cardio) + Number(dist.flexibility) - 1) < 0.011
      && Number(dist.flexibility) > 0,
      `${dist.strength}/${dist.cardio}/${dist.flexibility}`)
  }

  // ==================== 8. 字典动态化 ====================
  section('8. 动作字典：只返回库里真有数据的值')
  const dict = (await call('GET', '/exercises/dict')).json?.data
  check('dict 接口 200 且返回数据', !!dict, '')
  const cats = dict?.categories || []
  const parts = dict?.bodyParts || []
  const equips = dict?.equipments || []
  check('★★ categories = 2（拉伸/有氧）', cats.length === 2, cats.join('/'))
  check('★★ categories 里**没有"热身"**（死入口消失）', !cats.includes('热身'), cats.join('/'))
  check('★★ categories 里**没有"力量"**（它按部位切，不是一级筛选）', !cats.includes('力量'), cats.join('/'))
  check('★ bodyParts = 12 且含"其他"', parts.length === 12 && parts.includes('其他'), parts.join('/'))
  check('★★ equipments = 6', equips.length === 6, equips.join('/'))
  check('★ equipments 里没有"史密斯"/"悍马机"',
    !equips.includes('史密斯') && !equips.includes('悍马机'), equips.join('/'))
  // 排序稳定：连查两次顺序必须一样（否则前端左侧栏会抖）
  const dict2 = (await call('GET', '/exercises/dict')).json?.data
  check('★ 字典顺序稳定（连查两次一致）',
    JSON.stringify(dict2?.bodyParts) === JSON.stringify(parts)
    && JSON.stringify(dict2?.equipments) === JSON.stringify(equips))

  // ==================== 9. 图片可匿名访问 ====================
  section('9. 动作图：200 / image/jpeg / >10000 字节（三个都要断）')
  const list = (await call('GET', '/exercises?pageSize=5&onlyOnline=true')).json?.data
  const cards = list?.list || []
  check('动作库接口返回 5 条', cards.length === 5, String(cards.length))
  check('★ imageUrl 是**绝对地址**（DB 存 key，后端拼 base-url）',
    cards.every((c) => String(c.imageUrl || '').startsWith('http')), cards[0]?.imageUrl)
  let imgBad = []
  for (const c of cards.slice(0, 5)) {
    try {
      const r = await fetch(c.imageUrl)
      const buf = await r.arrayBuffer()
      const ctype = r.headers.get('content-type') || ''
      if (!(r.status === 200 && ctype.includes('image/jpeg') && buf.byteLength > 10000)) {
        imgBad.push(`${r.status}/${ctype}/${buf.byteLength} ${c.imageUrl}`)
      }
    } catch (e) {
      imgBad.push(`ERR ${c.imageUrl} ${e.message}`)
    }
  }
  check('★★ 5 张图全部 200 + image/jpeg + >10000 字节（不带签名参数直接 GET）',
    imgBad.length === 0, imgBad.slice(0, 2).join(' | ') || '5/5 通过')

  // ==================== 10. DB 里不该再有外链 ====================
  section('10. DB image_url：不再有外链，全部是对象 key')
  const ghfast = Number(sql("SELECT COUNT(*) FROM fm_plan.exercise WHERE image_url LIKE '%ghfast.top%'"))
  check('★★ image_url 里 ghfast.top = 0', ghfast === 0, String(ghfast))
  const httpLeft = Number(sql("SELECT COUNT(*) FROM fm_plan.exercise WHERE image_url LIKE 'http%'"))
  check('★★ image_url 以 http 开头的 = 0（全转成 key）', httpLeft === 0, String(httpLeft))
  const keyed = Number(sql("SELECT COUNT(*) FROM fm_plan.exercise WHERE image_url LIKE 'action-cover/exercise/%'"))
  check('★ 873 条全是 action-cover/exercise/ 形态的 key', keyed === 873, String(keyed))

  // ==================== 11. 食物条数 ====================
  section('11. 食物库：1638 条，16 条"热量缺失"的没进来')
  const foodTotal = Number(sql('SELECT COUNT(*) FROM fm_plan.food WHERE owner_id = 0'))
  check('★★ food 总行数 = 1638（去重后可写 1638，30 条种子全在其中）', foodTotal === 1638, String(foodTotal))
  const fakeKcal = Number(sql("SELECT COUNT(*) FROM fm_plan.food WHERE owner_id = 0 AND (kcal_per_100g IS NULL OR kcal_per_100g = 0)"))
  check('★ 没有"0 千卡的假食物"（缺热量的 16 条直接跳过，没塞 0）', fakeKcal === 0, String(fakeKcal))
  const skippedNames = ['蜂胶液', '蜂王浆', '白花菜', '艾蒿', '喷瓜']
  const found = Number(sql(
    `SELECT COUNT(*) FROM fm_plan.food WHERE name IN (${skippedNames.map((n) => `'${n}'`).join(',')})`))
  check('★★ 抽查 5 个"热量缺失"的名字，库里一个都没有', found === 0, String(found))
  const rice = Number(sql("SELECT kcal_per_100g FROM fm_plan.food WHERE name = '米饭（蒸，代表值）'"))
  check('★ 「米饭（蒸，代表值）」= 116 千卡/100g（人工核对口径）', rice === 116, String(rice))

  // ==================== 12. alias ====================
  section('12. 别名：379 条非空，且能**用接口**搜到')
  const aliasCount = Number(sql(
    "SELECT COUNT(*) FROM fm_plan.food WHERE owner_id = 0 AND alias IS NOT NULL AND alias <> ''"))
  check('★★ alias 非空 = 379 条（数据集里 391 条带［］，去重后 379）', aliasCount === 379, String(aliasCount))
  const dirtyNone = Number(sql("SELECT COUNT(*) FROM fm_plan.food WHERE alias = 'None'"))
  check('★ 没有字符串 "None" 的脏别名（sql_str 漏判 None 时的典型症状）', dirtyNone === 0, String(dirtyNone))
  const searched = (await call('GET', '/foods?keyword=' + encodeURIComponent('江米') + '&pageSize=20')).json?.data
  const hit = (searched?.list || []).find((f) => f.name === '糯米［江米］')
  check('★★ 接口搜"江米"能搜到「糯米［江米］」（证明 alias 真的参与搜索）', !!hit,
    (searched?.list || []).map((f) => f.name).slice(0, 3).join('/') || '无结果')

  // ==================== 13. "常见"推荐位 ====================
  section('13. "常见"推荐位：sort 偏移生效')
  const firstFew = rows('SELECT name, sort FROM fm_plan.food WHERE owner_id = 0 ORDER BY sort ASC, id ASC LIMIT 3')
  const firstName = firstFew[0]?.[0] || ''
  check('★★ "常见"第一位不是牛油/猪油（sort 偏移 10000 生效）',
    !/牛油|羊油|猪油|鸭油/.test(firstName), firstFew.map((r) => `${r[0]}/${r[1]}`).join(' '))
  const manual = Number(sql('SELECT COUNT(*) FROM fm_plan.food WHERE owner_id = 0 AND sort < 10000'))
  check('★ sort < 10000 的行数 = 30（人工挑的常见位没被导入行挤掉）', manual === 30, String(manual))
  const importedMin = Number(sql('SELECT MIN(sort) FROM fm_plan.food WHERE owner_id = 0 AND sort >= 10000'))
  check('导入行 sort 从 10001 起（1~9999 留给人工位）', importedMin === 10001, String(importedMin))

  // ==================== 14. 孤儿引用 ====================
  section('14. 孤儿引用：历史记录必须还能点开')
  const orphanItems = Number(sql(
    'SELECT COUNT(*) FROM fm_plan.plan_day_item i LEFT JOIN fm_plan.exercise e ON e.id = i.exercise_id '
    + 'WHERE e.id IS NULL'))
  check('★★ plan_day_item 指向的动作全部存在（孤儿 = 0）', orphanItems === 0, String(orphanItems))
  const orphanLogs = Number(sql(
    'SELECT COUNT(*) FROM fm_plan.workout_set_log l LEFT JOIN fm_plan.exercise e ON e.id = l.exercise_id '
    + 'WHERE e.id IS NULL'))
  check('★★ workout_set_log 指向的动作全部存在（孤儿 = 0）', orphanLogs === 0, String(orphanLogs))
  const officialPlans = Number(sql(
    'SELECT COUNT(DISTINCT plan_id) FROM fm_plan.plan_day WHERE plan_id IN '
    + '(SELECT id FROM fm_plan.plan WHERE source = 0)'))
  check('★ 官方计划仍有训练日（导入没动它们）', officialPlans >= 3, `${officialPlans} 个官方计划有训练日`)

  // ==================== 15. 食物池"能按份吃"门槛 ====================
  section('15. ★★ 食物池门槛：5 天偏差 ≤10%，奶是"能喝一杯"的，主食是"能按份吃"的')
  if (pv?.dietDraftId) {
    const budget = Number(pv.macros?.kcalBudget)
    const devs = (pv.dietDays || []).map((d) => Math.abs(Number(d.kcalTotal) - budget) / budget)
    check('★★ 每天热量与预算偏差 ≤ 10%（全量 1638 条下仍然成立）',
      devs.length === 5 && devs.every((x) => x <= 0.10),
      devs.map((x) => `${(x * 100).toFixed(1)}%`).join(' '))
    // 奶：只允许 ≤100 千卡/100g（250g = 一杯）
    const dairyBad = Number(sql(
      `SELECT COUNT(*) FROM fm_plan.diet_plan_meal_item i
       JOIN fm_plan.food f ON f.id = i.food_id
       JOIN fm_plan.diet_plan_meal m ON m.id = i.meal_id
       JOIN fm_plan.diet_plan_day d ON d.id = m.diet_plan_day_id
       WHERE d.diet_plan_id = ${pv.dietDraftId}
         AND f.category = '肉蛋奶' AND (f.name LIKE '%奶%')
         AND f.kcal_per_100g > 100`))
    check('★★ 排进计划的"奶"项全部 ≤100 千卡/100g（没有"奶粉 250g=1195 千卡"这种餐）',
      dairyBad === 0, `${dairyBad} 个高密度奶`)
    // 主食：≥200 千卡/100g（500g 马铃薯不是"一餐"）
    const stapleBad = Number(sql(
      `SELECT COUNT(*) FROM fm_plan.diet_plan_meal_item i
       JOIN fm_plan.food f ON f.id = i.food_id
       JOIN fm_plan.diet_plan_meal m ON m.id = i.meal_id
       JOIN fm_plan.diet_plan_day d ON d.id = m.diet_plan_day_id
       WHERE d.diet_plan_id = ${pv.dietDraftId}
         AND f.category = '主食' AND f.kcal_per_100g < 200`))
    check('★★ 排进计划的"主食"项全部 ≥200 千卡/100g（马铃薯/玉米这类低密度不进 AI 计划）',
      stapleBad === 0, `${stapleBad} 个低密度主食`)
    // 抽查一份"奶"的名称，证明它真的是液态奶而不是奶粉
    const dairyNames = rows(
      `SELECT DISTINCT f.name FROM fm_plan.diet_plan_meal_item i
       JOIN fm_plan.food f ON f.id = i.food_id
       JOIN fm_plan.diet_plan_meal m ON m.id = i.meal_id
       JOIN fm_plan.diet_plan_day d ON d.id = m.diet_plan_day_id
       WHERE d.diet_plan_id = ${pv.dietDraftId} AND f.name LIKE '%奶%' LIMIT 3`)
    check('★ 计划里的奶项看起来像"一杯"（纯牛奶/酸奶/豆浆）',
      dairyNames.length > 0 && dairyNames.every((r) => !/粉|酪|油|炼/.test(r[0])),
      dairyNames.map((r) => r[0]).join('/'))
  } else {
    check('preview 拿到 dietDraftId（否则第 15 组无法验证）', false, '')
  }

  // ==================== 汇总 ====================
  console.log('\n' + '='.repeat(74))
  console.log(`结果：通过 ${passed} 项 / 失败 ${failed} 项`)
  if (failed > 0) {
    console.log('\n失败明细：')
    failures.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
  } else {
    console.log('✅ 全部通过')
  }
  console.log('='.repeat(74))
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('\n💥 脚本异常：', e.message)
  process.exit(2)
})
