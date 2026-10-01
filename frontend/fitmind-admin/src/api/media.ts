/**
 * 媒资管理（fm-media，网关前缀 /ms）
 *
 * ⚠️ 与课程一样：fm-media **没有开** `admin.enabled` → 这些接口只校验登录（见 #A9）。
 *
 * ★ 上传是**三步**（不是一次 POST）：
 *   ① `POST /ms/medias/upload-signature` 申请签名（服务端先建一条"上传中"的媒资记录）
 *   ② 拿着 `uploadUrl` **直传 MinIO**（PUT，注意不是打我们的服务）
 *   ③ `POST /ms/medias/{id}/confirm` 确认（服务端会去 MinIO 核实对象真的存在）
 *
 * ★★ 第②步的 PUT **必须带 `Content-Type`**：
 *   不带的话 MinIO 会把对象存成 `binary/octet-stream`，
 *   然后**浏览器不会把非 `image/*` 的响应当图片渲染** —— `<img>` 直接 onerror，
 *   而直链明明返回 200。这个坑在 M7-7 撞过一次（509 个对象里 51 个受影响），
 *   表现是"封面怎么都不显示"，极易误判成桶策略/鉴权问题。
 */
import type { MediaQuery, MediaVO, PageDTO, UploadSignature } from '@/types/admin'
import { http } from '@/utils/request'

/** 媒资分页 */
export function getMediaPage(query: MediaQuery): Promise<PageDTO<MediaVO>> {
  return http.get<PageDTO<MediaVO>>('/ms/medias', { params: query })
}

/** 第①步：申请上传签名 */
export function applyUploadSignature(filename: string, size: number): Promise<UploadSignature> {
  return http.post<UploadSignature>('/ms/medias/upload-signature', null, {
    params: { filename, size },
  })
}

/** 第③步：确认上传（服务端会向 MinIO 核实对象存在） */
export function confirmUpload(
  mediaId: string,
  body: { duration: number; coverUrl?: string },
): Promise<Record<string, unknown>> {
  return http.post<Record<string, unknown>>(`/ms/medias/${mediaId}/confirm`, body)
}

/** 管理端预览地址（有效期更短） */
export function getPreviewUrl(mediaId: string): Promise<{ url: string; expire: number }> {
  return http.get<{ url: string; expire: number }>(`/ms/medias/${mediaId}/preview-url`)
}

/** 删除单个媒资（被课程引用时后端会拒绝 —— 这是保护，不是 bug） */
export function deleteMedia(mediaId: string): Promise<void> {
  return http.delete<void>(`/ms/medias/${mediaId}`)
}

/**
 * 批量删除媒资
 *
 * ★ `ids` 必须 `.join(',')`：Spring 的 `@RequestParam List<Long>` 期望的是
 *   `ids=1,2,3`，而 axios 默认会把数组序列化成 `ids[]=1&ids[]=2` ——
 *   两者对不上，后端会当成"没传参数"或解析失败（M7-3 已核实过同一问题）。
 */
export function deleteMedias(ids: string[]): Promise<void> {
  return http.delete<void>('/ms/medias', { params: { ids: ids.join(',') } })
}

// ==================== 通用文件上传（M7-12 第 2 期：课程封面） ====================

/**
 * 上传返回（`FileDTO`）
 *
 * ★★ 两个地址字段必须分清 —— **存错会"过一会儿变破图"**：
 *   · `url`    —— **带签名、有有效期**，只适合"临时看一眼"
 *   · `rawUrl` —— 不带签名的原始地址，**要长期存进 `coverUrl` 的必须是它**
 *
 *   这与 M7 §12.6 第 4 条拒绝"用预签名做封面"的理由同源：
 *   "有时好有时坏的东西比一直走兜底的更难排查"。
 */
export interface UploadedFile {
  /** Long → string（全局约定） */
  id: string
  filename: string
  /** 带签名的访问地址，**有有效期** */
  url: string
  /** 不带签名的原始地址（★ 入库用这个） */
  rawUrl: string
  size: number
}

/** 业务类型 —— 决定对象落在哪个前缀，而**前缀决定"能不能匿名读"** */
export type FileBizType = 'course-cover' | 'action-cover' | 'media-cover' | 'avatar' | 'common'

/**
 * 上传图片（服务端中转的 multipart，**不是**预签名直传）。
 *
 * ★★ 课程封面为什么走这里、而**不走**预签名媒资链路（依据是 M8-1 对 #B3 的收窄）：
 *   匿名读白名单里有 `course-cover/`、`avatar/` 等前缀，**没有 `media/`**
 *   （`media/` 是课程视频 + 学员考核视频，必须预签名）。
 *   `seed-demo-data.mjs` 当年把封面也丢进了 `media/`，只能靠"按扩展名放行"折中（#C31）。
 *   新建课**从源头就走对**：`bizType=course-cover` → 落 `course-cover/` → `<img src>` 直接可用。
 *
 * ⚠️ **不要手写 `Content-Type: multipart/form-data`** —— multipart 需要 `boundary`，
 *   手写一个不带 boundary 的头会让后端解析不出文件（M7-7 撞过 509 个对象）。
 *   交给浏览器按 `FormData` 自动生成即可。
 */
export function uploadFile(
  file: File,
  bizType: FileBizType = 'common',
  onProgress?: (percent: number) => void,
): Promise<UploadedFile> {
  const fd = new FormData()
  fd.append('file', file)
  return http.post<UploadedFile>('/ms/files', fd, {
    params: { bizType },
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}
