<script setup lang="ts">
import { Refresh, VideoCamera } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { pagePendingActions, reviewAction } from '@/api/action'
import { getPreviewUrl } from '@/api/media'
import type { ActionAssessment } from '@/types/admin'
import { formatDateTime, pageTotal } from '@/utils/format'

/**
 * 动作考核（管理端 / 教练评分）—— M7-10
 *
 * ★ 页面只做一件事：**待评分列表 + 评分**。
 *
 * ★★ 一个刻意的交互决定：把「看视频」和「打分」放在**同一个对话框**里。
 *   分成"先点播放看视频、关掉、再点评分"是很容易写出来的结构，
 *   但那要求教练**记住**刚才看到的动作问题 —— 而评语恰恰是要写细节的。
 *   所以对话框左侧视频、右侧打分与评语，边看边写。
 *
 * ★ `videoUrl` 是**带签名的临时地址**：列表可能已经拉出来几分钟了，
 *   所以**打开评分框时重新拉一次列表**（或直接复用刚拉的那次），
 *   地址过期时 `<video>` 会静默播不动，看起来像"卡住了"。
 */
const loading = ref(false)
const rows = ref<ActionAssessment[]>([])
const total = ref(0)

const query = reactive({ pageNo: 1, pageSize: 10 })

// ---------- 评分 ----------
const reviewVisible = ref(false)
const submitting = ref(false)
const current = ref<ActionAssessment | null>(null)
/**
 * ★ 真正喂给 `<video>` 的地址：拿 `mediaId` **现取**一次管理端预览地址。
 *   `videoUrl` 一直是 null（签名地址不存库），见类型里的说明。
 */
const videoSrc = ref('')
const form = reactive({
  id: '',
  /** 默认 60（及格线）——让教练在基准上加减，比从 0 开始敲更省事 */
  score: 60,
  comment: '',
})

async function load() {
  loading.value = true
  try {
    const page = await pagePendingActions({ pageNo: query.pageNo, pageSize: query.pageSize })
    rows.value = page.list ?? []
    total.value = pageTotal(page)
  } catch {
    /* request.ts 已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function openReview(row: ActionAssessment) {
  current.value = row
  form.id = row.id
  form.score = 60
  form.comment = ''
  videoSrc.value = ''
  reviewVisible.value = true
  // ★ 先取播放地址：教练要**边看边打分**，视频没出来就别让人干等
  const mid = row.mediaId
  if (!mid) return
  try {
    const preview = await getPreviewUrl(String(mid))
    videoSrc.value = preview?.url ?? ''
  } catch {
    videoSrc.value = ''
  }
}

async function doReview() {
  if (!form.id) return
  submitting.value = true
  try {
    await reviewAction({
      id: form.id,
      score: form.score,
      comment: form.comment.trim() || undefined,
    })
    ElMessage.success(`已评分 ${form.score} 分`)
    reviewVisible.value = false
    // ★ 评完**重新拉列表**，而不是本地删掉这一行：
    //   后端"并发下只有一次生效"，本地猜测会与真实状态不一致。
    await load()
  } catch {
    /* request.ts 已提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="action-admin">
    <el-card shadow="never">
      <div class="head">
        <div>
          <div class="fm-card-title">
            <el-icon><VideoCamera /></el-icon>
            动作考核 · 待评分
          </div>
          <div class="sub">
            学员上传的动作视频。★ 评分前请先播放看完，再给分与评语 ——
            学员端会原样看到你的评语。
          </div>
        </div>
        <div class="head-right">
          <span class="total-hint" data-testid="action-pending-total">待评分 {{ total }} 条</span>
          <el-button :icon="Refresh" :loading="loading" data-testid="action-refresh" @click="load">
            刷新
          </el-button>
        </div>
      </div>

      <el-empty
        v-if="!loading && rows.length === 0"
        description="没有待评分的动作考核"
        data-testid="action-pending-empty"
      />

      <el-table v-else :data="rows" border stripe data-testid="action-table">
        <el-table-column label="学员" prop="userName" width="120" />
        <el-table-column label="动作" min-width="150">
          <template #default="{ row }">
            {{ row.cataName || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="自述" min-width="220">
          <template #default="{ row }">
            <span class="remark">{{ row.remark || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.submitTime) }}</template>
        </el-table-column>
        <!--
          ★ 注意这里**不要**给 slot 标注类型：`#default="{ row }: { row: ActionAssessment }"`
          是**反的** —— slot props 的类型由 `el-table` 侧定义
          （`{ row: DefaultRow; column: TableColumnCtx<DefaultRow>; $index: number }`），
          标注得更窄会变成"不兼容"。正确做法是**在调用处断言**（见下面的 `row as ActionAssessment`）：
          断言只发生在"模板 → 函数"这一个边界上，函数内部仍是强类型。
          （M7-9 已经踩过一次，M7-10 写这个页面时又踩了一次 —— 所以写在这儿。）
        -->
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              data-testid="action-review-btn"
              @click="openReview(row as ActionAssessment)"
            >
              查看并评分
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > query.pageSize" class="pager">
        <el-pagination
          v-model:current-page="query.pageNo"
          :page-size="query.pageSize"
          :total="total"
          layout="prev, pager, next"
          data-testid="action-pager"
          @current-change="load"
        />
      </div>
    </el-card>

    <!-- 评分：视频与打分区放在一起（见文件头说明） -->
    <el-dialog
      v-model="reviewVisible"
      title="动作考核评分"
      width="860px"
      data-testid="action-review-dialog"
      @closed="current = null"
    >
      <div class="review-body">
        <div class="review-left">
          <video
            v-if="videoSrc"
            class="review-video"
            :src="videoSrc"
            controls
            preload="metadata"
            data-testid="action-review-video"
          />
          <el-empty
            v-else
            description="视频地址取不到（签名可能已过期，刷新列表再试）"
            :image-size="60"
          />
        </div>

        <div class="review-right">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="学员">
              {{ current?.userName || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="动作">
              {{ current?.cataName || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="提交时间">
              {{ formatDateTime(current?.submitTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="学员自述">
              {{ current?.remark || '—' }}
            </el-descriptions-item>
          </el-descriptions>

          <div class="score-row">
            <span class="label">评分</span>
            <el-input-number
              v-model="form.score"
              :min="0"
              :max="100"
              :step="5"
              :controls="false"
              style="width: 110px"
              data-testid="action-score-input"
            />
            <span class="label-hint">0 ~ 100 分</span>
          </div>

          <el-input
            v-model="form.comment"
            type="textarea"
            :rows="4"
            maxlength="200"
            show-word-limit
            placeholder="评语（选填）：指出具体问题与改进方向，学员端会原样看到"
            data-testid="action-comment-input"
          />
        </div>
      </div>

      <template #footer>
        <el-button :disabled="submitting" @click="reviewVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="action-submit-review"
          @click="doReview"
        >
          提交评分
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.fm-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  font-weight: 600;
}

.sub {
  margin-top: 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.total-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.remark {
  color: var(--el-text-color-regular);
}

.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.review-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.review-left {
  flex: 1 1 420px;
  min-width: 320px;
}

.review-video {
  width: 100%;
  max-height: 380px;
  border-radius: 8px;
  background: #000;
}

.review-right {
  flex: 1 1 300px;
  min-width: 260px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.score-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.score-row .label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.label-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
