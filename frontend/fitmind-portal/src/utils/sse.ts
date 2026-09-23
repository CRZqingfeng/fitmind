/**
 * SSE（Server-Sent Events）解析与流式请求
 *
 * ============================================================
 * ★★ 为什么不用 EventSource
 * ============================================================
 * 浏览器的 `EventSource` 有两个硬限制，正好都撞在我们的接口上：
 *   ① **只支持 GET** —— 而我们的对话是 `POST /ais/chat/stream`（提问内容在 body 里）；
 *   ② **不能加自定义请求头** —— 而我们的鉴权是 `Authorization: <裸token>`。
 * 所以只能用 `fetch` + `ReadableStream` **手写解析**。
 *
 * ============================================================
 * ★★ 手写解析要处理的四件事（每一件都会真的出错）
 * ============================================================
 * ① **粘包 / 半包**：`reader.read()` 返回的 chunk 与"一个 SSE 帧"没有对齐关系 ——
 *    可能一次给你 3 整帧，也可能只给你半行。必须自己缓冲、按分隔符切。
 * ② **UTF-8 被切在 chunk 边界**：一个汉字 3 字节，很可能前 2 字节在这块、第 3 字节在下一块。
 *    `new TextDecoder().decode(chunk)` 会把它变成 `�`（不可逆）。
 *    → 必须 `decode(chunk, { stream: true })`，让解码器自己留着不完整的尾字节。
 * ③ **多行载荷**：SSE 规范里一个帧可以有**多条 `data:` 行**，它们用 `\n` 连接起来才是完整载荷。
 *    ⚠️ 这不是可选特性 —— 我们的回答里全是换行，后端就是靠这个机制把换行传过来的。
 *    实测原始帧（Mock 回答里的一个 4 字分片 "】\n[1"）：
 *      ```
 *      event:DATA
 *      data:】
 *      data:[1
 *      ```
 *    ★ 如果只取最后一条 `data:`（或者只取第一条），文本会被中间截断。
 * ④ ★★ **`data:` 冒号后的空格不能按规范剥掉**：
 *    SSE 规范说"值若以一个空格开头，去掉那一个空格"，多数解析器（包括浏览器的 EventSource、
 *    以及各种现成的 JS SSE 库）都照做。
 *    **但 Spring 的 SseEmitter 不会在冒号后补空格** —— 它写的是 `data:` + 载荷原文。
 *    于是载荷本身以空格开头时，线上就是 `data: 而`，规范解析器会把它变成 `and` 丢掉那个空格。
 *    实测证据（ENHANCE 策略的一次真实回答，全文 727 字）：
 *      · 不剥空格 → 拼接长度 727，与 `POST /ais/chat` 的全文**完全一致**
 *      · 按规范剥 → 拼接长度 720，**丢了 7 个字符**，且不报任何错
 *    → 这里**刻意不剥**。这也是"必须自己写解析器、不能用现成库"的最实际的理由。
 *
 * 后端帧格式（实测）：
 * ```
 * event:DATA\n
 * data:分片\n
 * \n
 * ```
 */

import { getToken } from '@/utils/token'
import { forceLogin, refreshTokenOnce } from '@/utils/request'
import type { ChatParamPayload, SseFrame, StopPayload } from '@/types/ai'
import { SSE_EVENT } from '@/types/ai'

// ============================================================
// 纯解析部分（不依赖网络，可单独验证）
// ============================================================

export interface SseParser {
  /** 喂入一段文本，返回这次能凑出的完整帧 */
  push(chunk: string): SseFrame[]
  /** 流结束时调用：把缓冲区里可能残留的最后一帧也吐出来 */
  flush(): SseFrame[]
}

/**
 * 解析一个帧（不含帧尾空行）。
 *
 * SSE 规范里除了 `event` / `data`，还有 `id` / `retry` 与注释行（以 `:` 开头）；
 * 我们不使用它们，但**也不能把它们当数据** —— 所以显式忽略未识别的字段。
 */
function parseFrame(rawFrame: string): SseFrame | null {
  let event = 'message'
  const dataLines: string[] = []

  for (const rawLine of rawFrame.split('\n')) {
    // 容忍 CRLF（本后端只发 \n，但代理/反代有可能改写）
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (!line) continue
    if (line.startsWith(':')) continue // 注释行（常被用作心跳）

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    const value = colon === -1 ? '' : line.slice(colon + 1)

    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      // ★★ 见文件头 ④：**不要**去掉前导空格
      dataLines.push(value)
    }
  }

  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

export function createSseParser(): SseParser {
  let buf = ''

  const drain = (): SseFrame[] => {
    const frames: SseFrame[] = []
    // 帧分隔符 = 一个空行（这里按 \n\n 找；\r 已在 parseFrame 里逐行剥掉）
    let sep = buf.indexOf('\n\n')
    while (sep !== -1) {
      const rawFrame = buf.slice(0, sep)
      buf = buf.slice(sep + 2)
      const frame = parseFrame(rawFrame)
      if (frame) frames.push(frame)
      sep = buf.indexOf('\n\n')
    }
    return frames
  }

  return {
    push(chunk: string) {
      buf += chunk
      return drain()
    },
    flush() {
      // 流已正常结束，缓冲区里剩下的内容（没有以空行收尾）也算一帧
      const rest = buf
      buf = ''
      const frames = drain()
      const last = parseFrame(rest)
      if (last) frames.push(last)
      return frames
    },
  }
}

