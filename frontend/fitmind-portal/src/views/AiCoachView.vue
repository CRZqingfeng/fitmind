<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

import { MagicStick, Plus, Promotion, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import {
  chatStream,
  getMemory,
  getProvider,
  getQuota,
  listMessages,
  listSessions,
  stopGeneration,
} from '@/api/ai'
import ChatCard from '@/components/chat/ChatCard.vue'
import CoachPanel from '@/components/chat/CoachPanel.vue'
import RagSources from '@/components/chat/RagSources.vue'
import { useAuthStore } from '@/stores/auth'
import type {
  ChatCardData,
  ChatMessage,
  ChatSession,
  ChatTypeValue,
  MemoryView,
  QuotaView,
  RagInfo,
  StopPayload,
  ToolTrace,
} from '@/types/ai'
import { CHAT_TYPES, chatTypeLabel } from '@/types/ai'
import { formatDateTime, pageTotal } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'
import type { StreamHandle } from '@/utils/sse'

/**
 * AI 教练
 *
 * ============================================================
 * 页面结构
 * ============================================================
 *   左：会话列表（分页）· 中：对话主区 · 右：调试面板（可收起）
 *
 * ============================================================
 * ★★ 三个必须讲清楚的设计点
 * ============================================================
 * ① **流式渲染要节流**：模型分片是 4 个字一块，一段 700 字的回答 ≈ 180 次到达。
 *    如果每个分片都改一次响应式数据，会触发 180 次组件更新 + 180 次 Markdown 重渲染 ——
 *    会明显掉帧。所以这里把分片先攒进缓冲，**按固定间隔提交**（`FLUSH_INTERVAL_MS`）。
 *    ⚠️ 节流用的是 `setTimeout` 而不是 `requestAnimationFrame`，理由见 `scheduleFlush` 的注释
 *    （rAF 在后台标签页会暂停、在无头环境可能被饿死，实测会让"流式"退化成"最后一次性出现"）。
 *
 * ② **"停止"要停两处**：前端 `abort()` 只是让**本浏览器**不再读了，
 *    服务端那一轮可能还在生成、还在烧 token。
 *    所以必须同时调 `POST /ais/chat/stop`（它是"分布式打断"，靠共享标记通知
 *    真正在生成的那个实例）。只做一件都不对：
 *      · 只 abort 前端 → 后端继续生成到自然结束，白烧 token
 *      · 只调 stop → 前端这条流不会自己断，还会继续收到分片
 *
 * ③ **消息里要能重渲染卡片**：历史消息接口的 `params` 字段带着卡片 JSON，
 *    所以"切回一个旧会话"时卡片还在。否则回看历史就只剩光秃秃的文字，
 *    而用户记住的恰恰是"刚才那张课程卡片"。
 */

interface LocalMessage {
  key: string
  role: 'user' | 'assistant'
  content: string
  cards: ChatCardData[]
  rag: RagInfo | null
  toolCalls: ToolTrace | null
  metrics: StopPayload | null
  /** 正在流式输出（决定要不要显示光标、按钮要不要变成"停止"） */
  streaming: boolean
  /** 本轮被停止 */
  stopped: boolean
  /** 出错提示（错误也留在对话里，而不是只弹一个转瞬即逝的 toast） */
  error: string | null
}

const auth = useAuthStore()

/**
 * 流式渲染的提交间隔（毫秒）。
 * 60ms ≈ 16 次/秒：视觉上是连续的打字机，又比"每个分片提交一次"省约一个数量级。
 */
const FLUSH_INTERVAL_MS = 60

// ---------- 会话 ----------
const sessions = ref<ChatSession[]>([])
const sessionTotal = ref(0)
const sessionPage = ref(1)
const sessionLoading = ref(false)
const sessionId = ref<string | null>(null)

/**
 * 生成一个会话 id（**首轮就带上**，理由见 `send()` 里那段说明）。
 *
 * ★ 格式没有约束：`chat_session.session_id` 是 `varchar(64)`，后端**不做数字解析**
 *   （列注释写的就是"会话id（前端生成或服务端回填）"）。
 *   这里用 32 位十六进制而不是仿雪花 id —— 与后端生成的 id **一眼能区分开**，
 *   排查"这条会话是谁建的"时反而方便（验收脚本用的是 `accept-m66-stat-…`，同理）。
 */
function newSessionId(): string {
  // crypto.randomUUID 只在安全上下文（https / localhost）可用，留一个降级分支
  const uuid = globalThis.crypto?.randomUUID?.()
  return uuid ? uuid.replaceAll('-', '') : `f${Date.now().toString(16)}${Math.random().toString(16).slice(2, 10)}`
}

// ---------- 对话 ----------
const messages = ref<LocalMessage[]>([])
const draft = ref('')
const chatType = ref<ChatTypeValue>('ROUTE')
const goal = ref('')
const streaming = ref(false)
let handle: StreamHandle | null = null

// ---------- 面板数据 ----------
const panelOpen = ref(false)
const provider = ref('')
const quota = ref<QuotaView | null>(null)
const memory = ref<MemoryView | null>(null)
const lastMetrics = ref<StopPayload | null>(null)

// ---------- DOM ----------
const scrollEl = ref<HTMLElement | null>(null)

const goalOptions = [
  { value: '', label: '不限目标' },
  { value: 'muscle_gain', label: '增肌' },
  { value: 'fat_loss', label: '减脂' },
]

/** 快速提问：四条各展示一种能力（工具卡片 / 下单卡片 / 知识依据 / 直答） */
/**
 * ★ 第 2 条刻意用「541 · 哑铃手臂线条塑造」——**演示账号当前未购买**的付费课
 *   （已购的是 535 / 539 / 542，见 M6-8 #3）。
 *
 *   为什么要专门挑一门没买过的：这条快捷项演示的是"下单成功"，
 *   而用一门已购课程点下去，用户看到的是"下单失败：你已经买过了" ——
 *   同一个按钮，一次演示"功能正常"、一次演示"功能坏了"。
 *
 *   ★ 文案里带上**课程名**而不只是 id：buy 提示词要求模型"先用 searchCourses
 *   确认课程真实存在"，只给一个数字（"541"）在关键词检索里搜不出东西，
 *   模型的正确路径会被自己的约束挡住。
 */
const quickPrompts = [
  { text: '我想增肌，有什么哑铃课程推荐？', tip: '触发工具 → 课程卡片' },
  { text: '帮我买课程 541（哑铃手臂线条塑造）', tip: '触发工具 → 生成可支付的订单' },
  { text: '硬拉的时候腰疼是什么原因？', tip: '触发知识库 → 引用来源' },
  // ★ #11：这条 tip 原来写的是"不需要知识库，直答" —— 而**默认策略是智能路由**，
  //   智能路由下这个问题会先花一次调用判路由，命中的是「推荐」Agent，**不会走直答**。
  //   tip 是给用户看"点下去会发生什么"的，写错了比不写更误导。
  { text: '今天练什么好？', tip: '智能路由 → 推荐课程' },
]

const currentTypeDesc = computed(
  () => CHAT_TYPES.find((t) => t.value === chatType.value)?.desc ?? '',
)

/** 当前策略的中文标签（左侧恒显的那一段） */
const chatTypeText = computed(() => chatTypeLabel(chatType.value))

/**
 * 当前选中的训练目标**中文**（#6）。
 *
 * ★ 以前这里直接渲染 `goal`（`muscle_gain` / `fat_loss` 这种枚举值），
 * 用户看到的是"知识库按「muscle_gain」过滤" —— 一句半英半中的话。
 * 同一个 `goalOptions` 里明明有 `label`，顺手映射一下就好。
 */
const goalText = computed(() => goalOptions.find((g) => g.value === goal.value)?.label ?? '')

/**
 * ★★ 本轮**是否确实会走知识库检索**（#6 / #16）—— 决定右半段提示显不显示。
 *
 * <p>以前这里是一个**三选一**（`v-if="streaming"` / `v-else-if="goal"` / `v-else`），
 * 所以"当前策略"和目标提示**互相排斥**：只要选了目标，就再也看不到自己现在是什么策略。
 * 更糟的是 —— **只要选了目标就显示"知识库按…过滤"**，
 * 哪怕策略是「直答」（一次检索都不会发）。**这已经不是文案问题，是在说假话。**</p>
 *
 * <p>判据按策略分三种：</p>
 * <ul>
 *   <li><b>增强检索（ENHANCE）</b>：一定先检索 → <b>显示</b>；</li>
 *   <li><b>直答（SIMPLE）</b>：一定不检索 → <b>不显示</b>；</li>
 *   <li><b>智能路由（ROUTE）</b>：发送前**无法预知**（要等路由结果）→
 *       退而用**上一轮的实际结果**当提示；没有上一轮就<b>不显示</b>
 *       —— <b>宁可少说一句，也不要瞎说一句</b>。</li>
 * </ul>
 */
const willRetrieve = computed(() => {
  if (chatType.value === 'ENHANCE') {
    return true
  }
  if (chatType.value === 'SIMPLE') {
    return false
  }
  // 智能路由：只有"上一轮真的检索到了东西"才敢说这一轮也会检索
  return Number(lastMetrics.value?.ragHits ?? 0) > 0
})

/**
 * 最近一轮的引用明细 —— 给调试面板用。
 *
 * ★ 必须从**消息**上取，而不是从 `lastMetrics` 取：
 *   引用与卡片走的是 SSE 的 PARAM 事件，`StopPayload` 里根本没有它们。
 *   （踩过一次：面板以为指标里有，结果引用区永远是空的。）
 */
const lastRag = computed(() => {
  const last = [...messages.value].reverse().find((m) => m.role === 'assistant')
  return last?.rag ?? null
})

/** 是否贴底（决定要不要自动滚动）—— 见 scrollToBottom 的说明 */
let stickToBottom = true

/**
 * 首屏加载（只会真正跑一次）。
 *
 * ★★ M6-8 #10：**未登录时不发鉴权请求**。
 *   `sessions` / `provider` / `quota` 都是需要登录的接口（`/ais/chat/**` 登录即可），
 *   而冷启动直接进 `/ai` 时登录态可能还没就绪 → 这一批请求会**必然 401**，
 *   控制台一下子一串红，顶栏还会闪一下"未登录"。
 *
 * ★ 先排除一个容易走偏的猜测：**不是"拦截器读不到 token"**。
 *   token 是**同步**从 localStorage 读的（`utils/token.ts` 的模块级 `readFromStorage()`），
 *   拦截器每次都拿得到。真正的意思是"这次会话本来就没有登录态"——
 *   那就更不该发这三个请求。
 */
let bootstrapped = false
async function bootstrap() {
  if (bootstrapped || !auth.isLogin) return
  bootstrapped = true
  await Promise.all([loadSessions(), loadProvider(), loadQuota()])
}

onMounted(bootstrap)

// ★ 登录态**晚于**首屏就绪时补一次（冷启动 token 恢复、或刚登录完跳过来）。
//   与上面的 `bootstrapped` 配合，保证"该加载的一定会加载、且只加载一次"。
watch(
  () => auth.isLogin,
  (v) => {
    if (v) void bootstrap()
  },
)

onBeforeUnmount(() => {
  // 离开页面要把在途的流断掉：否则它会继续跑（并在后台改一份已经没人看的组件状态）
  handle?.abort()
})

// ============================================================
// 数据加载
// ============================================================

async function loadSessions() {
  sessionLoading.value = true
  try {
    const page = await listSessions(sessionPage.value, 20)
    sessions.value = page?.list ?? []
    sessionTotal.value = pageTotal(page)
  } catch {
    /* request.ts 已提示 */
  } finally {
    sessionLoading.value = false
  }
}

async function loadProvider() {
  try {
    provider.value = (await getProvider()) ?? ''
  } catch {
    provider.value = ''
  }
}

async function loadQuota() {
  try {
    quota.value = await getQuota()
  } catch {
    /* 配额拿不到不影响对话，静默 */
  }
}

async function loadMemory() {
  if (!sessionId.value) {
    memory.value = null
    return
  }
  try {
    memory.value = await getMemory(sessionId.value)
  } catch {
    memory.value = null
  }
}

// ============================================================
// 会话切换
// ============================================================

async function startNewSession() {
  // ★★ 正在生成时，必须**先通知后端停止**，再清空界面（M6-8 #7）。
  //   只 `abort()` 本地流的话：后端那一轮的生成标记还在，它会把整段回答
  //   继续生成完才收尾（白烧模型 token、工具可能继续执行）——
  //   而前端已经把消息清空了，用户**完全看不到这段消耗**。
  //   ★ 与 `openSession` 的处理是一对：那边"拦下来不让切"（提示先停止），
  //     这边"先停再清"—— 因为"新对话"的意图本来就是"我想重新开始"，
  //     先停比拦住更顺，且不会让用户多一步操作。
  if (streaming.value) {
    await stop()
  }
  handle?.abort()
  sessionId.value = null
  messages.value = []
  lastMetrics.value = null
  memory.value = null
  draft.value = ''
  stickToBottom = true
}

async function openSession(s: ChatSession) {
  if (streaming.value) {
    ElMessage.warning('正在生成回答，先停止再切换会话')
    return
  }
  if (s.sessionId === sessionId.value) return

  sessionId.value = s.sessionId
  messages.value = []
  lastMetrics.value = null
  stickToBottom = true

  try {
    const list = await listMessages(s.sessionId)
    messages.value = list.map(toLocalMessage)
    await scrollToBottom(true)
  } catch {
    /* 已提示 */
  }
  await loadMemory()
}

/**
 * 历史消息 → 本地消息
 *
 * ★ 历史消息的卡片藏在 `params` 里（后端把 PARAM 载荷原样存了一份），
 *   所以这里能把它还原出来 —— 否则切回旧会话时卡片全没了。
 */
function toLocalMessage(m: ChatMessage): LocalMessage {
  const params = m.params
  return {
    key: `h-${m.id ?? Math.random().toString(36).slice(2)}`,
    role: m.role === 'user' ? 'user' : 'assistant',
    content: m.content ?? '',
    cards: params?.cards ?? [],
    rag: params?.rag ?? null,
    toolCalls: m.toolCalls ?? null,
    // ★★ #17：历史消息也带上指标 —— 以前这里恒为 `null`，
    //   于是"切回旧会话，助手回答下面那条指标行整行消失"，
    //   看起来像"历史消息是另一种消息"。
    //
    //   ⚠️ **能补的只有 3 项**（后端 `ChatMessageVO` 只存了这些）：Agent / 首字延迟 / 总耗时。
    //   ★ **"策略"补不了** —— `chat_message` 表里根本没存 `chatType`。
    //     所以历史行的指标**天然比实时少一项**，这是**如实呈现**，不是"做得像一样"。
    //     （要补全就得给消息表加字段，那是另一件事。）
    metrics:
      m.role === 'assistant' && (m.agentType || m.firstTokenMs != null || m.totalMs != null)
        ? { agentType: m.agentType, firstTokenMs: m.firstTokenMs, totalMs: m.totalMs }
        : null,
    streaming: false,
    stopped: false,
    error: null,
  }
}

// ============================================================
// 发送 / 停止
// ============================================================

async function send(text?: string) {
  const content = (text ?? draft.value).trim()
  if (!content) return
  if (streaming.value) return
  if (!auth.isLogin) {
    ElMessage.warning('请先登录')
    return
  }

  draft.value = ''
  stickToBottom = true

  messages.value.push(
    reactive<LocalMessage>({
      key: `u-${Date.now()}`,
      role: 'user',
      content,
      cards: [],
      rag: null,
      toolCalls: null,
      metrics: null,
      streaming: false,
      stopped: false,
      error: null,
    }),
  )

  /**
   * ★★ 这里必须用 `reactive()` 包一层，不能是普通对象 —— 这条踩过坑。
   *
   * `messages` 是 `ref<LocalMessage[]>([])`，它内部是响应式代理，
   * 但**push 一个普通对象进去、再直接改那个对象的属性，是不会触发渲染的**：
   * 代理只有在"通过代理读写"时才收集/触发依赖，而我们手上的 `answer` 是原始对象。
   *
   * 症状非常有迷惑性：文本**看着会更新**，但只在**别的响应式状态变化时**才更新
   * （比如 `streaming.value = false` 触发一次整组件重渲染，于是已经攒好的全文一次性出现）。
   * 实测就是"采样 54 次、只有 2 个不同长度：7 → 680" ——
   * **打字机效果其实是坏的，只是结束时会"啪"地出现全文，看起来像正常。**
   */
  const answer = reactive<LocalMessage>({
    key: `a-${Date.now()}`,
    role: 'assistant',
    content: '',
    cards: [],
    rag: null,
    toolCalls: null,
    metrics: null,
    streaming: true,
    stopped: false,
    error: null,
  })
  messages.value.push(answer)
  streaming.value = true
  await scrollToBottom(true)

  // ---- ★ 分片缓冲 + 定时节流（见文件头 ①） ----
  let pending = ''
  let timer: ReturnType<typeof setTimeout> | null = null
  const flush = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (!pending) return
    answer.content += pending
    pending = ''
    void scrollToBottom()
  }
  /**
   * ★★ 为什么用 `setTimeout` 而不是 `requestAnimationFrame`（本项目的实测结论）：
   *
   * rAF 是"跟着渲染帧走"的，因此有两个坑：
   *   ① **后台标签页会暂停 rAF** —— 用户切走再切回来，会看到回答"一次性出现"
   *      （这段时间分片一直在到，只是没有任何一帧被提交）；
   *   ② **无头/受限环境下 rAF 可能被饿死** —— 实测在验收脚本里跑了 6.5 秒、
   *      采样 54 次只测到 2 个不同长度，**流式效果等于不存在**。
   *
   * 定时器节流没有这两个问题，而且照样能压住更新频率（60ms ≈ 16 次/秒，
   * 视觉上足够顺滑，比"每个分片一次"省一个数量级）。
   * ★ 代价：它不同步于屏幕刷新，理论上可能有轻微撕裂 —— 对文字逐字出现无影响。
   */
  const scheduleFlush = () => {
    if (timer) return
    timer = setTimeout(flush, FLUSH_INTERVAL_MS)
  }
  const flushNow = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (pending) {
      answer.content += pending
      pending = ''
    }
  }

  // ★★ 首轮就自带 sessionId（M6-8 #8）—— 这不是"前端乱造 id"，而是本项目的**既定设计**：
  //   `chat_session.session_id` 的列注释写着"会话id（**前端生成**或服务端回填）"，
  //   后端 `getOrCreate` 也专门为此写了注释（"前端带一个库里没有的 sessionId 是正常情况…直接按它建库"），
  //   只是前端一直没这么做（等后端在 STOP 里回填）。
  //
  // ★ **为什么必须这样：不这样的话，首轮就停不掉。**
  //   `POST /ais/chat/stop` 只认 sessionId（`ChatStopDTO` **刻意**不收 requestId，理由是
  //   "前端手上的可能是上一轮的 id，于是点了停止却停不掉当前这轮"）。
  //   而首轮 sessionId 为 null → 点"停止"只能 abort 本地的流，
  //   后端那一轮**照样把整段回答生成完**（白烧模型 token、工具可能继续执行）。
  const isNewSession = !sessionId.value
  if (isNewSession) sessionId.value = newSessionId()

  handle = chatStream(
    {
      content,
      chatType: chatType.value,
      // ★ 这里一定有值（上一行刚保证），但 TS 推断不出来，用 ?? undefined 收窄
      sessionId: sessionId.value ?? undefined,
      ...(goal.value ? { goal: goal.value } : {}),
    },
    {
      onData: (chunk) => {
        pending += chunk
        scheduleFlush()
      },
      onParam: (payload) => {
        // 卡片与引用先落住（它们不参与打字机，一次性渲染即可）
        if (payload.cards?.length) answer.cards = payload.cards
        if (payload.rag) answer.rag = payload.rag
      },
      onStop: (payload) => {
        // ★ 必须先把缓冲刷完再标记结束：否则最后几个分片会"丢"
        //   （它们还在 pending 里，而 streaming 已变 false，界面看着像被截断）
        flushNow()
        answer.metrics = payload
        answer.toolCalls = payload.toolCalls ?? null
        answer.stopped = !!payload.stopped
        answer.streaming = false
        lastMetrics.value = payload
        // ★ 首轮：会话是服务端**刚建好**的（标题也是刚生成的），要刷新左侧列表让它出现。
        //   ⚠️ 不能再用"payload.sessionId !== sessionId.value"来判断"是不是首轮"了 ——
        //   现在首轮前端就把 id 带过去了，后端回的是**同一个值**，那个条件永远不成立
        //   （这是"改成首轮自带 id"之后必须跟着改的一处，否则新会话不会出现在列表里）。
        if (payload.sessionId) sessionId.value = payload.sessionId
        if (isNewSession) void loadSessions()
        void loadMemory()
        void loadQuota()
      },
      onError: (e) => {
        flushNow()
        answer.streaming = false
        answer.error = e.message || '生成失败'
      },
    },
  )

  try {
    await handle.done
  } finally {
    flushNow()
    answer.streaming = false
    streaming.value = false
    handle = null
    await scrollToBottom()
  }
}

