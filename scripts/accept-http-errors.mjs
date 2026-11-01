#!/usr/bin/env node
/**
 * ============================================================================
 * 通用回归：请求"格式不对"时必须是 4xx，不能是 500（《待修复问题清单》#A28）
 * ============================================================================
 * 背景：`fm-common` 的 `CommonExceptionAdvice` 曾经把「路径参数类型错 / body JSON 坏 /
 * 方法不支持 / 路径不存在」全部兜成 **500「系统繁忙」**，其中路径不存在那条连 HTTP
 * 状态都是 200 —— 巡检和监控完全看不出这是客户端问题。
 *
 * 修完之后最怕的是"以后又有人加了个裸的 `@PathVariable Long`，并且把 handler 删了"。
 * 所以把它固化成脚本：**任何一个服务都能跑**，只要把 PORT 传进去。
 *
 * 用法：
 *   node scripts/accept-http-errors.mjs                  # 默认打 fm-plan(8095)
 *   PORT=8086 node scripts/accept-http-errors.mjs        # 打 fm-learning
 *   BASE=http://127.0.0.1:8095 node scripts/accept-http-errors.mjs
 *
 * ★ 为什么用 `--noproxy` 那种思路：本机环境变量里配了 HTTP_PROXY（127.0.0.1:8743），
 *   Node 的 fetch 默认不走它，但 curl 会。这个脚本用 fetch，所以不受影响 —— 记一笔，
 *   免得下次用 curl 手工验时被 502「upstream connect failed」误导。
 *
 * ⚠️ 前置：被验证的服务要**起了**，且这些探测点在该服务上存在（见 PROBES）。
 * ============================================================================
 */

const PORT = Number(process.env.PORT || 8095)
const BASE = process.env.BASE || `http://127.0.0.1:${PORT}`
const USER_ID = process.env.USER_ID || '1'

let passed = 0
let failed = 0
const failures = []

function check(name, ok, detail = '') {
  if (ok) {
    passed++
    console.log(`  ✅ ${name}`)
  } else {
    failed++
    failures.push(name + (detail ? ` —— ${detail}` : ''))
    console.log(`  ❌ ${name}${detail ? ` —— ${detail}` : ''}`)
  }
}

/** 统一带"模拟网关"的两个头：直连服务端口时靠它们拿到登录用户（同 accept-plan-*.mjs） */
async function call(method, path, body, rawBody) {
  const headers = { 'user-info': USER_ID, 'request-from': 'gateway' }
  let payload
  if (rawBody !== undefined) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json'
    payload = rawBody
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const res = await fetch(BASE + path, { method, headers, body: payload })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON（可能是 404 的静态页） */
  }
  return { status: res.status, json, text }
}

async function main() {
  console.log('='.repeat(78))
  console.log(`A28 回归：请求格式错误必须是 4xx（目标 ${BASE}）`)
  console.log('='.repeat(78))

  try {
    await fetch(`${BASE}/plans/today`, { headers: { 'user-info': USER_ID, 'request-from': 'gateway' } })
  } catch (e) {
    console.error(`\n❌ 前置不满足：${BASE} 不可达 —— ${e.message}`)
    process.exit(2)
  }

  console.log('\n【1. 路径参数类型错 → 400】')
  {
    const r = await call('GET', '/plans/abc')
    check('HTTP 400', r.status === 400, `实际 ${r.status}`)
    check('body code=400 且文案点名参数', r.json?.code === 400 && String(r.json?.msg || '').includes('格式'),
      `code=${r.json?.code} msg=${r.json?.msg}`)
  }

  console.log('\n【2. 日期参数坏 → 400（带 yyyy-MM-dd 提示）】')
  for (const bad of ['abc', '2018-13-45', '2026-02-30']) {
    const r = await call('GET', `/diet/today?date=${bad}`)
    check(`date=${bad} → 400`, r.status === 400, `实际 ${r.status}`)
    check(`date=${bad} 文案提示了格式`, String(r.json?.msg || '').includes('yyyy-MM-dd'),
      `msg=${r.json?.msg}`)
  }

  console.log('\n【3. body JSON 坏 / 字段类型错 / 空 body → 400】')
  {
    const badJson = await call('POST', '/plans', undefined, '{"title": bad}')
    check('JSON 语法错 → 400', badJson.status === 400, `实际 ${badJson.status}`)
    const badType = await call('POST', '/plans', { difficulty: 'abc' })
    check('字段类型不符 → 400', badType.status === 400, `实际 ${badType.status}`)
    const empty = await call('POST', '/plans', undefined, '')
    check('空 body → 400', empty.status === 400, `实际 ${empty.status}`)
  }

  console.log('\n【4. 请求方法不支持 → 405】')
  {
    const r = await call('PATCH', '/plans/today')
    check('PATCH 只支持 GET 的接口 → 405', r.status === 405, `实际 ${r.status}`)
  }

  console.log('\n【5. 路径不存在 → 404（不能是 200/500）】')
  {
    const r = await call('GET', '/nope-not-exist')
    check('HTTP 404', r.status === 404, `实际 ${r.status}`)
    check('body code=404（不是 500）', r.json?.code === 404, `code=${r.json?.code}`)
  }

  console.log('\n【6. 分页参数传空值 → 回落默认值，不能 NPE】')
  for (const p of ['/exercises?pageNo=', '/exercises?pageSize=', '/foods?pageNo=', '/sports?pageNo=']) {
    const r = await call('GET', p)
    check(`${p} → 200 且有数据`, r.status === 200 && (r.json?.data?.list || []).length > 0,
      `status=${r.status} total=${r.json?.data?.total}`)
  }
  {
    // ★ 刻意保留的旧语义：pageSize=0 表示"不算总页数"，不能被顺手 coerce 成默认值
    const r = await call('GET', '/exercises?pageSize=0')
    check('pageSize=0 的旧语义仍在（pages=0 且不返回数据）',
      r.status === 200 && String(r.json?.data?.pages) === '0' && (r.json?.data?.list || []).length === 0,
      `pages=${r.json?.data?.pages} list=${(r.json?.data?.list || []).length}`)
  }

  console.log('\n【7. 不能被"修坏"的三条】')
  {
    const ok = await call('GET', '/plans/today')
    check('正常接口仍 200', ok.status === 200 && ok.json?.code === 200, `status=${ok.status}`)

    // 业务校验必须是「HTTP 200 + code 400」（项目惯例），不能被改成 HTTP 400
    const biz = await call('POST', '/plans/days/1/items', { exerciseIds: [] })
    check('业务校验仍是 HTTP 200 + code=400', biz.status === 200 && biz.json?.code === 400,
      `status=${biz.status} code=${biz.json?.code}`)

    const anon = await fetch(`${BASE}/plans/today`)
    check('匿名仍 401（鉴权没被这条改动影响）', anon.status === 401, `实际 ${anon.status}`)
  }

  console.log('\n' + '='.repeat(78))
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
