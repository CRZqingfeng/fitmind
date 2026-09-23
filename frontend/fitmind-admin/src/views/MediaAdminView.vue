<script setup lang="ts">
import { Delete, Refresh, Search, Upload, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { applyUploadSignature, confirmUpload, deleteMedias, getMediaPage, getPreviewUrl } from '@/api/media'
import CoverThumb from '@/components/CoverThumb.vue'
import type { MediaVO } from '@/types/admin'
import { MEDIA_STATUS } from '@/types/admin'
import { formatDateTime, formatDuration, formatFileSize, mediaStatusTagType, pageTotal } from '@/utils/format'

/**
 * 媒资管理（预签名直传）
 *
 * ★★ 这里是本项目**唯一**"前端直接跟对象存储打交道"的地方，三条硬约束：
 *
 *  ① **上传是三步**：申请签名 → PUT 直传 MinIO → confirm。
 *     中间那步**不经过我们的网关**（带着签名的 URL 直连 MinIO），
 *     所以它不能走 axios 实例 —— 那会把 `Authorization` 头也带过去，
 *     而且响应拦截器会试图把 MinIO 的 XML 当成 `R<T>` 解包。
 *
 *  ② **必须带 `Content-Type`**。不带的话 MinIO 存成 `binary/octet-stream`，
 *     然后**浏览器不会把非 `image/*` 当图片渲染** —— 直链返回 200，`<img>` 照样裂。
 *     这个坑在 M7-7 撞过（桶里 509 个对象有 51 个中招），
 *     且症状会把排查方向带到"桶策略/鉴权"上去。这里**显式取 file.type**，
 *     取不到就按扩展名兜一层（并明确告诉用户我们在兜）。
 *
 *  ③ **上传进度只能用 XHR**。`fetch` 拿不到上传进度事件 ——
 *     一个 500MB 的视频没有进度条，用户会以为卡死了然后刷新页面。
 */
const loading = ref(false)
const rows = ref<MediaVO[]>([])
const total = ref(0)
const selected = ref<MediaVO[]>([])
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadStage = ref('')

const query = reactive({
  pageNo: 1,
  pageSize: 10,
  name: '',
  status: undefined as number | undefined,
})

const fileInput = ref<HTMLInputElement | null>(null)

async function load() {
  loading.value = true
  try {
    const page = await getMediaPage(query)
    rows.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

/** 按扩展名猜 MIME（`file.type` 在部分环境下是空串） */
function guessMime(name: string): { mime: string; guessed: boolean } {
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
function readVideoDuration(file: File): Promise<number> {
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

/** PUT 直传（用 XHR 才能拿到进度） */
function putToMinio(url: string, file: File, mime: string, onProgress: (p: number) => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url, true)
    xhr.setRequestHeader('Content-Type', mime)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new Error(`MinIO 返回 HTTP ${xhr.status}：${xhr.responseText?.slice(0, 120) ?? ''}`))
    }
    xhr.onerror = () => reject(new Error('直传失败（网络中断或 MinIO 不可达）'))
    xhr.send(file)
  })
}

async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许连续上传同一个文件
  if (!file) return

  const { mime } = guessMime(file.name)
  const finalMime = file.type || mime
  if (!file.type) {
    console.log(`[upload] 浏览器未给出 MIME，按扩展名兜底为 ${finalMime}`)
  }

  uploading.value = true
  uploadPercent.value = 0
  try {
    uploadStage.value = '① 申请上传签名…'
    const sig = await applyUploadSignature(file.name, file.size)
    if (!sig?.uploadUrl) throw new Error('没拿到上传地址')

    uploadStage.value = '② 直传对象存储…'
    await putToMinio(sig.uploadUrl, file, finalMime, (p) => (uploadPercent.value = p))

    uploadStage.value = '③ 读取时长并确认…'
    const duration = await readVideoDuration(file)
    await confirmUpload(sig.mediaId, { duration })

    ElMessage.success(`上传成功（${formatFileSize(file.size)}，时长 ${duration}s）`)
    query.pageNo = 1
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '上传失败')
  } finally {
    uploading.value = false
    uploadStage.value = ''
    uploadPercent.value = 0
  }
}

async function handlePreview(row: MediaVO) {
  try {
    const r = await getPreviewUrl(row.id)
    if (r?.url) window.open(r.url, '_blank', 'noopener')
  } catch {
    /* 已提示 */
  }
}