/**
 * 停止生成 —— ★ 前端 abort + 后端 /chat/stop，两件都要做（见文件头 ②）
 */
async function stop() {
  if (!streaming.value) return

  // 先通知后端（让真正在生成的实例尽快停下），再断前端这条流
  const backendStop = sessionId.value
    ? stopGeneration(sessionId.value).catch(() => null)
    : Promise.resolve(null)

  handle?.abort()

  const stoppedRequestId = await backendStop
  const last = [...messages.value].reverse().find((m) => m.role === 'assistant')
  if (last) {
    last.stopped = true
    last.streaming = false
  }
  streaming.value = false
  // ★ 文案按"用户确实中断了一条在途生成"来定（M6-8 #8）。
  //   原来写的是 `stoppedRequestId ? '已停止生成' : '当前没有正在生成的回答'`，
  //   但**后面那句话永远不该出现在这个函数里** —— 函数开头的
  //   `if (!streaming.value) return` 已经保证了"确实有一条在途的流"。
  //   首轮（还没有 sessionId）时后端必然返回 null，于是弹出"当前没有正在生成的回答"，
  //   与"前端刚 abort 了一条流、回答也确实停住了"自相矛盾。
  ElMessage.info('已停止生成')
  // 后端说"该会话当前没有在途生成"时不吞掉这个信息，但也不让用户困惑（只记 console）
  if (!stoppedRequestId) console.debug('[AiCoach] stop: 后端该会话当前无在途生成，仅中断了本地流')
}

