import { http } from '@/utils/request'
import { guessMime, putToMinio, readVideoDuration, type UploadSignature } from '@/utils/upload'

/**
 * 媒资接口（fm-media，网关前缀 `/ms`）
 *
 * 本文件有两个用途：
 *   ① **播视频**：拿预签名播放地址（M7-4 起）
 *   ② **上传视频**：动作考核要学员自己传（M7-10 新增）
 *
 * ★ 关于权限（M7-10 起，见 fm-media 的 `fm.auth.resource.admin.pathPatterns`）：
 *   `POST /medias/upload-signature` 与 `POST /medias/{id}/confirm` **只要求登录、不要求管理员** ——
 *   因为**学员要上传动作考核视频**。而 `GET /medias`（分页）、`DELETE /medias/{id}`（删除）
 *   仍是管理端接口。
 *   ★ 这两类接口**路径形状完全一样**（都是 `/medias/*`），
 *     所以 fm-media 的配置用的是方法级写法 `DELETE:/medias/*` ——
 *     否则要么误伤学员上传、要么把删除放开。
 */

export interface MediaPlayUrl {
  /** 带签名的播放地址（★ 会过期，由 `expire` 给出秒数） */
  url: string
  /** 有效期，单位秒 */
  expire: number
  /** 视频时长，单位秒 */
  duration: number | null
}

/**
 * 获取播放地址（带签名）。
 *
 * ★ 不要缓存太久：地址到期后 `<video>` 会直接播放失败，
 *   而这个失败在界面上表现为"视频卡住不动"，很容易被当成网络问题。
 *   组件里每次进入动作时重新取一次最省心。
 */
export function getPlayUrl(mediaId: string): Promise<MediaPlayUrl> {
  return http.get<MediaPlayUrl>(`/ms/medias/${mediaId}/play-url`)
}

// ============================================================
// 上传（三步，不是一次 POST）
// ============================================================

/** 第①步：申请直传签名（服务端会先建一条"上传中"的媒资记录） */
export function applyUploadSignature(filename: string, size: number): Promise<UploadSignature> {
  return http.post<UploadSignature>('/ms/medias/upload-signature', null, {
    params: { filename, size },
  })
}

export interface ConfirmMediaBody {
  /** 视频时长（秒）—— 后端 `@NotNull`，所以至少要传 0 */
  duration: number
  /** 封面地址（选填；一般先传图片再回传它的 url） */
  coverUrl?: string
}

/** 第③步：确认上传（服务端会向 MinIO 核实对象真的存在） */
export function confirmUpload(
  mediaId: string,
  body: ConfirmMediaBody,
): Promise<Record<string, unknown>> {
  return http.post<Record<string, unknown>>(`/ms/medias/${mediaId}/confirm`, body)
}

/**
 * 完整的上传流程：申请签名 → 直传 MinIO → 确认。**成功返回 mediaId**。
 *
 * ★ 顺序不能变：`confirm` 之前必须先 PUT 成功，否则服务端去 MinIO 核实不到对象会报错。
 * ★ 第②步的 `Content-Type` 处理见 `utils/upload.ts` 的说明（不带会导致存成 octet-stream）。
 *
 * @param onStage 可选：把当前阶段回显给用户（"① 申请签名…"）——
 *                三步里有两步完全没进度（第①、③步都是普通请求），
 *                不给阶段提示的话，用户在大文件上传前会以为没反应。
 */
export async function uploadMedia(
  file: File,
  options: { onStage?: (s: string) => void; onProgress?: (p: number) => void } = {},
): Promise<string> {
  const { onStage, onProgress } = options

  onStage?.('① 申请上传签名…')
  const sig = await applyUploadSignature(file.name, file.size)
  if (!sig?.uploadUrl) throw new Error('没拿到上传地址')

  onStage?.('② 上传视频…')
  const mime = file.type || guessMime(file.name)
  await putToMinio(sig.uploadUrl, file, mime, (p) => onProgress?.(p))

  onStage?.('③ 读取时长并确认…')
  const duration = await readVideoDuration(file)
  await confirmUpload(sig.mediaId, { duration })

  return String(sig.mediaId)
}
