/**
 * 媒资直传 MinIO 的三个小工具（M7-12 第 2 期从 `MediaAdminView.vue` **原样搬出**）
 *
 * ★ 为什么搬出来：建课向导的第 3 步也要"给训练动作挂视频"，
 *   而这三个函数（猜 MIME / 读时长 / PUT 直传）与业务无关、纯技术细节。
 *   复制一份的代价是"以后改一处、忘一处" —— 尤其 `putToMinio` 里那个
 *   `Content-Type` 的坑（M7-7 撞过 509 个对象），所以收敛到一个地方。
 *
 * ⚠️ 搬运是**原样**的，行为与搬之前完全一致（`MediaAdminView` 已验收过）。
 */

/** 按扩展名猜 MIME（`file.type` 在部分环境下是空串） */
export function guessMime(name: string): { mime: string; guessed: boolean } {
  const ext = name.toLowerCase().slice(name.lastIndexOf('.') + 1)
  const map: Record<string, string> = {
    mp4: 'video/mp4',
    mov: 'video/quicktime',
    webm: 'video/webm',
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    webp: 'image/webp',
    svg: 'image/svg+xml',
  }
  return { mime: map[ext] ?? 'application/octet-stream', guessed: true }
}

/** 用 `<video>` 读出时长（后端 confirm 需要它；前端不读，这个字段就只能是 0） */
export function readVideoDuration(file: File): Promise<number> {
  return new Promise((resolve) => {
    if (!file.type.startsWith('video/') && !/\.(mp4|mov|webm)$/i.test(file.name)) {
      resolve(0)
      return
    }
    const url = URL.createObjectURL(file)
    const v = document.createElement('video')
    v.preload = 'metadata'
    v.onloadedmetadata = () => {
      const d = Number.isFinite(v.duration) ? Math.round(v.duration) : 0
      URL.revokeObjectURL(url)
      resolve(d)
    }
    // ★ 读不出来就返回 0，**不 reject** —— 时长不是必需信息，
    //   为了它让整次上传失败是本末倒置（后端也接受 duration=0）
    v.onerror = () => {
      URL.revokeObjectURL(url)
      resolve(0)
    }
    v.src = url
  })
}

/**
 * PUT 直传对象存储（用 XHR 才能拿到进度）。
 *
 * ★★ `Content-Type` **必须带**：不带的话 MinIO 会把对象存成 `binary/octet-stream`，
 *   然后**浏览器不会把非 `image/*` 的响应当图片渲染** —— `<img>` 直接 onerror，
 *   而直链明明返回 200。M7-7 撞过一次（509 个对象里 51 个受影响），
 *   表现是"封面怎么都不显示"，极易误判成桶策略/鉴权问题。
 *
 * ★★ M9-1 起 `mime` 的**取值来源变了**，调用方要注意：
 *   - 阿里云 OSS（V4 签名把 Content-Type 算进签名）→ 必须用**后端回传的**
 *     `sig.contentType`，否则 403 SignatureDoesNotMatch；
 *   - MinIO → 后端不回传，沿用"`file.type` → 按扩展名猜"的老逻辑。
 *   所以调用方写的是 `sig.contentType || file.type || mime`（见各个调用点）。
 */
/** 从 URL 里取 host（解析失败就原样返回），只为了让报错能说清"打到哪" */
function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

export function putToMinio(
  url: string,
  file: File,
  mime: string,
  onProgress: (p: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    // ★ 把目标 host 带进错误信息。出问题时（桶名配错 / CORS 没配 / 网络不通）
    //   浏览器只会给一句干巴巴的 "CORS 错误" 或 404，前端再翻译成"网络中断"，
    //   排查方向很容易被带偏 —— 2026-10-04 实测：桶名配成了 MinIO 的 `fitmind`，
    //   页面提示"MinIO 不可达"，真相却是 OSS 上没这个桶（预检 404 NoSuchBucket）。
    const target = hostOf(url)
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url, true)
    xhr.setRequestHeader('Content-Type', mime)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new Error(`对象存储返回 HTTP ${xhr.status}（${target}）：${xhr.responseText?.slice(0, 120) ?? ''}`))
    }
    xhr.onerror = () => reject(new Error(`直传失败：${target} 不可达，或被跨域(CORS)拦截`))
    xhr.send(file)
  })
}
