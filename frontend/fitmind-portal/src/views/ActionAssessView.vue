<script setup lang="ts">
import { Refresh, Upload, VideoCamera } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import { getActionAssessDetail, pageMyActionAssess, submitActionAssess } from '@/api/assessment'
import { getPlayUrl, uploadMedia } from '@/api/media'
import type { ActionAssessment } from '@/types/assessment'
import { ACTION_STATUS } from '@/types/assessment'
import { formatDateTime, pageTotal } from '@/utils/format'

/**
 * 动作考核（M7-10）
 *
 * 学员录一段自己做动作的视频 → 教练打分 + 评语。
 *
 * ★★ 这个页面的重点在"上传"这一段的体验，因为它有**三步且只有一步有进度**：
 *     ① `POST /medias/upload-signature`  申请签名   ← 普通请求，没有进度
 *     ② `PUT` 直传对象存储（MinIO）        ← 只有这步有进度，而且**不经过我们的网关**
 *     ③ `POST /medias/{id}/confirm`      确认       ← 普通请求，没有进度
 *   不给阶段提示的话，用户点完按钮会有一段时间"什么都没发生"（第①步之前、
 *   第③步之后再各一次），很容易以为没点成功然后反复点。
 *   → 所以下面用 `stage` 把当前阶段显式回显出来。
 *
 * ★ 上传的三条硬约束（Content-Type / 不走 axios / XHR 进度）见 `utils/upload.ts`。
 *   其中"**PUT 必须带 Content-Type**"这条最坑：不带的话对象存成
 *   `binary/octet-stream`，浏览器不把它当视频渲染 —— 直链 200，播放器照样不动。
 */
const loading = ref(false)
const rows = ref<ActionAssessment[]>([])
const total = ref(0)

// ---------- 上传 ----------
const dialogVisible = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const pickedFile = ref<File | null>(null)
const remark = ref('')
const uploading = ref(false)
const uploadPercent = ref(0)
const stage = ref('')

// ---------- 详情 ----------
const detailVisible = ref(false)
const detail = ref<ActionAssessment | null>(null)
/**
 * ★ 真正喂给 `<video>` 的地址。
 *   不是 `detail.videoUrl`（那字段一直是 null），而是拿到 `mediaId` 后
 *   **现取一次** `play-url` —— 签名地址会过期，现取才有效。
 */
const videoSrc = ref('')

