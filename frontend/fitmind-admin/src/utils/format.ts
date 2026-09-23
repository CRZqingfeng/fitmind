/**
 * 展示层格式化工具
 *
 * 集中在一处的原因：这些换算（分→元、秒→时长、日期格式化）如果散落在各组件里，
 * 迟早会出现"列表页显示 ¥199.00、详情页显示 ¥199"这种不一致。
 */

/**
 * 价格展示
 *
 * ⚠️ `free` 的形态在不同接口里**不一样**（这是后端既有的不一致）：
 *   - 搜索 `CourseSearchVO.free`  → `boolean`
 *   - 全文 `CourseFullDTO.free`   → `number`（1-免费 2-付费）
 * 所以这里的签名要同时接受两种，否则某个页面必然显示错。
 *
 * @param fen  价格，单位分（后端一律用分）
 */
export function formatPrice(
  fen: number | null | undefined,
  free?: boolean | number | null,
): string {
  if (free === true || free === 1) return '免费'
  if (fen === null || fen === undefined) return '—'
  if (fen === 0) return '免费'
  return `¥${(fen / 100).toFixed(2)}`
}

/** 秒 → "1 小时 12 分" / "12 分 30 秒" / "45 秒" */
export function formatDuration(seconds: number | null | undefined): string {
  if (!seconds || seconds <= 0) return '—'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  if (h > 0) return `${h} 小时 ${m} 分`
  if (m > 0) return s > 0 ? `${m} 分 ${s} 秒` : `${m} 分`
  return `${s} 秒`
}

/**
 * 分页的 `total` / `pages` → 数字。
 *
 * ★ 为什么需要一个专门的函数（而不能在页面里写 `Number(page.total)`）：
 *   后端把 `PageDTO.total` 序列化成**字符串**（全局 Long→String），
 *   而 `el-pagination` 的 `:total` 要求数字、`total + 1` 这类算术也要求数字。
 *   散落各处 `Number()` 的下场是"某几处漏了" —— 而漏掉的表现是
 *   **字符串拼接**（`"4" + 1 === "41"`），既不报错也不崩溃，只是数字莫名其妙。
 *   所以收敛成唯一入口：**凡是用到分页总数，都从这里过**。
 */
export function pageTotal(page: { total?: string | number | null } | null | undefined): number {
  const n = Number(page?.total ?? 0)
  return Number.isFinite(n) ? n : 0
}

/**
 * 后端 LocalDateTime → "2026-09-13 19:25"
 *
 * 后端返回的是不带时区的 ISO 字符串（如 `2026-09-13T19:25:59`），
 * ★ 不要 `new Date(str)` 再 toLocaleString —— 那会按浏览器时区再转一次，
 *   在本机（东八区）看着没问题，换台机器就差 8 小时。
 *   直接按字符串切，永远和数据库里存的一致。
 */
export function formatDateTime(value: string | number[] | null | undefined): string {
  if (!value) return '—'

  // ★★ 搜索接口返回的 publishTime 是**数组**而不是 ISO 字符串 ——
  //    实测 `[2026, 9, 18, 20, 0, 7]`。原因：该字段来自 ES 索引，
  //    ES 里的 datetime 被序列化成了数组形式。
  //    不处理这个分支的话，页面会渲染成 "[object Array]"（而且不报错，最容易被漏掉）。
  if (Array.isArray(value)) {
    const [y, mo, d, h = 0, mi = 0] = value
    const p = (n: number | undefined) => String(n ?? 0).padStart(2, '0')
    return `${y}-${p(mo)}-${p(d)} ${p(h)}:${p(mi)}`
  }

  // 课程详情等其他接口返回的是不带时区的 ISO 字符串（如 `2026-09-13T19:25:59`）
  // ★ 不要 `new Date()` 再 `toLocaleString()` —— 那会按浏览器时区再转一次，
  //   本机（东八区）看着没问题，换台机器就差 8 小时
  const m = String(value).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (!m) return String(value)
  return `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}`
}

