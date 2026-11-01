/**
 * 直传对象存储（MinIO）的工具。
 *
 * ★ 与 `fitmind-admin/src/views/MediaAdminView.vue` 里那套是**同一套逻辑**：
 *   两端是独立打包的两个应用（没有共享包），只能各放一份 ——
 *   改其中一边时记得同步另一边。
 *
 * ★★ 三条硬约束（都是本项目撞过的）：
 *
 *  ① **上传是三步**：申请签名 → PUT 直传 MinIO → confirm。
 *     中间那步**不经过我们的网关**（拿着签名直连 MinIO），
 *     所以它不能走 `src/utils/request.ts` 那个 axios 实例：
 *     既会把 `Authorization` 带过去，响应拦截器还会拿 MinIO 返回的 XML
 *     去按 `R<T>` 解包。
 *
 *  ② **PUT 必须带 `Content-Type`**。不带的话 MinIO 会把对象存成
 *     `binary/octet-stream`，于是**浏览器不把非 `image/*`／`video/*` 的响应当图片/视频渲染**
 *     —— 直链明明返回 200，`<img>` 照样裂。这个坑在 M7-7 撞过一次（509 个对象里 51 个中招），
 *     而且症状会把排查方向带到"桶策略 / 鉴权"上去。
 *
 *  ③ **上传进度只能用 XHR**：`fetch` 没有上传进度事件。
 *     一个 50MB 的视频没有进度条，用户会以为卡死然后刷新页面。
 */

/** `POST /medias/upload-signature` 的返回 */
export interface UploadSignature {
  /** 预先创建的媒资 id，第③步确认时要带回来 */
  mediaId: string
  /** 对象存储里的 key */
  key: string
  /** 预签名 PUT 地址（前端直传用） */
  uploadUrl: string
  /** 签名有效期，单位秒 */
  expire: number
  /**
   * ★★ 直传时**必须原样带上**的 Content-Type（M9-1 起）。
   *
   * 阿里云 OSS 的 V4 签名把 Content-Type 算进签名，而浏览器直传 `File` 时
   * 一定会带这个头 —— 一旦与后端签名时用的值不一致，就是
   * `403 SignatureDoesNotMatch`，报错完全看不出是这个原因。
   *
   * 所以值由**后端算好回传**，这里照带即可，别再自己猜。
   *
   * ⚠️ MinIO 模式下后端不返回它（`undefined`）—— 那时沿用原有的自猜逻辑，行为不变。
   */
  contentType?: string
}

/**
 * 按扩展名猜 MIME。
 *
 * ★ 为什么需要：`file.type` 在部分环境（老浏览器、某些安卓 WebView、
 *   拖拽进来的文件）会是**空串**，而空串同样会让 MinIO 存成 `octet-stream`。
 */
export function guessMime(name: string): string {
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
  return map[ext] ?? 'application/octet-stream'
}

/**
 * 用 `<video>` 读出时长（后端 `confirm` 需要它；前端不读，这个字段就只能是 0）。
 *
 * ★ 读不出来**返回 0 而不是 reject**：时长不是必需信息，
 *   为了它让整次上传失败是本末倒置（后端也接受 `duration = 0`）。
 */
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
    v.onerror = () => {
      URL.revokeObjectURL(url)
      resolve(0)
    }
    v.src = url
  })
}

/**
 * PUT 直传到对象存储（用 XHR 才能拿到上传进度）。
 *
 * ★★ `mime` 的取值来源见 `UploadSignature.contentType` 的说明：
 *    OSS 模式必须用后端回传的值（V4 把 Content-Type 算进签名），MinIO 模式才自猜。
 *    调用点写的是 `sig.contentType || file.type || guessMime(file.name)`。
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
  onProgress: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    // ★ 错误信息里带上目标 host —— 桶名配错 / CORS 没配 / 网络不通，
    //   浏览器给的都是同一句"CORS 错误"或 404，只有把地址打出来才分得清。
    const target = hostOf(url)
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url, true)
    // ★ 见文件头 ②
    xhr.setRequestHeader('Content-Type', mime)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new Error(`直传失败：对象存储返回 HTTP ${xhr.status}（${target}）`))
    }
    xhr.onerror = () => reject(new Error(`直传失败：${target} 不可达，或被跨域(CORS)拦截`))
    xhr.send(file)
  })
}