// ============================================================
// 流式请求
// ============================================================

export interface StreamChatOptions {
  /** 请求体（`ChatRequestDTO`） */
  body: Record<string, unknown>
  /** 收到一个文本分片（★ 增量，不是全文） */
  onData?: (text: string) => void
  /** 收到结构化载荷（引用 / 卡片） */
  onParam?: (payload: ChatParamPayload) => void
  /** 收到收尾事件（含本轮全部指标） */
  onStop?: (payload: StopPayload) => void
  /** 出错（网络 / 业务码 / 解析） */
  onError?: (error: Error) => void
}

export interface StreamHandle {
  /** 中断前端这一侧的读取（用于"停止生成"按钮；★ 还要另外调后端 /chat/stop） */
  abort: () => void
  /** 流结束（resolve 出 STOP 载荷；被中断或失败时可能是 null） */
  done: Promise<StopPayload | null>
}

const baseURL = (import.meta.env.VITE_API_BASE as string | undefined) ?? ''

/**
 * 发起一次流式对话。
 *
 * ★ 同步返回句柄：因为"停止"按钮必须在**流还没开始时**就能点上
 *   （用户看到"正在思考"就会想停），所以不能在 await fetch 之后才把 abort 交出去。
 */
export function streamChat(options: StreamChatOptions): StreamHandle {
  const controller = new AbortController()
  let stopPayload: StopPayload | null = null
  let aborted = false

  const done = run(controller, options, {
    isAborted: () => aborted,
    setStop: (p) => {
      stopPayload = p
    },
  }).catch((e: unknown) => {
    // 主动中断不是错误（用户点停止是正常操作），不要弹红条
    const err = e instanceof Error ? e : new Error(String(e))
    if (err.name === 'AbortError' || aborted) return
    options.onError?.(err)
  })

  return {
    abort: () => {
      aborted = true
      controller.abort()
    },
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    done: done.then(() => stopPayload),
  }
}

async function run(
  controller: AbortController,
  options: StreamChatOptions,
  state: { isAborted: () => boolean; setStop: (p: StopPayload) => void },
): Promise<void> {
  const url = `${baseURL}/ais/chat/stream`

  let response = await doFetch(url, options.body, controller.signal)

  // ---- 401：和 Axios 拦截器共用同一套续期（不再写第二份）----
  if (response.status === 401) {
    const token = await refreshTokenOnce()
    if (!token) {
      forceLogin()
      throw new Error('登录已过期，请重新登录')
    }
    response = await doFetch(url, options.body, controller.signal)
  }

  if (!response.ok || !response.body) {
    // 非 2xx：后端在这个阶段返回的还是普通 JSON（业务码 + 人话 msg），取出来给人看
    const text = await response.text().catch(() => '')
    let msg = `请求失败（HTTP ${response.status}）`
    try {
      const body = JSON.parse(text) as { msg?: string }
      if (body?.msg) msg = body.msg
    } catch {
      /* 不是 JSON 就用兜底文案 */
    }
    throw new Error(msg)
  }

  const reader = response.body.getReader()
  // ★ 见文件头 ②：{ stream: true } 才能正确跨 chunk 拼接多字节字符
  const decoder = new TextDecoder('utf-8')
  const parser = createSseParser()

  const handleFrames = (frames: SseFrame[]) => {
    for (const frame of frames) {
      if (frame.event === SSE_EVENT.DATA) {
        // ★ 空字符串也可能是有意义的（后端说"分片为空"时会发 data:），
        //   但追加上去等于没变，直接跳过更省一次渲染
        if (frame.data !== '') options.onData?.(frame.data)
      } else if (frame.event === SSE_EVENT.PARAM) {
        try {
          const payload = JSON.parse(frame.data) as ChatParamPayload
          options.onParam?.(payload)
        } catch {
          // 载荷坏掉不该让整轮对话失败 —— 卡片/引用是"附加值"，少一条还能看回答
          console.warn('[sse] PARAM 载荷解析失败：', frame.data.slice(0, 200))
        }
      } else if (frame.event === SSE_EVENT.STOP) {
        try {
          const payload = JSON.parse(frame.data) as StopPayload
          state.setStop(payload)
          options.onStop?.(payload)
        } catch {
          console.warn('[sse] STOP 载荷解析失败：', frame.data.slice(0, 200))
        }
      }
    }
  }

  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    handleFrames(parser.push(decoder.decode(value, { stream: true })))
  }
  // 冲掉解码器里可能残留的半截字符，以及缓冲区里最后一帧
  handleFrames(parser.push(decoder.decode()))
  handleFrames(parser.flush())
}

async function doFetch(
  url: string,
  body: Record<string, unknown>,
  signal: AbortSignal,
): Promise<Response> {
  const token = getToken()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  // ★ 裸 token（与后端 AuthUtil.parseToken 的期望一致，不加 Bearer 前缀）
  if (token) headers.Authorization = token
  return fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal,
  })
}