/** 日期部分 → "2026-09-13" */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const m = String(value).match(/^(\d{4})-(\d{2})-(\d{2})/)
  return m ? `${m[1]}-${m[2]}-${m[3]}` : String(value)
}

/** 大数字缩写：1234 → "1234"，12345 → "1.2万" */
export function formatCount(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  if (n < 10000) return String(n)
  return `${(n / 10000).toFixed(1)}万`
}

/**
 * ★ 安全渲染搜索高亮
 *
 * 搜索接口在关键词命中时返回 `highlightName`，形如 `哑<em>铃</em>卧推`
 * —— 后端（ES 高亮）会往里塞 `<em>` 标签。
 *
 * ⚠️ **绝不能直接 `v-html`**：课程名是管理员在后台输入的内容，
 *   如果名字里带着 `<img src=x onerror=alert(1)>`，直接渲染就是标准的 XSS。
 *   "后端生成的高亮所以可信"这个假设不成立 —— 高亮的原料是用户输入。
 *
 * 做法：**先把整串转义，再把 `<em>`/`</em>` 两个标签单独还原回来**。
 * 这样只有白名单里的这一个标签能生效，其余 HTML 全部变成纯文本。
 */
export function safeHighlight(html: string | null | undefined): string {
  if (!html) return ''
  const escaped = String(html)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
  return escaped
    .replace(/&lt;em&gt;/g, '<em>')
    .replace(/&lt;\/em&gt;/g, '</em>')
}

/** 统一的标签颜色类型（对应 Element Plus 的 el-tag type） */
type TagType = 'success' | 'info' | 'warning' | 'danger' | 'primary'

/**
 * 订单状态 → 标签颜色（对应后端 `OrderStatus`：1 待支付 / 2 已支付 / 3 已关闭 / 5 已报名 / 6 退款中 / 7 已退款）
 *
 * ★ 只管**颜色**，不管**文案**：文案一律用后端返回的 `statusDesc`。
 *   前端再写一份映射表的话，将来后端加一个状态，就会出现"同一状态在列表页叫甲、在详情页叫乙"。
 */
export function orderStatusTagType(status: number | null | undefined): TagType {
  switch (status) {
    case 1:
      return 'warning' // 待支付：要用户做事，用醒目色
    case 2:
      return 'primary' // 已支付
    case 5:
      return 'success' // 已报名：终态且是好事
    case 6:
      return 'warning' // 退款中
    case 3: // 已关闭
    case 7: // 已退款
      return 'info'
    default:
      return 'info'
  }
}

/** 退款申请状态 → 标签颜色（1 待审批 / 2 已取消 / 3 同意退款 / 4 已拒绝 / 5 退款成功 / 6 退款失败） */
export function refundStatusTagType(status: number | null | undefined): TagType {
  switch (status) {
    case 1:
      return 'warning' // 待审批
    case 3:
      return 'primary' // 同意退款（还没到账）
    case 4:
    case 6:
      return 'danger' // 已拒绝 / 退款失败
    case 5:
      return 'success' // 退款成功
    default:
      return 'info' // 已取消
  }
}

/**
 * 解析后端时间（ISO 字符串或 ES 返回的数组）→ 毫秒时间戳
 *
 * ⚠️ 两个坑：
 *   1. 后端返回的 LocalDateTime 带 **7 位**小数（`2026-09-18T21:01:21.8053367`），
 *      而 JS 的 ISO 解析只认 3 位毫秒 —— 直接 `new Date(str)` 在部分引擎上得到 Invalid Date。
 *      所以这里自己按字段切，不用 Date 的字符串解析。
 *   2. 串里**没有时区信息**（后端存的是本地时间），所以必须用 `new Date(y, m, d, ...)`
 *      这种"本地时区"的构造方式，不能用 `Date.parse`（那会按 UTC 理解一部分格式）。
 */
