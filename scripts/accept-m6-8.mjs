#!/usr/bin/env node
/**
 * ============================================================================
 * M6-8 第 2 期验收：工具契约（按 ID 可用性）
 * ============================================================================
 * 覆盖：#12（按课程 ID 咨询/购买走不通）· #13（工具描述与实现不符）· #14（目录静默截断）
 *
 * 前置：
 *   · fm-aigc 必须以 **`AI_MODE=mock`** 启动（脚本会先检查 `/ais/chat/provider`）
 *   · 演示账号 13800010001（id=1）· 课程 535「零基础哑铃增肌 12 周计划」
 *     （3 个训练日 / 11 个训练动作 / 有效期 365 天 / 难度零基础 / 目标增肌）
 *
 * 用法：node scripts/accept-m6-8.mjs
 *
 * ★★ 本脚本的验收思路（值得单独说一句）：
 *
 *   `#12` 的修复本质是「**发给模型的工具描述**放宽了」，而这件事**只有真实模型
 *   才表现得出来**，真实模型又是非确定的 —— "它这次恰好照着新约束做了"
 *   不能当作"约束改对了"的证据。
 *
 *   所以这里断言的是 **工具描述这个确定性的文本**（`aigc:mock:tools` 快照，
 *   由 MockChatModel 写出），而不是"模型这次的行为"。
 *   ★ 一句话：**模型的行为可以变，发给它的规则不会。**
 *
 *   而 `#13`/`#14` 是**工具输出**的问题，可以用 mock 确定性驱动：
 *   mock 会把工具返回的原文回显进回答，所以断言直接落在回答正文上。
 * ============================================================================
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import fs from 'node:fs'

const GATEWAY = 'http://localhost:10010'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const STUDENT_PHONE = '13800010001'
const STUDENT_PASSWORD = '123456'
const STUDENT_USER_ID = 1

const PROVIDER_API = '/ais/chat/provider'
const CHAT_API = '/ais/chat'
/** 工具定义快照（M6-8 第 2 期新增，见 AigcConstants.MOCK_TOOLS_SNAPSHOT_KEY） */
const TOOLS_SNAPSHOT_KEY = 'aigc:mock:tools'
const PROMPT_SNAPSHOT_KEY = 'aigc:mock:prompts'

/** 被验证的课程：3 个训练日 / 11 个动作 / 14 条目录 / 365 天 / 零基础 / 增肌 */
const COURSE_ID = 535
const COURSE_EXPECT = {
  sectionNum: 11,
  validDuration: 365,
  catalogueTotal: 14,
  days: 3,
  day3Action: '俯身反向飞鸟',
}

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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** ★ 查询失败必须炸出来（否则"查询挂了"会伪装成"数据为空"） */
function checkCmdResult(kind, cmd, r) {
  if (r.error) {
    throw new Error(`${kind} 执行失败（进程无法启动）：${cmd}\n${r.error.message}`)
  }
  if (r.status !== 0) {
    throw new Error(`${kind} 执行失败（退出码 ${r.status}）：${cmd}\n${(r.stderr || '').trim()}`)
  }
}

