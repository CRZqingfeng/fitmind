import { http } from '@/utils/request'
import { readVideoDuration, type UploadSignature } from '@/utils/upload'
import { uploadResumable } from '@/utils/chunk-upload'

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

  /**
   * ★★ M12-1 起改走「分片上传」（`utils/chunk-upload.ts`）：
   *
   *   老链路 = 一次 PUT 传完整个视频。200MB 的考核视频在弱网下几乎必然失败，
   *   而且失败就是**从 0 开始**（学员在地铁里传了 8 分钟，切换一次网络就白费）。
   *   新链路的失败只是"少了几片"，重进页面时会问服务端还缺哪几片，接着传。
   *
   *   同时白拿一个能力：同内容的文件第二次上传会被**秒传**命中（不传字节）。
   *
   *   ⚠️ `applyUploadSignature` / `putToMinio` 仍然保留 ——
   *      管理端的课程视频上传还在用那条链路（两端是独立打包的两个应用，改一端不影响另一端）。
   *      统一它们是下一步（见 docs/待修复问题清单.md）。
   */
  const result = await uploadResumable(file, {
    // 视频落在 media/ 前缀（私有：mp4 不允许匿名读，播放必须签名）
    bizType: 'media',
    // ★ 产出 media 记录，调用方拿到的还是原来那个 mediaId，接口形状不变
    target: 'media',
    onStage,
    onProgress,
  })

  const mediaId = result.mediaId
  if (!mediaId) throw new Error('上传完成但没有返回媒资 id')

  /**
   * ★ 还要再调一次老接口：它负责把**时长**写进库（分片上传本身不读视频元数据）。
   *   它同时也是"服务端向对象存储核实大小"的那道校验，重复调用是幂等的。
   */
  onStage?.('④ 读取时长并确认…')
  const duration = await readVideoDuration(file)
  await confirmUpload(mediaId, { duration })

  return String(mediaId)
}

// ============================================================
// M7-12：通用文件（图片）上传 —— 与视频**不是同一条链路**
// ============================================================

/**
 * 文件业务类型（对应后端 `fm-media` 的 `BizType` 枚举）。
 *
 * ★ 它的作用不只是分类：**决定了对象落在桶里的目录前缀**，
 *   而前缀又决定「能不能匿名读」（见 M8-1 对 `#B3` 的收窄）：
 *
 *   | 前缀 | 匿名读 |
 *   |---|---|
 *   | `avatar/` `course-cover/` `action-cover/` `media-cover/` `common/` | ✅ 允许 |
 *   | `media/` 下的图片（按扩展名） | ⚠️ 折中放行 |
 *   | `media/` 下的视频 | ✗ 必须预签名 |
 *
 *   → 所以**头像/封面这类"要长期用 `<img>` 直接显示"的图，必须走这里**，
 *     而不是走 `uploadMedia`（后者落 `media/`，只能靠扩展名折中兜住）。
 */
export type FileBizType = 'avatar' | 'course-cover' | 'action-cover' | 'media-cover' | 'common'

/**
 * `POST /ms/files` 的返回（后端 `FileDTO`）。
 *
 * ★★ 两个地址字段必须分清，**存错会"过一会儿变破图"**：
 *   · `url`    —— **带签名、有有效期**，适合"临时看一眼"
 *   · `rawUrl` —— 不带签名的原始地址，**要长期存进 `icon` / `coverUrl` 的必须是它**
 *
 *   这与 M7 §12.6 第 4 条拒绝"用预签名做封面"的理由同源：
 *   "预签名 URL 有有效期，图片会过一会儿变破图 —— 一个有时好有时坏的封面比
 *    一直走兜底的更难排查"。
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

/**
 * 上传图片（服务端中转的 multipart，**不是**预签名直传）。
 *
 * 后端 `FileController` 的类注释说明了为什么图片走中转、视频走直传：
 * "图片体积不大，走服务端中转即可；视频走 {@link MediaController} 的预签名直传。"
 *
 * ⚠️ **不要手写 `Content-Type: multipart/form-data`** ——
 *    multipart 需要 `boundary`，手写一个不带 boundary 的头会让后端解析不出文件。
 *    这里交给浏览器/axios 自己依据 `FormData` 生成（axios 1.x 会自动补 boundary）。
 *
 * @param onProgress 上传进度（0~100）。小图其实很快，但头像也可能是一张几 MB 的原图，
 *                   没有进度用户会以为没反应。
 */
export function uploadImage(
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

/** 获取文件信息（返回带签名的可访问地址） */
export function getFileInfo(id: string): Promise<UploadedFile> {
  return http.get<UploadedFile>(`/ms/files/${id}`)
}

/**
 * 删除文件。
 *
 * ⚠️ ★ 后端 `DELETE /files/{id}` **没有归属校验**（已登记为 `#A14`）——
 *    任何登录用户都能删**别人**上传的文件。
 *    所以前端只在自己"换了头像、要清理刚上传的那张旧图"时调用它，
 *    且**只删本次上传返回的 id**，不要把它做成一个"任意 id 都能删"的入口。
 */
export function deleteFile(id: string): Promise<void> {
  return http.delete<void>(`/ms/files/${id}`)
}
