/**
 * Markdown 渲染（AI 回答专用）
 *
 * ============================================================
 * ★ 为什么回答**必须**渲染 Markdown
 * ============================================================
 * 实测真实模型的输出大量使用 Markdown：`**加粗结论**`、有序/无序列表、
 * 偶尔还有表格。当纯文本渲染时会出现满屏 `**` 和 `###` —— 看起来像"AI 坏了"，
 * 其实是前端少做了一步。
 *
 * ============================================================
 * ★★ 为什么必须 `html: false`（安全）
 * ============================================================
 * 回答文本里**可能回显用户输入**（模型会说"关于「<你问的原话>」……"）。
 * 一旦允许原始 HTML，用户在输入框里写 `<img onerror=...>` 就能在别人的浏览器里执行 ——
 * 这是一个**用户可控 → 页面执行**的链路，属于存储型 XSS 的典型形态。
 *
 * `html: false` 会把原文里的 HTML 当普通文本转义输出，
 * 于是"能渲染 Markdown 的排版"与"不能注入 HTML"两件事同时成立。
 *
 * ============================================================
 * ★ 关于"流式过程中的半截 Markdown"
 * ============================================================
 * 流式渲染时每一帧都是"半截文本"：未闭合的 `**`、还没写完的表格、刚开头的代码块，
 * 都可能短暂渲染成奇怪的样子，等下一帧补全就正常了。
 * 这是**流式 Markdown 的固有现象**，不是 bug；换来的是"边写边变色"的即时反馈。
 * （另一种做法是流式期间只显示纯文本、结束后再渲染 —— 那样结尾会有一次明显的"跳变"，
 *   体验反而更差。）
 */

import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  // ★ 见上面第二条：不允许原始 HTML
  html: false,
  // 自动把裸链接变成可点的 <a>
  linkify: true,
  // 单个换行也当换行（模型很爱用单换行分行，按标准 Markdown 会粘成一行）
  breaks: true,
})

// 外链一律新窗口打开，并断开 opener（避免 tabnabbing）
const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(text: string | null | undefined): string {
  if (!text) return ''
  return md.render(text)
}
