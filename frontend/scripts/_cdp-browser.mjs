/**
 * ============================================================
 * 前端验收脚本共用的「浏览器连接」模块
 * ============================================================
 *
 * ★★ 为什么要单独抽出来（而不是每个脚本各写一份 `launchBrowser`）：
 *
 *   本机上 `chromium.launch({ channel: 'msedge' })` **一定失败**：
 *   Playwright 与浏览器之间默认走 `--remote-debugging-pipe`
 *   （靠子进程的 stdin/stdout 通信），而这条路径在本机 Edge 131 上
 *   **进程起来就自己退出** —— `exitCode=0`、`signal=null`、没有 stderr，
 *   对外只表现为 `Target page, context or browser has been closed`。
 *   （`dangerouslyDisableSandbox` 无关：关掉沙箱一样失败，所以不是沙箱拦的。）
 *
 *   ★ 这与《本地环境排障手册》§3.40(b) 是**同一族**问题：那里是
 *   `spawnSync` 的 stdin 不能是 pipe，这里是 Edge 的调试通道也是 pipe。
 *   **本机的"管道"这条路是坏的**，能绕就绕。
 *
 * ★ 绕法 = 手动起 Edge（`--remote-debugging-port`）+ `chromium.connectOverCDP()`：
 *   同一个浏览器、同一套 Playwright API（goto / waitForSelector / click /
 *   截图全都能用），只是把**连接方式**换成实测可用的那一种。
 *   「改执行手段、不降判据」—— 与本项目一贯做法一致。
 *
 * 用法（与原来的写法**只差一行 import**）：
 *   import { launchBrowser } from './_cdp-browser.mjs'
 *   const browser = await launchBrowser()      // 返回 Browser，可直接 .newContext()
 *
 * ★ 进程退出时自动收掉"自己起的那个"浏览器；复用别人开着的则不动它。
 * ============================================================
 */
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { chromium } from 'playwright'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const CDP_PORT = 9222
const CDP_URL = `http://127.0.0.1:${CDP_PORT}`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** CDP 端口上有没有已经开着的浏览器？返回版本串或 false */
export async function cdpAlive() {
  try {
    const res = await fetch(`${CDP_URL}/json/version`, { signal: AbortSignal.timeout(2500) })
    const j = await res.json()
    return j?.Browser ? j.Browser : false
  } catch {
    return false
  }
}

/** 拿到 `{ browser, owned, pid }`：优先复用已开着的，否则自己起一个 */
export async function connectBrowser() {
  const alive = await cdpAlive()
  if (alive) {
    console.log(`  ℹ️ 复用已开着的浏览器（CDP ${CDP_PORT}）：${alive}`)
    return { browser: await chromium.connectOverCDP(CDP_URL), owned: false, pid: null }
  }

  const profile = join(tmpdir(), `fitmind-edge-cdp-${Date.now()}`)
  const child = spawn(
    EDGE,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-sandbox',
      '--no-first-run',
      '--disable-extensions',
      `--remote-debugging-port=${CDP_PORT}`,
      `--user-data-dir=${profile}`,
      'about:blank',
    ],
    { detached: true, stdio: 'ignore' },
  )
  child.unref()

  for (let i = 0; i < 30; i++) {
    await sleep(500)
    if (await cdpAlive()) {
      console.log(`  ℹ️ 已启动本机 Edge（CDP ${CDP_PORT}，pid=${child.pid}）`)
      return { browser: await chromium.connectOverCDP(CDP_URL), owned: true, pid: child.pid }
    }
  }
  throw new Error(
    `Edge 未能在 ${CDP_PORT} 上启动 CDP。可手动起一个再重跑：\n` +
      `  "${EDGE}" --headless=new --disable-gpu --no-sandbox --remote-debugging-port=${CDP_PORT} ` +
      `--user-data-dir=%TEMP%\\edge-cdp about:blank`,
  )
}

let conn = null

/**
 * ★ 与各脚本原有的 `launchBrowser()` **同名同签名** —— 所以调用点一行都不用改，
 *   只要把本地那份定义换成 `import` 即可。
 */
export async function launchBrowser() {
  if (!conn) conn = await connectBrowser()
  return conn.browser
}

/** 收尾：只关"自己起的那一个"（复用人家的就只断开，别把窗口杀掉） */
export async function closeBrowser() {
  if (conn?.owned && conn.pid) {
    try {
      process.kill(conn.pid)
    } catch {
      /* 已经退出了 */
    }
  }
  conn = null
}

// 脚本异常退出时也别把浏览器留着
process.on('exit', () => {
  if (conn?.owned && conn.pid) {
    try {
      process.kill(conn.pid)
    } catch {
      /* ignore */
    }
  }
})