async function load() {
  loading.value = true
  try {
    const page = await pageMyActionAssess({ pageNo: 1, pageSize: 20 })
    rows.value = page.list ?? []
    total.value = pageTotal(page)
  } catch {
    /* 已由 request.ts 统一提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openUpload() {
  pickedFile.value = null
  remark.value = ''
  uploadPercent.value = 0
  stage.value = ''
  dialogVisible.value = true
}

function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  pickedFile.value = input.files?.[0] ?? null
  input.value = '' // 允许连续选同一个文件
}

async function doUpload() {
  const file = pickedFile.value
  if (!file) {
    ElMessage.warning('请先选择视频文件')
    return
  }
  uploading.value = true
  uploadPercent.value = 0
  try {
    const mediaId = await uploadMedia(file, {
      onStage: (s) => (stage.value = s),
      onProgress: (p) => (uploadPercent.value = p),
    })
    stage.value = '④ 提交考核…'
    await submitActionAssess({
      mediaId,
      remark: remark.value.trim() || undefined,
    })
    ElMessage.success('已提交，等教练评分')
    dialogVisible.value = false
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '上传失败')
  } finally {
    uploading.value = false
    stage.value = ''
    uploadPercent.value = 0
  }
}

/**
 * 打开详情时**重新拉一次接口**，而不是直接用列表里的对象。
 *
 * ★ 因为 `videoUrl` 是**带签名的临时地址**，列表可能是几分钟前拉的 ——
 *   直接复用旧地址的话，视频会"卡住不动"（地址过期），
 *   而用户完全看不出是签名过期还是网络问题。
 */
async function openDetail(row: ActionAssessment) {
  detail.value = row
  detailVisible.value = true
  videoSrc.value = ''
  try {
    const fresh = await getActionAssessDetail(row.id)
    if (fresh) detail.value = fresh
  } catch {
    /* 拉不到就用列表里的（信息还能看，只是视频可能取不到） */
  }
  // ★ 用 mediaId 现取播放地址（见 videoSrc 的说明）
  const mid = detail.value?.mediaId
  if (!mid) return
  try {
    const play = await getPlayUrl(String(mid))
    videoSrc.value = play?.url ?? ''
  } catch {
    videoSrc.value = ''
  }
}

function statusTagType(row: ActionAssessment) {
  return row.status === ACTION_STATUS.REVIEWED ? 'success' : 'warning'
}
</script>

<template>
  <div class="action-page">
    <div class="fm-card head-card">
      <div class="head-left">
        <div class="fm-card-title">
          <el-icon><VideoCamera /></el-icon>
          动作考核
        </div>
        <p class="sub">
          录一段你做某个训练动作的视频传上来，教练会给你打分并留下评语。
          <br />
          ★ 建议：<b>侧面或正面固定机位</b>，把要看的部位（膝盖、腰背、肩）拍全，
          10~30 秒就够 —— 太长反而不好看细节。
        </p>
      </div>
      <div class="head-right">
        <el-button type="primary" :icon="Upload" data-testid="action-upload-btn" @click="openUpload">
          上传我的动作
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="fm-card">
      <div class="list-head">
        <span class="fm-card-title">我的提交记录</span>
        <span class="total-hint" data-testid="action-total">共 {{ total }} 条</span>
      </div>

      <el-empty
        v-if="!loading && rows.length === 0"
        description="还没有提交过动作考核"
        data-testid="action-empty"
      />

      <div v-else class="record-list">
        <div
          v-for="row in rows"
          :key="row.id"
          class="record"
          data-testid="action-card"
          @click="openDetail(row)"
        >
          <div class="record-main">
            <div class="record-name">
              {{ row.cataName || '动作考核' }}
              <el-tag
                :type="statusTagType(row)"
                size="small"
                effect="light"
                data-testid="action-status"
              >
                {{ row.statusDesc || (row.status === ACTION_STATUS.REVIEWED ? '已评分' : '待评分') }}
              </el-tag>
            </div>
            <div class="record-meta">
              <span data-testid="action-submit-time">
                提交于 {{ formatDateTime(row.submitTime) }}
              </span>
              <span v-if="row.remark" class="remark">「{{ row.remark }}」</span>
            </div>
          </div>
          <div class="record-score">
            <template v-if="row.status === ACTION_STATUS.REVIEWED && row.score !== null">
              <span class="score" data-testid="action-score">{{ row.score }}</span>
              <span class="score-unit">分</span>
            </template>
            <span v-else class="pending">待教练评分</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 上传 -->
    <el-dialog v-model="dialogVisible" title="上传动作视频" width="520px" data-testid="action-upload-dialog">
      <div class="upload-body">
        <input
          ref="fileInput"
          class="file-input"
          type="file"
          accept="video/*"
          data-testid="action-file-input"
          @change="handleFileChange"
        />
        <el-button :disabled="uploading" @click="fileInput?.click()" data-testid="action-pick-file">
          选择视频文件
        </el-button>
        <span v-if="pickedFile" class="picked" data-testid="action-picked">
          {{ pickedFile.name }}（{{ (pickedFile.size / 1024 / 1024).toFixed(1) }} MB）
        </span>
        <span v-else class="picked muted">支持 mp4 / mov / webm</span>

        <el-progress
          v-if="uploading"
          class="progress"
          :percentage="uploadPercent"
          :stroke-width="10"
          data-testid="action-progress"
        />
        <div v-if="stage" class="stage" data-testid="action-stage">{{ stage }}</div>

        <el-input
          v-model="remark"
          class="remark-input"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="想说点什么（选填）：比如这是第几周、自己觉得哪里不稳、想重点看哪个部位"
          data-testid="action-remark"
        />
      </div>

      <template #footer>
        <el-button :disabled="uploading" @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="uploading"
          data-testid="action-submit"
          @click="doUpload"
        >
          上传并提交
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="考核详情" width="620px" data-testid="action-detail-dialog">
      <template v-if="detail">
        <video
          v-if="videoSrc"
          class="detail-video"
          :src="videoSrc"
          controls
          preload="metadata"
          data-testid="action-video"
        />
        <el-empty v-else description="视频地址暂时取不到（重新打开试试）" :image-size="60" />

        <el-descriptions class="detail-desc" :column="1" border size="small">
          <el-descriptions-item label="提交时间">
            {{ formatDateTime(detail.submitTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ detail.statusDesc || (detail.status === ACTION_STATUS.REVIEWED ? '已评分' : '待评分') }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.remark" label="我的自述">
            {{ detail.remark }}
          </el-descriptions-item>
          <el-descriptions-item label="教练评分">
            <template v-if="detail.score !== null">
              <span class="score">{{ detail.score }}</span> 分
            </template>
            <span v-else class="pending">还没评分</span>
          </el-descriptions-item>
          <el-descriptions-item label="教练评语">
            <span data-testid="action-comment">{{ detail.comment || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.reviewTime" label="评分时间">
            {{ formatDateTime(detail.reviewTime) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.action-page {
  max-width: var(--fm-page-max-width);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.head-left {
  flex: 1;
  min-width: 260px;
}

.head-right {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.fm-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.sub {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.total-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.record-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.record {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--fm-radius);
  cursor: pointer;
  transition: border-color 0.2s;
}

.record:hover {
  border-color: var(--el-color-primary);
}

.record-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.record-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.remark {
  color: var(--el-text-color-regular);
}

.record-score {
  flex-shrink: 0;
}

.score {
  font-size: 22px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.score-unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 2px;
}

.pending {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.upload-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: flex-start;
}

.file-input {
  display: none;
}

.picked {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.picked.muted {
  color: var(--el-text-color-secondary);
}

.progress {
  width: 100%;
}

.stage {
  font-size: 13px;
  color: var(--el-color-primary);
}

.remark-input {
  width: 100%;
}

.detail-video {
  width: 100%;
  max-height: 320px;
  border-radius: var(--fm-radius);
  background: #000;
}

.detail-desc {
  margin-top: 12px;
}
</style>