export function parseBackendTime(value: string | number[] | null | undefined): number | null {
  if (!value) return null
  if (Array.isArray(value)) {
    const [y, mo, d, h = 0, mi = 0, s = 0] = value
    if (!y) return null
    return new Date(y, (mo ?? 1) - 1, d ?? 1, h, mi, s).getTime()
  }
  const m = String(value).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/)
  if (!m) return null
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime()
}

/** 距离某个时刻还有多久（用于"订单 15 分钟内未支付将自动关闭"） */
export function formatRemaining(target: string | number[] | null | undefined, now = Date.now()): string {
  const t = parseBackendTime(target)
  if (t === null) return '—'
  const diff = t - now
  if (diff <= 0) return '已超时'
  const total = Math.floor(diff / 1000)
  const m = Math.floor(total / 60)
  const s = total % 60
  return m > 0 ? `${m} 分 ${String(s).padStart(2, '0')} 秒` : `${s} 秒`
}

// ============================================================
// 以下是**管理端专有**的展示工具（用户端用不到）
// ============================================================

/**
 * 课程状态 → 标签颜色（后端 `CourseStatus`：1 待上架 / 2 已上架 / 3 下架 / 4 已完结）
 *
 * ★ 与订单/退款状态一样：**只管颜色，文案一律用后端的 `statusDesc`**。
 */
export function courseStatusTagType(status: number | null | undefined): TagType {
  switch (status) {
    case 1:
      return 'info' // 待上架：还没对外，不需要醒目
    case 2:
      return 'success' // 已上架：正常在售
    case 3:
      return 'warning' // 下架：需要关注（是不是误操作）
    case 4:
      return 'info' // 已完结
    default:
      return 'info'
  }
}

/**
 * 媒资状态 → 标签颜色（后端 `FileStatus`：1 上传中 / 2 已上传 / 3 已使用）
 *
 * ★ "已使用"是**好事**（有课程在引用它），所以给 success 而不是 warning ——
 *   这与"上传中"要区分开：前者是终态、后者是中间态且可能永久卡住。
 */
export function mediaStatusTagType(status: number | null | undefined): TagType {
  switch (status) {
    case 1:
      return 'warning' // 上传中（可能卡住，需要关注）
    case 2:
      return 'primary' // 已上传（可用但还没被引用）
    case 3:
      return 'success' // 已使用
    default:
      return 'info'
  }
}

/** 字节数 → "12.3 MB" */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || bytes < 0) return '—'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i += 1
  }
  return `${i === 0 ? v : v.toFixed(1)} ${units[i]}`
}

/**
 * 分 → 带千分位的元（看板用）
 *
 * ★ 与 `formatPrice` 的区别：那个是**商品价签**（`¥199.00`），
 *   这个是**统计金额**（`¥12,345.67`）—— 看板上几千几万的数字没有千分位很难读。
 *   两者分开是有意的：价签带千分位反而显得奇怪。
 */
export function formatYuan(fen: number | string | null | undefined): string {
  if (fen === null || fen === undefined || fen === '') return '—'
  const n = Number(fen)
  if (!Number.isFinite(n)) return '—'
  return `¥${(n / 100).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')}`
}

/** 纯数字的千分位（用于计数类指标） */
export function formatThousands(n: number | string | null | undefined): string {
  if (n === null || n === undefined || n === '') return '—'
  const v = Number(n)
  if (!Number.isFinite(v)) return '—'
  return v.toLocaleString('zh-CN')
}

/**
 * `yyyyMMdd` 整数 → `2026-09-20`
 *
 * ★ 数据看板的日期参数与返回都是**整数**（`statDate` / `dates`），
 *   直接用会显示成 `20260920`。
 */
export function formatStatDate(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—'
  const s = String(v)
  if (s.length !== 8) return s
  return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
}

/** `2026-09-20` → `20260920`（拉数据时反向转换） */
export function toStatDate(d: Date): number {
  const p = (n: number) => String(n).padStart(2, '0')
  return Number(`${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`)
}