async function handleDelete(rowsToDelete: MediaVO[]) {
  if (!rowsToDelete.length) return
  const names = rowsToDelete.map((r) => r.filename).join('、')
  try {
    await ElMessageBox.confirm(
      `确认删除 ${rowsToDelete.length} 个媒资？\n${names}\n\n★ 已被课程引用的媒资后端会拒绝删除（这是保护，不是故障）。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
    await deleteMedias(rowsToDelete.map((r) => r.id))
    ElMessage.success('已删除')
    selected.value = []
    await load()
  } catch {
    /* 取消 / 已提示 */
  }
}

onMounted(load)
</script>

<template>
  <div class="fm-page media-admin">
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-input
          v-model="query.name"
          placeholder="按名称搜索"
          clearable
          style="width: 200px"
          data-testid="media-filter-name"
          @keyup.enter="query.pageNo = 1; load()"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select
          v-model="query.status"
          placeholder="状态"
          clearable
          style="width: 130px"
          data-testid="media-filter-status"
        >
          <el-option label="上传中" :value="MEDIA_STATUS.UPLOADING" />
          <el-option label="已上传" :value="MEDIA_STATUS.UPLOADED" />
          <el-option label="已使用" :value="MEDIA_STATUS.USED" />
        </el-select>

        <el-button type="primary" :icon="Search" @click="query.pageNo = 1; load()">查询</el-button>
        <el-button :icon="Refresh" @click="query.name = ''; query.status = undefined; query.pageNo = 1; load()">
          重置
        </el-button>

        <div class="right-group">
          <el-button
            type="danger"
            plain
            :icon="Delete"
            :disabled="!selected.length"
            data-testid="media-batch-delete"
            @click="handleDelete(selected)"
          >
            删除选中（{{ selected.length }}）
          </el-button>

          <!-- ★ 不引 el-upload：它带来一整套自己的状态机（fileList / on-change / http-request），
               而这个场景要的只是"拿到 File 对象"，用原生 input + ref 触发更直接，
               也少一层"上传进度到底由谁负责"的歧义（进度由我们的 XHR 报）。 -->
          <el-button
            type="primary"
            :icon="Upload"
            :loading="uploading"
            data-testid="media-upload-btn"
            @click="fileInput?.click()"
          >
            上传媒资
          </el-button>
          <input
            ref="fileInput"
            class="file-input"
            type="file"
            accept="video/*,image/*"
            data-testid="media-file-input"
            @change="handleFileChange"
          />

          <span class="total-hint" data-testid="media-total">共 {{ total }} 个</span>
        </div>
      </div>

      <el-progress
        v-if="uploading"
        class="upload-progress"
        :percentage="uploadPercent"
        :stroke-width="14"
        text-inside
      />
      <div v-if="uploading" class="upload-stage">{{ uploadStage }}</div>

      <div class="hint-line">
        ★ 上传三步：申请签名 → 直传对象存储 → 确认。
        直传这一步**不经过网关**（所以不占带宽、也不受网关超时限制）。
        ★★ 直传时会显式带上 `Content-Type` —— 不带的话对象会被存成
        <code>binary/octet-stream</code>，直链虽然返回 200，但浏览器**不当图片渲染**，
        封面就会一直"裂"（这个坑在 M7-7 真踩过）。
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        row-key="id"
        data-testid="media-table"
        empty-text="还没有媒资"
        @selection-change="(v: MediaVO[]) => (selected = v)"
      >
        <el-table-column type="selection" width="46" />

        <el-table-column label="预览" width="90">
          <template #default="{ row }">
            <CoverThumb :src="row.coverUrl" :name="row.filename" :seed="row.id" :width="72" :height="45" />
          </template>
        </el-table-column>

        <el-table-column label="名称" min-width="220">
          <template #default="{ row }">
            <div class="media-name" data-testid="media-name">{{ row.filename }}</div>
            <div class="media-sub">id={{ row.id }} · {{ formatDateTime(row.createTime) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="时长" width="110">
          <template #default="{ row }">{{ formatDuration(row.duration) }}</template>
        </el-table-column>

        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ formatFileSize(row.size) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="mediaStatusTagType(row.status)" size="small">
              {{ row.status === 1 ? '上传中' : row.status === 2 ? '已上传' : row.status === 3 ? '已使用' : '—' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="被引用" width="90">
          <template #default="{ row }">
            <span :class="{ 'used-count': (row.useTimes ?? 0) > 0 }">{{ row.useTimes ?? 0 }} 次</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="VideoPlay" data-testid="media-preview" @click="handlePreview(row as MediaVO)">
              预览
            </el-button>
            <el-button link type="danger" :icon="Delete" data-testid="media-delete" @click="handleDelete([row as MediaVO])">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="total"
        :page-sizes="[10, 20, 50]"
        data-testid="media-pager"
        @current-change="load"
        @size-change="query.pageNo = 1; load()"
      />
    </el-card>
  </div>
</template>

<style scoped>
.media-admin {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.filter-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.right-group {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.total-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.uploader {
  display: inline-flex;
}

/* 原生 file input：藏起来但**保持可点**（display:none 会让部分浏览器的
   `label[for]` 触发失效，用绝对定位移出视口更稳） */
.file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.upload-progress {
  margin-top: 12px;
}

.upload-stage {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.hint-line {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.8;
}

.media-name {
  font-weight: 600;
}

.media-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.used-count {
  color: var(--el-color-success);
  font-weight: 600;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