// ============================================================
// 滚动
// ============================================================

/**
 * 滚到底。
 *
 * ★ 只在"用户本来就贴着底"时自动滚 —— 否则用户往上翻看历史时，
 *   每一帧都被拽回底部，等于不让人读。
 */
async function scrollToBottom(force = false) {
  const el = scrollEl.value
  if (!el) return
  if (!force && !stickToBottom) return
  await nextTick()
  el.scrollTop = el.scrollHeight
}

function onScroll() {
  const el = scrollEl.value
  if (!el) return
  // 距底 < 40px 视为"贴底"（留余量，避免像素级误差把自动滚动关掉）
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

// ============================================================
// 展示辅助
// ============================================================

function renderMd(text: string) {
  return renderMarkdown(text)
}

function shortTime(v: string | null | undefined) {
  const t = formatDateTime(v)
  return t === '—' ? '' : t.slice(5)
}

/** 消息的旁注（Agent / 策略 / 耗时）—— 让人一眼看出"这东西是透明的" */
function metricLine(m: LocalMessage): string {
  const parts: string[] = []
  if (m.metrics?.agentType) parts.push(`Agent：${m.metrics.agentType}`)
  if (m.metrics?.chatType) parts.push(`策略：${chatTypeLabel(m.metrics.chatType)}`)
  if (m.metrics?.firstTokenMs != null) parts.push(`首字 ${m.metrics.firstTokenMs}ms`)
  if (m.metrics?.totalMs != null) parts.push(`共 ${m.metrics.totalMs}ms`)
  return parts.join(' · ')
}
</script>

<template>
  <div class="ai-page">
    <!-- ==================== 左：会话列表 ==================== -->
    <aside class="session-pane">
      <div class="pane-head">
        <el-button
          type="primary"
          :icon="Plus"
          class="new-btn"
          data-testid="ai-new-session"
          @click="startNewSession"
        >
          新对话
        </el-button>
      </div>

      <div v-loading="sessionLoading" class="session-list">
        <div v-if="!sessions.length" class="session-empty">还没有对话记录</div>
        <div
          v-for="s in sessions"
          :key="s.sessionId"
          class="session-item"
          :class="{ active: s.sessionId === sessionId }"
          data-testid="ai-session-item"
          @click="openSession(s)"
        >
          <div class="s-title" data-testid="ai-session-title">{{ s.title || '（无标题）' }}</div>
          <div class="s-preview">{{ s.lastMessage || '—' }}</div>
          <div class="s-meta">
            <span>{{ s.msgCount }} 条</span>
            <span>{{ shortTime(s.lastActive) }}</span>
          </div>
        </div>
      </div>

      <el-pagination
        v-if="sessionTotal > 20"
        v-model:current-page="sessionPage"
        small
        layout="prev, pager, next"
        :page-size="20"
        :total="sessionTotal"
        @current-change="loadSessions"
      />
    </aside>

    <!-- ==================== 中：对话 ==================== -->
    <section class="chat-pane">
      <header class="chat-head">
        <el-icon class="head-icon"><MagicStick /></el-icon>
        <span class="head-title">AI 教练</span>

        <!-- ★ 模型通道必须显示：同一条回答来自真实模型还是确定性 Mock，可信度完全不同 -->
        <el-tag
          v-if="provider"
          size="small"
          :type="provider === 'mock' ? 'info' : 'success'"
          effect="dark"
          data-testid="ai-provider"
        >
          {{ provider === 'mock' ? 'Mock 通道（确定性）' : '真实模型' }}
        </el-tag>

        <div class="head-right">
          <el-select v-model="chatType" size="small" class="type-select" data-testid="ai-chattype">
            <el-option v-for="t in CHAT_TYPES" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
          <el-select
            v-model="goal"
            size="small"
            class="goal-select"
            placeholder="目标"
            data-testid="ai-goal"
          >
            <el-option v-for="g in goalOptions" :key="g.value" :value="g.value" :label="g.label" />
          </el-select>
          <el-button
            size="small"
            :type="panelOpen ? 'primary' : 'default'"
            :icon="Setting"
            data-testid="ai-panel-toggle"
            @click="panelOpen = !panelOpen"
          >
            调试面板
          </el-button>
        </div>
      </header>

      <div class="type-hint">{{ currentTypeDesc }}</div>

      <div ref="scrollEl" class="chat-scroll" @scroll.passive="onScroll">
        <!-- 空态：把"能做什么"说清楚，而不是空白 + 输入框 -->
        <div v-if="!messages.length" class="chat-empty">
          <el-icon class="empty-icon"><MagicStick /></el-icon>
          <h3>我是你的健身教练</h3>
          <p class="empty-sub">
            我能查你的体测档案、推荐课程、帮你把订单建到待支付；也能引用训练知识库回答训练与营养问题。
          </p>
          <div class="quick-grid">
            <div
              v-for="(q, i) in quickPrompts"
              :key="q.text"
              class="quick-item"
              :data-testid="`ai-quick-${i}`"
              @click="send(q.text)"
            >
              <div class="quick-text">{{ q.text }}</div>
              <div class="quick-tip">{{ q.tip }}</div>
            </div>
          </div>
        </div>

        <div
          v-for="m in messages"
          :key="m.key"
          class="msg-row"
          :class="m.role"
          data-testid="ai-message"
        >
          <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>

          <div class="msg-main">
            <!-- 用户消息原样展示（不渲染 Markdown：用户写什么就该看到什么） -->
            <div v-if="m.role === 'user'" class="bubble user-bubble">{{ m.content }}</div>

            <template v-else>
              <div
                class="bubble ai-bubble"
                data-testid="ai-answer"
                :data-streaming="m.streaming ? 'true' : 'false'"
              >
                <div v-if="!m.content && m.streaming" class="thinking" data-testid="ai-thinking">
                  正在思考…
                </div>
                <!-- ★ Markdown 渲染（html:false 防注入，见 utils/markdown.ts） -->
                <div v-else class="md-body" v-html="renderMd(m.content)" />

                <span v-if="m.streaming" class="caret" />
              </div>

              <div v-if="m.stopped" class="stopped-tip">
                <el-tag size="small" type="warning" effect="plain" data-testid="ai-stopped">
                  已停止生成
                </el-tag>
                <span>以上是停止前已生成的内容</span>
              </div>

              <div v-if="m.error" class="error-tip" data-testid="ai-error">{{ m.error }}</div>

              <!-- 结构化卡片（来自工具，不是模型文本） -->
              <ChatCard v-for="(c, ci) in m.cards" :key="ci" :card="c" />

              <!-- 引用来源 -->
              <RagSources v-if="m.rag" :rag="m.rag" mode="inline" />

              <div v-if="metricLine(m)" class="msg-metrics" data-testid="ai-metrics-line">
                {{ metricLine(m) }}
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- ==================== 输入区 ==================== -->
      <footer class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="问点什么…（Enter 发送，Shift + Enter 换行）"
          data-testid="ai-input"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="composer-actions">
          <!--
            ★★ #6 / #16：拆成**两段、不再互斥**。
              左段「当前策略」**恒显**（含生成中）—— 它回答的是"我这次点发送会怎么走"，
              是一句永远成立的话；右段是**条件**提示，只在"这一轮确实会检索"时才出现。
          -->
          <span class="composer-tip">
            <span data-testid="ai-tip-chattype">当前策略：{{ chatTypeText }}</span>
            <template v-if="streaming">
              <span class="tip-sep">·</span>
              <span>正在生成…</span>
            </template>
            <template v-else-if="willRetrieve && goalText">
              <span class="tip-sep">·</span>
              <span data-testid="ai-tip-goal">知识库按「{{ goalText }}」过滤</span>
            </template>
          </span>
          <el-button
            v-if="streaming"
            type="danger"
            plain
            :icon="Promotion"
            data-testid="ai-stop"
            @click="stop"
          >
            停止
          </el-button>
          <el-button
            v-else
            type="primary"
            :disabled="!draft.trim()"
            data-testid="ai-send"
            @click="send()"
          >
            发送
          </el-button>
        </div>
      </footer>
    </section>

    <!-- ==================== 右：调试面板 ==================== -->
    <CoachPanel
      v-if="panelOpen"
      :metrics="lastMetrics"
      :memory="memory"
      :quota="quota"
      :provider="provider"
      :rag="lastRag"
      @close="panelOpen = false"
    />
  </div>
</template>

<style scoped>
.ai-page {
  display: flex;
  gap: 16px;
  /* 撑满视口：减去顶部导航(60) 与 el-main 的上下内边距(20 + 40) */
  height: calc(100vh - 120px);
  min-height: 520px;
}

/* ---------- 左：会话列表 ---------- */
.session-pane {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--fm-radius);
  overflow: hidden;
}