/** ⚠️ `stdio` 的 stdin 必须是 ignore（默认 pipe 在本机受限环境会 EBUSY，且**不抛异常**） */
function sql(query) {
  const r = spawnSync(MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', query],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  checkCmdResult('SQL', query, r)
  return (r.stdout || '').trim()
}

function envGet(key) {
  const env = fs.readFileSync('docker/.env', 'utf8')
  return (env.match(new RegExp(`^${key}=(.*)$`, 'm')) || [])[1]?.trim()
}

function redis(...args) {
  const r = spawnSync('docker',
    ['exec', 'fm-redis', 'redis-cli', '-a', envGet('REDIS_PASSWORD'), '--no-auth-warning', ...args],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  checkCmdResult('REDIS', `redis-cli ${args.join(' ')}`, r)
  return (r.stdout || '').trim()
}

async function call(method, path, { body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = token
  const res = await fetch(GATEWAY + path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: res.status, json, text }
}

/** 用**密码**登录（避开验证码 60 秒频控，本脚本要跑好几轮对话） */
async function login() {
  const r = await call('POST', '/as/accounts/login', {
    body: { type: 1, cellPhone: STUDENT_PHONE, password: STUDENT_PASSWORD, rememberMe: false },
  })
  const token = r.json?.data
  if (!token) throw new Error(`登录失败：${JSON.stringify(r.json)}`)
  return token
}

/**
 * 管理员登录。
 *
 * ★ 必须走 **`/as/accounts/admin/login`**（管理端专用入口）：
 *   用户端那个 `/as/accounts/login` 目前**只有 id=1 学员能过**，
 *   教练/管理员会稳定 500（那是清单里的 **#A25**，与本脚本无关）。
 */
async function loginAdmin() {
  const r = await call('POST', '/as/accounts/admin/login', {
    body: { type: 1, cellPhone: '13800010003', password: '123456', rememberMe: false },
  })
  const token = r.json?.data
  if (!token) throw new Error(`管理员登录失败：${JSON.stringify(r.json)?.slice(0, 200)}`)
  return token
}

/** 跑一轮对话，返回 {agentType, toolNames, cards, content, invocations, sessionId} */
async function chat(token, content, sessionId) {
  const r = await call('POST', CHAT_API, { body: { content, sessionId }, token })
  const data = r.json?.data
  return {
    agentType: data?.agentType,
    toolNames: data?.toolNames || [],
    cards: data?.cards || [],
    content: normalizeNewlines(data?.content),
    invocations: data?.toolCalls?.invocations || [],
    sessionId: data?.sessionId,
  }
}

/**
 * ★★ 换行归一化 —— 这个坑值得单独说。
 *
 * <p>一轮回答里混着**两种换行**：</p>
 * <ul>
 *   <li>Mock 自己拼的那段（"我查了一下，结果如下：\n"）是**真换行**；</li>
 *   <li><b>工具返回值里的换行是「字面量 `\` + `n`」</b> —— 因为 Spring AI 把
 *       工具的 String 返回值当对象序列化了一次，`\n` 被转义成了两个字符。</li>
 * </ul>
 *
 * <p>后果很隐蔽：**打印出来长得一模一样**，但 `content.split('\n')` 切不开 ——
 * 于是"按行断言"全部落空（第一版就在这里假红了 1 条）。</p>
 *
 * <p>★ 统一成真换行后再断言：<b>断言关心的是"内容在不在"，不是"它以哪种方式换行"。</b></p>
 */
function normalizeNewlines(s) {
  return String(s ?? '').replace(/\\r\\n|\\n/g, '\n')
}

/**
 * 从工具快照里取出某个工具的**完整描述**（多行）。
 *
 * <p>⚠️ 第一版只取了 `name ::` 那**一行**，而描述是**多行文本块** ——
 * 于是"用法：……核实……"和"不要编造"这些关键句子全在后面几行，断言必然落空。
 * 正确做法：一直取到**下一个工具**（下一个 `xxx :: ` 行）之前。</p>
 */
function toolDescription(snapshot, name) {
  const lines = String(snapshot || '').split('\n')
  const start = lines.findIndex((l) => l.startsWith(`${name} ::`))
  if (start < 0) {
    return ''
  }
  const out = [lines[start].slice(name.length + 4)]
  for (let i = start + 1; i < lines.length; i++) {
    if (/^\S+ :: /.test(lines[i])) {
      break
    }
    out.push(lines[i])
  }
  return out.join('\n')
}

/**
 * 跑 ToolTrace 卡片治理探针（编译 + 运行 + 解析 `KEY=value`）。
 *
 * <p>★ 只依赖 `fm-aigc/target/classes`（`ToolTrace` 没有第三方依赖），
 * 所以**不需要**导出 Maven 的全量 classpath —— 也就绕开了"命令行 32KB 上限"那个坑（见 §3.40a）。</p>
 *
 * <p>★ `javac` 必须显式 `-encoding UTF-8`：Windows 中文环境下它默认按 **GBK** 读源码，
 * 而探针源码是 UTF-8 的 → 满屏"非法字符"。</p>
 *
 * <p>★ `java` 要带 `-Dfile.encoding=UTF-8`：否则探针打印的中文在 GBK 控制台里是乱码，
 * 断言"关键词里有没有 `+`"会莫名其妙地失败。</p>
 */
function runToolTraceProbe() {
  const root = process.cwd()
  const classes = `${root}/fm-aigc/target/classes`
  const outDir = join(tmpdir(), `m68-probe-${Date.now()}`)
  const javac = 'D:/develop/jdk-17/bin/javac'
  const java = 'D:/develop/jdk-17/bin/java'

  mkdirSync(outDir, { recursive: true })
  const c = spawnSync(javac,
    ['-encoding', 'UTF-8', '-cp', classes, '-d', outDir, `${root}/scripts/probe/ToolTraceProbe.java`],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (c.status !== 0) {
    throw new Error(`探针编译失败：\n${c.stderr || c.stdout}`)
  }
  const r = spawnSync(java,
    ['-Dfile.encoding=UTF-8', '-cp', `${classes};${outDir}`, 'ToolTraceProbe'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (r.status !== 0) {
    throw new Error(`探针运行失败：\n${r.stderr || r.stdout}`)
  }
  const out = {}
  for (const line of String(r.stdout || '').split(/\r?\n/)) {
    const i = line.indexOf('=')
    if (i > 0) {
      out[line.slice(0, i).trim()] = line.slice(i + 1).trim()
    }
  }
  return out
}

/** Windows 风格路径 → 正斜杠（argfile 里 `\` 是转义符，`C:\Users` 会被吃成 `C:Users`） */
const mixedPath = (p) => String(p).replace(/\\/g, '/')

/**
 * 跑 **#9 工具超限拦截** 探针（编译 + 运行 + 解析 `✅/❌` 行）。
 *
 * <p>★ 它比 `runToolTraceProbe` 麻烦，原因是**它要 new 出真正的工具对象**：
 * <ol>
 *   <li>`CourseTools` / `OrderTools` 的类型签名里带着 `SearchClient` / `OrderClient`
 *       （Feign 接口，在 `fm-api`），运行时还要初始化 `@Slf4j` 的 logger
 *       → <b>必须给全量 classpath</b>，不能像 ToolTrace 探针那样只带 `target/classes`。</li>
 *   <li>而 `fm-aigc` 的依赖 classpath 有 **30KB**（30970 字节，实测），
 *       正好贴着 Windows 命令行 **32767** 字符的上限
 *       → 走 <b>argfile（`@file`）</b>；javac 和 java 都支持。</li>
 * </ol>
 *
 * <p>★ 这个"探针要不要全量 classpath"的分界，本身就是个可复用的判据：
 * <b>探针只碰"纯逻辑类"就轻装；一旦要构造"带依赖的组件"，就得把 classpath 备齐。</b></p>
 */
function runToolBlockedProbe() {
  const root = process.cwd()
  const classes = `${root}/fm-aigc/target/classes`
  const stamp = Date.now()
  const outDir = join(tmpdir(), `m68-blocked-${stamp}`)
  const cpFile = join(tmpdir(), `m68-cp-${stamp}.txt`)
  const javacArgsFile = join(tmpdir(), `m68-javac-${stamp}.txt`)
  const javaArgsFile = join(tmpdir(), `m68-java-${stamp}.txt`)
  const javac = 'D:/develop/jdk-17/bin/javac'
  const java = 'D:/develop/jdk-17/bin/java'
  const bash = 'D:/develop/Git/bin/bash.exe'

  mkdirSync(outDir, { recursive: true })

  // ① 导出 fm-aigc 的运行时依赖（约 10 秒；用绝对路径调 bash，避免 PATH 里没有 bash）
  //    ⚠️ -Dmdep.outputFile 要传 **Windows 路径**：mvn.sh 跑在 Git Bash 里，
  //       但 maven 自己按 Java 的文件 API 解析这个参数。
  const dep = spawnSync(bash,
    ['scripts/mvn.sh', '-q', '-pl', 'fm-aigc', 'dependency:build-classpath',
      `-Dmdep.outputFile=${mixedPath(cpFile)}`, '-DincludeScope=runtime'],
    { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (dep.status !== 0) {
    throw new Error(`导出 classpath 失败：\n${dep.stderr || dep.stdout}`)
  }
  // ⚠️⚠️ `deps` 是 maven 写的**反斜杠**路径（`D:\develop\Maven\...\xxx.jar`）。
  //     必须把 `\` 也换成 `/` —— argfile 里的 `\` 是**转义符**，不换的话
  //     每一个 jar 的路径都会被吃掉（`D:\develop` → `D:develop`）→ 表现为
  //     `NoClassDefFoundError: org/slf4j/LoggerFactory` 这种"看起来跟路径毫无关系"的报错。
  //     ★ 这个是同一个坑的**第三次**出现（前两次：`root` 拼接、探针源码路径）——
  //       教训就一条：**进 argfile 的每一段路径都要正斜杠，一处都不能漏。**
  const deps = fs.readFileSync(cpFile, 'utf8').replace(/\r?\n/g, '').replace(/\\/g, '/')
  // ⚠️ **拼接的每一段**都要转正斜杠 —— 少转一段，argfile 解析时那一段的 `\` 就会被
  //    当转义符吃掉（实测把 `D:\develop\develop\code\fitmind/...` 吃成了
  //    `D:developdevelopcodeitmind/...`，javac 报"无效的文件名"）。
  const cp = [
    mixedPath(classes),
    `${mixedPath(root)}/fm-api/target/classes`,
    `${mixedPath(root)}/fm-common/target/classes`,
    deps,
    mixedPath(outDir),
  ].join(';')

  // ② 编译（也走 argfile —— 同一个 32KB 上限，javac 一样躲不开）
  const probeSrc = `${mixedPath(root)}/scripts/probe/ToolBlockedProbe.java`
  fs.writeFileSync(javacArgsFile,
    `-encoding UTF-8\n-cp "${cp}"\n-d "${mixedPath(outDir)}"\n"${probeSrc}"\n`,
    'utf8')
  const c = spawnSync(javac, [`@${mixedPath(javacArgsFile)}`],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (c.status !== 0) {
    throw new Error(`#9 探针编译失败：\n${c.stderr || c.stdout}`)
  }

  // ③ 运行
  fs.writeFileSync(javaArgsFile, `-cp "${cp}"\nToolBlockedProbe\n`, 'utf8')
  const r = spawnSync(java, ['-Dfile.encoding=UTF-8', `@${mixedPath(javaArgsFile)}`],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  if (r.status !== 0) {
    throw new Error(`#9 探针运行失败：\n${r.stderr || r.stdout}`)
  }
  const stdout = String(r.stdout || '')
  const failed = (stdout.match(/^\s*❌/gm) || []).length
  return { stdout, failed, lines: stdout.split(/\r?\n/).filter((l) => /^\s*(✅|❌)/.test(l)) }
}

/**
 * 跑一轮 **SSE** 对话，返回 `{ text, stop, status }`。
 *
 * <p>为什么 #18 必须走 SSE：**限流那一轮的 sessionId 只出现在 STOP 事件里**。
 * 非流式接口（`POST /ais/chat`）返回的是 `ChatResultVO`，字段形状与 STOP 载荷不同 ——
 * 用非流式去断言"STOP 里带了 sessionId"，等于没断言。</p>
 *
 * <p>★ 事件名是**大写**的 `DATA` / `PARAM` / `STOP`（见 `AigcConstants.EVENT_*`）。
 * SSE 帧用空行分隔，`event:` 与 `data:` 各占一行。</p>
 */
async function chatSse(token, content, sessionId) {
  const res = await fetch(`${GATEWAY}/ais/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token },
    body: JSON.stringify(sessionId ? { content, sessionId } : { content }),
  })
  const raw = await res.text()
  let text = ''
  let stop = null
  for (const frame of raw.split('\n\n')) {
    const ev = /^event:\s*(.*)$/m.exec(frame)?.[1]?.trim()
    const data = [...frame.matchAll(/^data:\s?(.*)$/gm)].map((m) => m[1]).join('\n')
    if (ev === 'DATA') {
      text += data
    } else if (ev === 'STOP') {
      try {
        stop = JSON.parse(data)
      } catch {
        stop = { raw: data }
      }
    }
  }
  return { text: normalizeNewlines(text), stop, status: res.status }
}

async function main() {
  console.log('============================================================')
  console.log(' M6-8 第 2 期验收：工具契约（按 ID 可用性）')
  console.log('============================================================')

  // ================================================================
  section('0. 前置：模型通道必须为 mock（否则断言会失真且烧真实配额）')
  // ================================================================
  const token = await login()
  const provider = (await call('GET', PROVIDER_API, { token })).json?.data
  check('fm-aigc 处于 mock 通道', provider === 'mock', `当前=${provider}`)
  if (provider !== 'mock') {
    console.error(`\n❌ 前置不满足：请以 AI_MODE=mock 重启 fm-aigc 后再跑（当前 ${provider}）`)
    process.exit(2)
  }
  check('课程 535 数据与预期一致（前置数据就是这个脚本的依据）', (() => {
    const row = sql(`SELECT CONCAT(section_num,'|',valid_duration,'|',training_level) FROM fm_course.course WHERE id=${COURSE_ID}`)
    return row === `${COURSE_EXPECT.sectionNum}|${COURSE_EXPECT.validDuration}|1`
  })(), sql(`SELECT CONCAT(section_num,'|',valid_duration,'|',training_level) FROM fm_course.course WHERE id=${COURSE_ID}`))

  // 清掉上一轮快照：**必须先清**，否则读到的是"上一次跑留下来的"（另一种假绿）
  redis('DEL', TOOLS_SNAPSHOT_KEY, PROMPT_SNAPSHOT_KEY)

  const sid = `accept-m68-${Date.now()}`

  // ================================================================
  section('1. ★★ #12 端到端：用户**直接报 ID** 能走通（以前必然被拒）')
  // ================================================================
  const ask = `课程 ${COURSE_ID} 一共有几节？目录是什么？`
  const ans = await chat(token, ask, sid)
  check('★★ 工具轨迹里出现了 queryCourseDetail（用户给的 id 被直接拿去核实，而不是被逼着先搜）',
    ans.toolNames.includes('queryCourseDetail'), `tools=${ans.toolNames}`)
  check('★★ 核实用的就是用户给的那个 id（参数没被模型改掉）',
    ans.invocations.some((i) => i.name === 'queryCourseDetail' && String(i.args).includes(String(COURSE_ID))),
    JSON.stringify(ans.invocations.map((i) => `${i.name}(${i.args})`)))
  check('★ 工具执行成功（不是"没找到"）',
    !ans.invocations.some((i) => /没找到|不存在/.test(String(i.result) + String(i.error))),
    JSON.stringify(ans.invocations.map((i) => i.result)))

  // ================================================================
  section('2. ★★★ #12 的**实质**：发给模型的工具描述已放宽（确定性文本）')
  // ================================================================
  // ★ 这一段是本脚本的核心：不去赌"模型这次听话了"，而是断言**规则本身变了**
  const tools = redis('LINDEX', TOOLS_SNAPSHOT_KEY, '0')
  check('工具定义快照已落库（本轮挂载的工具与描述）',
    tools.includes('queryCourseDetail') && tools.includes('searchCourses'),
    String(tools).slice(0, 120))

  const qcdDesc = toolDescription(tools, 'queryCourseDetail')
  const scDesc = toolDescription(tools, 'searchCourses')
  check('★★ queryCourseDetail 的描述里明确允许「核实用户给的 id」',
    /用户/.test(qcdDesc) && /核实/.test(qcdDesc), qcdDesc.slice(0, 110))
  check('★★ queryCourseDetail 的描述里**不再有**"必须来自 searchCourses 的结果"这条硬前置',
    !/必须来自\s*searchCourses|id 必须来自/.test(qcdDesc), qcdDesc.slice(0, 110))
  check('★★ searchCourses 的描述里**不再有**"推荐课程之前必须先调用它"（降级为"不确定时优先"）',
    !/必须先调用它|之前必须先/.test(scDesc), scDesc.slice(0, 110))
  // ★ 放宽 ≠ 放开：底线必须还在
  check('★★★ 底线未松：仍然明令"不要编造 / 不要凭印象编课程 id"',
    /不要编造|不要凭印象编/.test(qcdDesc + scDesc), `${qcdDesc.slice(0, 60)} | ${scDesc.slice(0, 60)}`)

  // ★ 提示词侧：buy.txt 的工具清单以前只列了 2 个工具（漏了 queryCourseDetail）
  const promptSnap = redis('LINDEX', PROMPT_SNAPSHOT_KEY, '0')
  check('★★ 系统提示词的工具清单里补上了 queryCourseDetail（以前只列了 2 个工具）',
    promptSnap.includes('queryCourseDetail'), String(promptSnap).slice(0, 100))

  // ================================================================
  section('3. ★★ #13 工具描述与实现对齐（承诺的字段必须真给得出）')
  // ================================================================
  const c = ans.content
  check('★★ 补上了「难度」，且翻成中文（不是把 1 原样丢给模型）',
    /难度：零基础/.test(c), c.match(/难度：[^\n]*/)?.[0] || '（没有难度行）')
  check('★★ 补上了「训练目标」，同样翻成中文',
    /训练目标：增肌/.test(c), c.match(/训练目标：[^\n]*/)?.[0] || '（没有训练目标行）')
  check('★ 补上了「有效期」（库里一直有，以前不输出）',
    new RegExp(`有效期：购买后 ${COURSE_EXPECT.validDuration} 天`).test(c),
    c.match(/有效期：[^\n]*/)?.[0] || '（没有有效期行）')
  check('★ 节数行说明了口径（是"训练动作数"，不是"训练日数"）',
    /11 节/.test(c), c.match(/节数：[^\n]*/)?.[0] || '（没有节数行）')
  check('★★★ 不再出现实现**根本给不出**的「器械要求」（旧描述承诺了它，库里没有这个字段）',
    !/器械/.test(c), (c.match(/[^\n]*器械[^\n]*/) || [''])[0].slice(0, 80))

  // ================================================================
  section('4. ★★ #14 目录不再静默截断（按训练日聚合 + 自证完整）')
  // ================================================================
  check('★★★ 目录里出现了 Day 3（旧实现 limit(8) 正好把它切掉）',
    /Day 3/.test(c), c.match(/Day 3[^\n]*/)?.[0]?.slice(0, 90) || '（没有 Day 3）')
  check('★★ Day 3 的**动作名**也在（不是只有个标题）',
    c.includes(COURSE_EXPECT.day3Action), `${COURSE_EXPECT.day3Action} 未出现`)
  check('★★★ 开头声明「共 N 条 / M 个训练日」——被截断时也知道总量，不会把"我看到的"当"全部"',
    new RegExp(`共 ${COURSE_EXPECT.catalogueTotal} 条\\s*/\\s*${COURSE_EXPECT.days} 个训练日`).test(c),
    c.match(/课程目录[^\n]*/)?.[0] || '（没有目录头）')
  check('★★ 动作挂在所属训练日下面（不再是一串扁平名字）',
    (() => {
      const line = c.split('\n').find((l) => l.includes('Day 1')) || ''
      return line.includes('：') && line.split('：')[1].includes('、')
    })(),
    c.match(/Day 1[^\n]*/)?.[0]?.slice(0, 90) || '')
  check('★ 三个训练日都在（Day 1 / Day 2 / Day 3）',
    /Day 1/.test(c) && /Day 2/.test(c) && /Day 3/.test(c))

  // ================================================================
  section('5. 负向：编造的 id 仍被挡住（"放宽"不等于"放开"）')
  // ================================================================
  const FAKE_ID = 99999999
  const fake = await chat(token, `课程 ${FAKE_ID} 有几节？目录是什么？`, sid)
  check('★★ 编造的 id → 明确回"没找到"（人话，不是异常）',
    /没找到/.test(fake.content), fake.content.slice(0, 110))
  check('★ 核实过（工具真的被调了），不是模型凭空断言"不存在"',
    fake.toolNames.includes('queryCourseDetail'), `tools=${fake.toolNames}`)
  check('★★ 没有产生任何订单卡片（不会拿着一个不存在的 id 去下单）',
    !fake.cards.some((x) => x.type === 'ORDER_CONFIRM'),
    JSON.stringify(fake.cards.map((x) => x.type)))
  check('★ 提示给出了下一步（"如实告诉用户没找到" / "先搜真实 id"）',
    /如实告诉他没找到|searchCourses/.test(fake.content), fake.content.slice(-110))

  // ================================================================
  section('6. 回归保护：原有能力没被这次放宽搞坏')
  // ================================================================
  const rec = await chat(token, '推荐一门适合新手的哑铃课程', sid)
  check('★ 关键词搜索路径仍然可用（searchCourses 照常触发）',
    rec.toolNames.includes('searchCourses'), `tools=${rec.toolNames}`)
  check('★ 课程卡片仍然回传（放宽描述没有影响卡片）',
    rec.cards.some((x) => x.type === 'COURSE_LIST'),
    JSON.stringify(rec.cards.map((x) => x.type)))
  const body = await chat(token, '推荐课程前先看一下我的体测档案', sid)
  check('★ 其它工具（体测档案）不受影响',
    body.toolNames.includes('queryMyBodyProfile'), `tools=${body.toolNames}`)

  // ================================================================
  section('7. ★★ #15 卡片治理：同类型卡片合并/去重（ToolTrace 探针）')
  // ================================================================
  // ★★ 为什么这里用**探针**，而不是"发一轮对话数卡片"：
  //   Mock 通道下一轮**只会调一次 `searchCourses`**（`calledToolNames()` 按**工具名**去重，
  //   已经调过的工具不再调）→ "一轮里出现多张课程卡片"这个场景**根本触发不了**。
  //   那么"卡片 ≤ 1 张"这条断言会**因为只搜了一次而必然通过** —— 那是假绿
  //   （"期望为 1"并没有证明"前置动作真的能产生多张"）。
  //   而真实模型下这件事又是非确定的。→ 把这段**纯逻辑单独拉出来验证**。
  const probe = runToolTraceProbe()
  check('★★ 两次搜索 → **合并成一张**课程卡片（不再一张一张往上堆）',
    probe.CASE1_CARD_COUNT === '1', `卡片数=${probe.CASE1_CARD_COUNT}`)
  check('★★ 合并时按 courseId 去重，且保留"先出现的那一份"（顺序即相关性顺序）',
    probe.CASE1_COURSE_IDS === '[535, 536, 541]', `课程=${probe.CASE1_COURSE_IDS}`)
  check('★ 合并后的关键词是两次搜索的**并集**（说清这张卡是哪几次搜索的结果）',
    (probe.CASE1_KEYWORD || '').includes('+'), `keyword=${probe.CASE1_KEYWORD}`)
  check('★★ 两次下单 → **只保留最后一张**（前面那笔已经不该被点了）',
    probe.CASE2_CARD_COUNT === '1' && probe.CASE2_ORDER_ID === '1002',
    `卡片数=${probe.CASE2_CARD_COUNT} 订单号=${probe.CASE2_ORDER_ID}`)
  check('★ 其它类型卡片**不误伤**（只治该治的两种，别的照旧各自保留）',
    probe.CASE3_CARD_COUNT === '2', `卡片数=${probe.CASE3_CARD_COUNT}`)

  // ================================================================
  section('8. ★★ 会话与停止（#7 / #8 / #18）')
  // ================================================================
  // ★ 这一组的前提是一条**被改掉的前端行为**：从前端"等后端在 STOP 里回填 sessionId"
  //   改成"**首轮就自己生成**"。后端本来就支持这件事 ——
  //   `getOrCreate` 的注释写着"前端带一个库里没有的 sessionId 是正常情况…直接按它建库"，
  //   `chat_session.session_id` 的列注释也写着"会话id（**前端生成**或服务端回填）"。
  //   只是前端一直没这么做，于是**首轮没有 sessionId**；而 `/ais/chat/stop` 只认 sessionId
  //   （`ChatStopDTO` 刻意不收 requestId，避免"前端拿的是上一轮 id"的竞态）
  //   → **首轮点"停止"永远停不掉后端**，后端会把整段回答生成完（白烧 token）。

  const mySid = `accept-m68-manual-${Date.now()}`
  const firstTurn = await chatSse(token, '增肌期每天吃多少蛋白质', mySid)
  check('★ 前端自带的 sessionId 被**原样采用**（不再"回填一个后端生成的"）',
    firstTurn.stop?.sessionId === mySid, `传入=${mySid} 返回=${firstTurn.stop?.sessionId}`)
  const sessOwner = sql(`SELECT user_id FROM fm_aigc.chat_session WHERE session_id='${mySid}'`)
  check('★ 该会话确实**按这个 id 建好了**（所以首轮不必"先建会话、再发消息"两次往返）',
    sessOwner.trim() === String(STUDENT_USER_ID), `库里 user_id=${sessOwner.trim() || '(空)'}`)

  const stopOk = await call('POST', '/ais/chat/stop', { body: { sessionId: mySid }, token })
  check('★★ 带 sessionId 调 stop 是**通的**（HTTP 200）—— 这条路以前首轮根本走不到',
    stopOk.status === 200, `status=${stopOk.status}`)
  check('★ 没有在途生成时返回 null（"停一个已经结束的轮次"是正常情况，不是错误）',
    stopOk.json?.data == null, `data=${JSON.stringify(stopOk.json?.data)}`)

  const stopSomeone = await call('POST', '/ais/chat/stop', { body: { sessionId: 'accept-m68-not-mine' }, token })
  check('★★ 别人的 / 不存在的 sessionId 停不掉（归属校验没被这次改动放过）',
    stopSomeone.json?.code !== 200, `code=${stopSomeone.json?.code} msg=${stopSomeone.json?.msg}`)

  // ---- #18：限流那一轮，STOP 里要有 sessionId，文案要说清"不会保存" ----
  const adminToken = await loginAdmin()
  // ★ 两个会话 id **分开用**：第 1 轮（成功那轮）会真的建会话，
  //   第 2 轮（被限流那轮）才是不该落库的那个 —— 用同一个 id 的话，
  //   "会话数为 0"的断言会因为第 1 轮建过会话而必然失败（第一版就这么写错了）。
  const limWarmSid = `accept-m68-warm-${Date.now()}`
  const limSid = `accept-m68-limited-${Date.now()}`
  await call('POST', '/ais/admin/limit?dailyChats=1&dailyTokens=1000000&maxConcurrent=2', { token: adminToken })
  await call('DELETE', `/ais/admin/limit/counter?userId=${STUDENT_USER_ID}`, { token: adminToken })

  // 第 1 次：把唯一的一次额度用掉（限流是"先扣后用"，所以要真的先跑一轮）
  await chatSse(token, '先把今天唯一的一次额度用掉', limWarmSid)
  // 第 2 次：必被限流（换一个全新 id，它才是"不该被建"的那个）
  const limited = await chatSse(token, '这一轮应该被限流', limSid)

  check('★ 第 2 次真的被限流了（否则下面几条断言等于没测 —— 先证前置）',
    limited.stop?.limited === true, `stop=${JSON.stringify(limited.stop)?.slice(0, 130)}`)
  check('★★★ 限流的 STOP 载荷里**带上了 sessionId**（以前没有 → 前端不知道"这是哪一轮"，左侧列表也不出现）',
    limited.stop?.sessionId === limSid, `stop.sessionId=${limited.stop?.sessionId} 期望=${limSid}`)
  check('★★ 限流提示明确说明**不会被保存**（"界面上有、库里没有"这件事要讲清楚，不能靠用户自己发现）',
    /不会被保存/.test(limited.text), `文案=${limited.text.slice(0, 130)}`)
  const limSessCount = sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE session_id='${limSid}'`)
  const limMsgCount = sql(`SELECT COUNT(*) FROM fm_aigc.chat_message WHERE session_id='${limSid}'`)
  check('★ 被限流的那一轮**会话与消息都没落库**（"限流放在 prepare 之前"是既定设计，这里确认它没被改坏）',
    limSessCount === '0' && limMsgCount === '0', `会话=${limSessCount} 消息=${limMsgCount}`)
  check('★ 反证：第 1 轮（成功那次）**是**落库的 —— 说明上面的"0"是限流造成的，不是别的原因',
    sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE session_id='${limWarmSid}'`) === '1',
    `第 1 轮会话数=${sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE session_id='${limWarmSid}'`)}`)

  // ★ 必须恢复：限流用的配额是**临时配置**，留着会把后面的用例（以及别的脚本）全挡住
  await call('DELETE', '/ais/admin/limit', { token: adminToken })
  await call('DELETE', `/ais/admin/limit/counter?userId=${STUDENT_USER_ID}`, { token: adminToken })
  const quotaBack = await call('GET', '/ais/chat/quota', { token })
  check('★ 配额已恢复（否则会把后面的用例一起挡死 —— 这类"改环境"的用例必须自己收尾）',
    quotaBack.json?.data?.remainingChats > 0, `quota=${JSON.stringify(quotaBack.json?.data)?.slice(0, 110)}`)

  // ================================================================
  section('9. ★★ #9 工具超限被拦下时必须留痕（独立探针）')
  // ================================================================
  // ★★ 为什么只能用探针：真实一轮对话里 mock **只调 1~2 次工具**，而单轮上限
  //   远大于 2 → "被拦"这个分支**根本触发不了**。直接断言"面板上能看到超限拦截"
  //   会因为**从来没拦过**而必然通过（假绿）。所以把纯逻辑拉出来，用
  //   `new ToolTrace(1)` 可靠地造出"第 2 次被拦"。
  //   ★ 它比第 7 组的探针重：要 new 出真正的 `CourseTools`/`OrderTools`
  //     （类型签名带 Feign 接口、运行时要 slf4j）→ 得给**全量 classpath**（约 30KB）
  //     → 走 argfile 绕开 Windows 32767 命令行上限。
  const blockedProbe = runToolBlockedProbe()
  for (const line of blockedProbe.lines) {
    console.log(`  ${line.trim()}`)
  }
  check('★★ #9 探针全部通过（三个工具被拦时各留一条 blocked 记录 + 两条反证）',
    blockedProbe.failed === 0, `失败 ${blockedProbe.failed} 条`)

  // ================================================================
  section('10. 清理与自检')
  // ================================================================
  const sids = sql(`SELECT session_id FROM fm_aigc.chat_session WHERE session_id LIKE 'accept-m68-%'`)
    .split(/\r?\n/).map((x) => x.trim()).filter(Boolean)
  for (const s of sids) {
    sql(`DELETE FROM fm_aigc.chat_message WHERE session_id='${s}'`)
    sql(`DELETE FROM fm_aigc.chat_session WHERE session_id='${s}'`)
    redis('DEL', `aigc:mem:${s}`, `aigc:mem:summary:${s}`, `aigc:gen:${s}`)
  }
  redis('DEL', TOOLS_SNAPSHOT_KEY, PROMPT_SNAPSHOT_KEY)
  const leftSession = sql(`SELECT COUNT(*) FROM fm_aigc.chat_session WHERE session_id LIKE 'accept-m68-%'`)
  const leftMsg = sql(`SELECT COUNT(*) FROM fm_aigc.chat_message WHERE session_id LIKE 'accept-m68-%'`)
  check('★ 本次会话与消息无残留', leftSession === '0' && leftMsg === '0',
    `session=${leftSession} message=${leftMsg}`)
  check('★ 快照 key 已清（不把"上一轮的内容"留给下一次断言）',
    redis('EXISTS', TOOLS_SNAPSHOT_KEY, PROMPT_SNAPSHOT_KEY) === '0',
    `exists=${redis('EXISTS', TOOLS_SNAPSHOT_KEY, PROMPT_SNAPSHOT_KEY)}`)
  check('★ 用户数未被本脚本改变（只读对话，不写业务数据）',
    sql(`SELECT COUNT(*) FROM fm_user.user WHERE id=${STUDENT_USER_ID}`) === '1')

  console.log('\n============================================================')
  console.log(` 结果：${passed} 通过 / ${failed} 失败`)
  if (failures.length) {
    console.log(' 失败清单：')
    failures.forEach((f) => console.log(`   · ${f}`))
  }
  console.log('============================================================')
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('脚本异常：', e.message)
  process.exit(2)
})
