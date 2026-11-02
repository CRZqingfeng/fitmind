#!/usr/bin/env node
/**
 * ============================================================================
 * A32 验收：「停止执行计划」+ 「再定制一份」入口（后端）
 * ============================================================================
 * 背景（清单 §A32）：项目作者本人当第一个真实用户时找不到 AI 定制入口 ——
 * 「定制计划」按钮只存在于 NO_PLAN 态，一旦有执行中的计划，界面上**没有任何正常路径**
 * 换一份；而 `user_plan` 也没有"停止/放弃"接口，换错的计划只能一直挂着。
 *
 * 覆盖：
 *   1. 造一个"进行中 + 有未完成会话"的计划
 *   2. 停止执行 → user_plan=2、未完成会话=2、today 切走、mine 显示"已放弃"
 *   3. 幂等（连点两次不报错）；停止后该计划的训练日开不了新会话
 *   4. 重新执行 → 回到进行中（"再练一轮"路径没被堵死）
 *   5. 已经练完的计划不能被"停止"（已完成 ≠ 已放弃，两个语义不许互相覆盖）
 *   6. 没执行过的计划 / 别的用户 → 400，且不产生任何副作用
 *   7. 已完成的训练记录一条不动（累计训练分钟不能变小）
 *   8. 回归：P0 / P1 / P2 的关键接口仍可用
 *
 * ★★ 四条写法纪律（与 P0/P1/P2 一致）：
 *   ① 断言全部按**本次的 id** 限定范围，不用全表 count；
 *   ② SQL 执行失败必须 throw（不能把"连不上库"当成"数据为空"）；
 *   ③ 期望值按规则自己算，不抄接口返回值；
 *   ④ 改库的地方 try/finally 还原，并且**跑完把 today 恢复成跑之前那个计划**。
 *
 * 前置：
 *   · fm-plan(8095) 起（离线直连即可，默认就是）
 *   · 走真网关：`FM_GATEWAY=1 node scripts/accept-plan-a32.mjs`（需 Nacos + gateway + auth）
 *
 * 用法：
 *   node scripts/accept-plan-a32.mjs
 * ============================================================================
 */
import { spawnSync } from 'node:child_process'

const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const USER_ID = 1
const OTHER_USER_ID = 999
const STUDENT_PHONE = '13800010001'
const STUDENT_PASSWORD = '123456'

const DIRECT = process.env.FM_GATEWAY !== '1'
const BASE = DIRECT ? 'http://127.0.0.1:8095' : 'http://localhost:10010'
const PREFIX = DIRECT ? '' : '/pls'

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

/**
 * 发一个请求。
 * `asUser` 只在直连模式生效（走网关时伪造不了身份，会 401）。
 */
async function call(method, path, body, asUser = USER_ID) {
  const headers = { 'Content-Type': 'application/json' }
  if (DIRECT) {
    headers['user-info'] = String(asUser)
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
    type: 1, cellPhone: STUDENT_PHONE, password: STUDENT_PASSWORD, rememberMe: false,
  })
  const data = r.json?.data
  if (!data) {
    throw new Error(`登录失败：HTTP ${r.status} ${r.text.slice(0, 200)}`)
  }
  token = data
}

function todayWeekday() {
  const d = new Date().getDay()
  return d === 0 ? 7 : d
}