.pane-head {
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.new-btn {
  width: 100%;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-empty {
  padding: 24px 8px;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.session-item {
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.session-item:hover {
  background: var(--el-fill-color-light);
}

.session-item.active {
  background: var(--el-color-primary-light-9);
}

.s-title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.s-preview {
  margin-top: 3px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.s-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

/* ---------- 中：对话 ---------- */
.chat-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--fm-radius);
  overflow: hidden;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.head-icon {
  color: var(--el-color-primary);
  font-size: 18px;
}

.head-title {
  font-size: 15px;
  font-weight: 600;
}

.head-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.type-select {
  width: 104px;
}

.goal-select {
  width: 104px;
}

.type-hint {
  padding: 6px 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: var(--el-fill-color-blank);
}

/* ---- 空态 ---- */
.chat-empty {
  max-width: 560px;
  margin: 32px auto 0;
  text-align: center;
}

.empty-icon {
  font-size: 40px;
  color: var(--el-color-primary);
}

.chat-empty h3 {
  margin: 12px 0 6px;
  font-size: 18px;
}

.empty-sub {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
  text-align: left;
}

.quick-item {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.quick-item:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.quick-text {
  font-size: 13px;
  line-height: 1.5;
}

.quick-tip {
  margin-top: 4px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

/* ---- 消息 ---- */
.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: var(--el-color-primary);
}

.msg-row.user .avatar {
  background: var(--el-color-info);
}

.msg-main {
  min-width: 0;
  max-width: min(760px, 82%);
}

.bubble {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.8;
}

.user-bubble {
  background: var(--el-color-primary-light-9);
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-bubble {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  word-break: break-word;
}

.thinking {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

/* 流式光标：让"还在写"这件事有可见的迹象 */
.caret {
  display: inline-block;
  width: 6px;
  height: 15px;
  margin-left: 3px;
  vertical-align: -2px;
  background: var(--el-color-primary);
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

.stopped-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.error-tip {
  margin-top: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 13px;
}

.msg-metrics {
  margin-top: 6px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

/* ---- Markdown 正文 ---- */
.md-body :deep(p) {
  margin: 0 0 8px;
}

.md-body :deep(p:last-child) {
  margin-bottom: 0;
}

.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) {
  margin: 14px 0 8px;
  font-size: 15px;
  line-height: 1.5;
}

.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 6px 0 8px;
  padding-left: 22px;
}

.md-body :deep(li) {
  margin-bottom: 4px;
}

.md-body :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 13px;
}

.md-body :deep(pre) {
  margin: 8px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-dark);
  overflow-x: auto;
}

.md-body :deep(pre code) {
  background: transparent;
  padding: 0;
}

.md-body :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
}

.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 4px 10px;
}

.md-body :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--el-color-primary-light-5);
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-regular);
}

/* ---------- 输入区 ---------- */
.composer {
  padding: 10px 14px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}

.composer-tip {
  margin-right: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 两段提示之间的分隔符（#6/#16 拆段后才需要） */
.tip-sep {
  margin: 0 6px;
  color: var(--el-text-color-placeholder);
}
</style>
