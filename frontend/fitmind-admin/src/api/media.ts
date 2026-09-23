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