async function main() {
  console.log('='.repeat(78))
  console.log(`A32 验收：停止执行计划（${DIRECT ? '离线直连 8095' : '经网关 10010'}）`)
  console.log('='.repeat(78))

  await login()

  const stamp = new Date().toISOString().replace(/[-:T.Z]/g, '').slice(0, 14)
  const title = `验收-A32停止执行-${stamp}`

  // ★ 跑之前先把 today 记下来：脚本结束时能验"停掉自己这份之后回到原来那份"，
  //   而不是笼统地说"today 变了"
  const beforeToday = await call('GET', '/plans/today')
  const beforePlanId = beforeToday.json?.data?.planId ? String(beforeToday.json.data.planId) : null
  console.log(`\n（跑之前 today.planId = ${beforePlanId ?? 'null'}）`)

  // ★ 已完成的训练条数（只统计不属于本次新建计划的）：用来证明"停止"不动历史
  const finishedBefore = Number(sql(
    `SELECT COUNT(*) FROM fm_plan.workout_session WHERE user_id=${USER_ID} AND status=1`))

  let planId = ''
  let dayId = ''
  let userPlanId = ''
  let sessionId = ''

  try {
    // ==================== 1. 造数据 ====================
    section('1. 造一个进行中的计划（今天有训练日 + 一条未完成会话）')

    const created = await call('POST', '/plans', { title, goal: '增肌', difficulty: 0 })
    planId = String(created.json?.data ?? '')
    check('创建计划返回 planId', created.json?.code === 200 && !!planId,
      `HTTP ${created.status} ${created.text.slice(0, 120)}`)
    if (!planId) {
      throw new Error('没有 planId，后续无法继续')
    }

    const weekday = todayWeekday()
    await call('POST', `/plans/${planId}/days`, { weekdays: [weekday] })
    dayId = sql(`SELECT id FROM fm_plan.plan_day WHERE plan_id=${planId} AND weekday=${weekday}`)
    check(`训练日加在今天（weekday=${weekday}）`, !!dayId, `实际 [${dayId}]`)

    // 1=中握距卧推 2=俯卧撑（种子动作）
    await call('POST', `/plans/days/${dayId}/items`, { exerciseIds: [1, 2] })
    check('训练日里有 2 个动作',
      sql(`SELECT COUNT(*) FROM fm_plan.plan_day_item WHERE plan_day_id=${dayId}`) === '2')

    const exec = await call('POST', `/plans/${planId}/execute`)
    userPlanId = String(exec.json?.data ?? '')
    check('执行本计划成功（拿到 userPlanId）', !!userPlanId, JSON.stringify(exec.json))
    check('user_plan.status = 0（进行中）',
      sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`) === '0')

    // today 必须已经指向这份新计划（start_date 最新）
    const t1 = await call('GET', '/plans/today')
    check('★ 今日训练已指向这份计划（前置成立，否则后面的"切换"没有意义）',
      String(t1.json?.data?.planId) === planId,
      `today.planId=${t1.json?.data?.planId} 期望 ${planId}`)

    // 造一条"练到一半"的会话
    const start = await call('POST', '/workouts', { planDayId: Number(dayId) })
    sessionId = String(start.json?.data ?? '')
    check('开始训练拿到 sessionId（造出一条 status=0 的会话）', !!sessionId, JSON.stringify(start.json))
    check('会话状态是 0（进行中）',
      sql(`SELECT status FROM fm_plan.workout_session WHERE id=${sessionId}`) === '0')

    // ==================== 2. 停止执行 ====================
    section('2. 停止执行 → 状态、会话、today、我的计划四处一起变')

    const stop = await call('PUT', `/plans/${planId}/abandon`)
    check('停止执行返回成功', stop.json?.code === 200, `HTTP ${stop.status} ${stop.text.slice(0, 160)}`)

    check('★★ user_plan.status = 2（已放弃，不是复用 1"已完成"）',
      sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`) === '2',
      `实际 ${sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`)}`)

    check('★★ 未完成的会话被一并收掉（status=2），不留"永远进行中"的记录',
      sql(`SELECT status FROM fm_plan.workout_session WHERE id=${sessionId}`) === '2',
      `实际 ${sql(`SELECT status FROM fm_plan.workout_session WHERE id=${sessionId}`)}`)

    const t2 = await call('GET', '/plans/today')
    check('★ 今日训练不再指向这份被停掉的计划',
      String(t2.json?.data?.planId ?? '') !== planId,
      `today.planId=${t2.json?.data?.planId}`)

    check('★★ 停掉之后回到"跑之前那份计划"（有其他执行中计划时不至于变成空白）',
      String(t2.json?.data?.planId ?? '') === String(beforePlanId ?? ''),
      `today.planId=${t2.json?.data?.planId ?? 'null'} 期望 ${beforePlanId ?? 'null'}`)

    const mine = await call('GET', '/plans/mine')
    const mineRow = (mine.json?.data ?? []).find((p) => String(p.id) === planId)
    check('★「我的计划」里能查到这份（没被隐藏）', !!mineRow, JSON.stringify(mine.json).slice(0, 160))
    check('★「我的计划」里它的 status = 2', mineRow?.status === 2, `实际 ${mineRow?.status}`)
    check('★ 状态标签是「已放弃」（不是空串/不是"已完成"）',
      mineRow?.statusLabel === '已放弃', `实际 "${mineRow?.statusLabel}"`)

    // ==================== 3. 幂等 + 停止后开不了新训练 ====================
    section('3. 幂等 + 停止后不能开始新训练')

    const again = await call('PUT', `/plans/${planId}/abandon`)
    check('★ 连点第二次仍是成功（不弹红字让用户以为没生效）',
      again.json?.code === 200, `HTTP ${again.status} ${again.text.slice(0, 160)}`)
    check('连点第二次不改变状态（仍是 2）',
      sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`) === '2')

    const startAgain = await call('POST', '/workouts', { planDayId: Number(dayId) })
    check('★ 停止后该计划的训练日开不了新会话（400）',
      startAgain.json?.code === 400, JSON.stringify(startAgain.json).slice(0, 200))
    check('★ 报错文案说清了"已经结束 + 怎么接着练"',
      String(startAgain.json?.msg ?? '').includes('已经结束'),
      `实际 "${startAgain.json?.msg}"`)

    // ==================== 4. 重新执行 ====================
    section('4. 重新执行 → 回到进行中')

    const reExec = await call('POST', `/plans/${planId}/execute`)
    check('重新执行成功（"再练一轮"这条路没被堵死）',
      reExec.json?.code === 200, JSON.stringify(reExec.json).slice(0, 160))
    check('★ 重新执行把 status 重置回 0',
      sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`) === '0',
      `实际 ${sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`)}`)
    check('★ 重新执行不新增 user_plan 行（仍是同一条，靠唯一索引复用）',
      sql(`SELECT COUNT(*) FROM fm_plan.user_plan WHERE user_id=${USER_ID} AND plan_id=${planId}`) === '1')

    // ==================== 5. 已练完的计划不能被"停止" ====================
    section('5. 已经练完的计划 → 拒绝"停止"（已完成 ≠ 已放弃）')

    // 直接改库造"练完了"这个状态：练完所有训练日需要走完整套打卡流程，
    // 与本脚本要验的东西无关（P0 已经端到端验过"练完置 1"）
    sql(`UPDATE fm_plan.user_plan SET status=1 WHERE id=${userPlanId}`)
    const stopFinished = await call('PUT', `/plans/${planId}/abandon`)
    check('★ 对"已完成"的计划停止执行 → 400',
      stopFinished.json?.code === 400, JSON.stringify(stopFinished.json).slice(0, 200))
    check('★ 文案说明白了"已经练完了 + 下一步该干什么"',
      String(stopFinished.json?.msg ?? '').includes('练完'),
      `实际 "${stopFinished.json?.msg}"`)
    check('★★ 被拒之后状态仍是 1（没被偷偷改成 2）',
      sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`) === '1',
      `实际 ${sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`)}`)
    // 还原成进行中，后面还要用它
    sql(`UPDATE fm_plan.user_plan SET status=0 WHERE id=${userPlanId}`)

    // ==================== 6. 没执行过 / 别人的计划 ====================
    section('6. 没执行过的计划 / 别的用户 → 400，且不留副作用')

    let untouchedPlanId = sql(
      `SELECT id FROM fm_plan.plan WHERE source=0 AND id NOT IN `
      + `(SELECT plan_id FROM fm_plan.user_plan WHERE user_id=${USER_ID}) ORDER BY id LIMIT 1`)
    if (!untouchedPlanId) {
      untouchedPlanId = '999999'
    }
    const notMine = await call('PUT', `/plans/${untouchedPlanId}/abandon`)
    check(`没执行过的计划（id=${untouchedPlanId}）停止执行 → 400`,
      notMine.json?.code === 400, JSON.stringify(notMine.json).slice(0, 200))
    check('★ 文案引导去「执行本计划」，而不是干巴巴的"操作失败"',
      String(notMine.json?.msg ?? '').includes('还没有执行'),
      `实际 "${notMine.json?.msg}"`)
    check('★ 被拒之后没有凭空多出一条 user_plan',
      sql(`SELECT COUNT(*) FROM fm_plan.user_plan WHERE user_id=${USER_ID} AND plan_id=${untouchedPlanId}`) === '0')

    const otherUser = await call('PUT', `/plans/${planId}/abandon`, undefined, OTHER_USER_ID)
    check('★ 别的用户（999）停不了我的计划 → 400',
      otherUser.json?.code === 400, JSON.stringify(otherUser.json).slice(0, 200))
    check('★★ 越权请求之后我的计划仍是进行中',
      sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`) === '0',
      `实际 ${sql(`SELECT status FROM fm_plan.user_plan WHERE id=${userPlanId}`)}`)

    // ==================== 7. 历史记录不动 ====================
    section('7. 已完成的训练一条不动（累计分钟不能变小）')

    // 再停一次（这次是"进行中 → 已放弃"的正常路径），确认它不会误伤别处的已完成会话
    await call('PUT', `/plans/${planId}/abandon`)
    const finishedAfter = Number(sql(
      `SELECT COUNT(*) FROM fm_plan.workout_session WHERE user_id=${USER_ID} AND status=1`))
    check('★★ 已完成的训练会话条数没变', finishedAfter === finishedBefore,
      `${finishedBefore} → ${finishedAfter}`)
    check('本次这条会话仍是"已放弃"（状态没被反复横跳）',
      sql(`SELECT status FROM fm_plan.workout_session WHERE id=${sessionId}`) === '2')

    // ==================== 8. 回归 ====================
    section('8. 回归：P0 / P1 / P2 关键接口仍可用')

    const rec = await call('GET', '/plans/recommend')
    check('P0：/plans/recommend 仍 200 且有数据',
      rec.json?.code === 200 && (rec.json?.data ?? []).length > 0, JSON.stringify(rec.json).slice(0, 120))

    const diet = await call('GET', '/diet/today')
    check('P1：/diet/today 仍 200', diet.json?.code === 200, JSON.stringify(diet.json).slice(0, 120))

    const dict = await call('GET', '/exercises/dict')
    check('P0：/exercises/dict 仍 200', dict.json?.code === 200, JSON.stringify(dict.json).slice(0, 120))

    const detail = await call('GET', `/plans/${planId}`)
    check('★ 详情接口仍带 userPlanStatus（前端「停止执行」按钮靠它决定显不显示）',
      detail.json?.data?.userPlanStatus === 2,
      `实际 ${JSON.stringify(detail.json?.data?.userPlanStatus)}`)

    const anon = await fetch(BASE + PREFIX + `/plans/${planId}/abandon`, { method: 'PUT' })
    check('★ 匿名调停止执行 → 401（没漏进白名单）', anon.status === 401, `HTTP ${anon.status}`)
  } finally {
    // ==================== 收尾：把 today 恢复成跑之前的样子 ====================
    // ★ 不这么做的话，每跑一次回归就多一份"进行中"的计划，会悄悄改掉下一个脚本的前置
    if (planId) {
      call('PUT', `/plans/${planId}/abandon`).catch(() => {})
    }
    if (beforePlanId) {
      call('POST', `/plans/${beforePlanId}/execute`).catch(() => {})
    }
  }

  // 恢复后复查一次（放 finally 外面，失败了也要看结果）
  await new Promise((r) => setTimeout(r, 200))
  const afterToday = await call('GET', '/plans/today')
  console.log(`\n（跑完 today.planId = ${afterToday.json?.data?.planId ?? 'null'}）`)

  console.log('\n' + '='.repeat(78))
  console.log(`本次：planId=${planId} · dayId=${dayId} · userPlanId=${userPlanId} · sessionId=${sessionId}`)
  console.log(`结果：通过 ${passed} 项 / 失败 ${failed} 项`)
  if (failed > 0) {
    console.log('\n失败项：')
    failures.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
    process.exit(1)
  }
  console.log('全部通过 ✅')
  console.log('='.repeat(78))
}

main().catch((e) => {
  console.error('\n脚本异常终止：', e)
  process.exit(1)
})
