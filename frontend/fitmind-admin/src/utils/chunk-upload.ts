import { http } from '@/utils/request'

/**
 * 分片上传（断点续传 + 秒传）—— M12-1。
 *
 * ★ 与 `fitmind-portal/src/utils/chunk-upload.ts` 是**同一份逻辑**：
 *   两个应用独立打包、没有共享包，只能各放一份 —— 改这里记得同步那边。
 *   （管理端「课程动作视频」与学员端「动作考核视频」用的是同一条后端链路。）
 *
 * ★★ 与老的三步直传（`api/media.ts` 的 `applyUploadSignature → putToMinio → confirmUpload`）
 *    的区别不是"实现方式"，而是**失败之后会怎样**：
 *    老三步是一次 PUT 传完，断了就从 0 开始；这里是「init → 每片一个请求 → complete」，
 *    断了只是"少了几片"，重新进来问一句服务端还缺哪几片就行。
 *
 * ★★ 三条硬约束（都是踩过的）：
 *  ① **分片大小以服务端返回为准**。本地按 `chunkSize` 自己切，服务端却用 5MiB 切 ——
 *     序号就会错位，续传必然传出一堆"大小不符"。所以 init 之后只用响应里的 `chunkSize`。
 *  ② **进度以服务端回执为准**，不要自己 `已传片数 / 总片数` 算 ——
 *     重传同一片不会让服务端计数 +1，自己算会算出 101%。
 *  ③ **恢复不要依赖 localStorage**：刷新页面后拿同一个文件再 `init` 一次，
 *     服务端会返回同一个 taskId 和已传分片。本地状态会丢、会被清，服务端不会。
 */

/** 服务端 init / progress 的返回（Long 全局序列化成 string） */
export interface InitUploadResult {
  instant: boolean
  taskId: string | null
  file?: { id: string; url: string; rawUrl: string; size: string; filename: string } | null
  mediaId?: string | null
  objectKey?: string
  chunkSize?: number
  chunkTotal?: number
  uploadedChunks?: number[]
  uploadedBytes?: string
  uploadedPercent?: number
}

/** 单片上传回执 */
export interface PartUploadResult {
  partNumber: number
  chunkTotal: number
  uploadedChunks: number[]
  uploadedBytes: string
  uploadedPercent: number
}

/** complete 的返回（后端 FileDTO，可能带 mediaId） */
export interface CompleteUploadResult {
  id: string
  filename: string
  url: string
  rawUrl: string
  size: string
  mediaId?: string | null
}

export interface ChunkUploadOptions {
  /** 业务类型，决定对象前缀：avatar / course-cover / action-cover / media-cover / media / common */
  bizType: string
  /** 产出类型：file-通用文件（默认）/ media-视频媒资（会额外返回 mediaId） */
  target?: 'file' | 'media'
  /** 阶段文案（"校验中/上传中/合并中"），用来给用户交代"现在在干什么" */
  onStage?: (stage: string) => void
  /** 百分比 0-100，取服务端回执 */
  onProgress?: (percent: number) => void
}

/** 分片大小：与后端 MIN_CHUNK_SIZE 对齐（S3 规定 ≥5MiB） */
const CHUNK_SIZE = 5 * 1024 * 1024

/**
 * 算整个文件的 SHA-256（秒传的唯一依据）。
 *
 * ⚠️ 这里把整个文件读进内存（`arrayBuffer()`），200MB 的文件会占 200MB。
 *   为什么先这么写：浏览器的 `crypto.subtle` **不支持流式**摘要，
 *   要做增量得引入 hash-wasm / spark-md5 之类的依赖。
 *   本项目的上传上限是 5GiB，但实际使用（动作考核视频）在 200MB 以内，桌面端可接受。
 *   真要支持超大文件，正确做法是换**增量哈希库**，而不是"只算前 1MB"——
 *   抽样哈希会产生**哈希碰撞**，而秒传撞一次的后果是"你拿到的是别人的文件"。
 */
export async function sha256Hex(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * 分片上传主流程。
 *
 * 返回 `fileId`（文件记录 id）与 `mediaId`（target=media 时才有）。
 */
export async function uploadResumable(file: File, options: ChunkUploadOptions): Promise<CompleteUploadResult> {
  const { bizType, target = 'file', onStage, onProgress } = options

  onStage?.('① 校验文件（算指纹，用于秒传）…')
  const hash = await sha256Hex(file)

  const init = await http.post<InitUploadResult>('/ms/files/uploads', {
    hash,
    filename: file.name,
    size: file.size,
    bizType,
    target,
    chunkSize: CHUNK_SIZE,
  })

  // 秒传：服务端已有同内容的物理对象，一个字节都不用传
  if (init.instant && init.file) {
    onProgress?.(100)
    onStage?.('已存在相同文件，秒传完成')
    return {
      id: init.file.id,
      filename: init.file.filename,
      url: init.file.url,
      rawUrl: init.file.rawUrl,
      size: init.file.size,
      mediaId: init.mediaId ?? null,
    }
  }

  const taskId = init.taskId
  if (!taskId) throw new Error('初始化上传失败：没拿到任务 id')

  // ★ 只用服务端给的分片大小（见文件头 ①）
  const chunkSize = init.chunkSize ?? CHUNK_SIZE
  const chunkTotal = init.chunkTotal ?? Math.ceil(file.size / chunkSize)
  const uploaded = new Set<number>(init.uploadedChunks ?? [])

  if (uploaded.size > 0) {
    onStage?.(`检测到上次传到第 ${uploaded.size}/${chunkTotal} 片，继续…`)
  }
  onProgress?.(init.uploadedPercent ?? 0)

  for (let partNumber = 1; partNumber <= chunkTotal; partNumber++) {
    if (uploaded.has(partNumber)) continue
    const start = (partNumber - 1) * chunkSize
    const chunk = file.slice(start, Math.min(start + chunkSize, file.size))
    const form = new FormData()
    // 字段名必须是后端 @RequestPart("chunk")
    form.append('chunk', chunk, `${file.name}.part${partNumber}`)
    // ⚠️ 不要手动设 Content-Type：boundary 由浏览器生成，手写会缺失 boundary 导致 400
    const part = await http.put<PartUploadResult>(`/ms/files/uploads/${taskId}/parts/${partNumber}`, form)
    // ★ 进度用服务端回执（见文件头 ②）
    onProgress?.(part.uploadedPercent)
    onStage?.(`② 上传中 ${part.uploadedChunks.length}/${part.chunkTotal} 片`)
  }

  onStage?.('③ 合并分片…')
  const done = await http.post<CompleteUploadResult>(`/ms/files/uploads/${taskId}/complete`)
  onProgress?.(100)
  return done
}
