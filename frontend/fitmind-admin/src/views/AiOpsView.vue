<script setup lang="ts">
import { Delete, Refresh, RefreshRight, Setting, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import {
  getKnowledgeStatus,
  getLimitView,
  getPrompts,
  getProvider,
  overrideLimit,
  rebuildKnowledge,
  resetLimitCounter,
  resetLimitOverride,
  setKnowledgeFailMode,
} from '@/api/ai'
import type { KnowledgeStatus, LimitView } from '@/types/admin'
import { formatThousands } from '@/utils/format'

/**
 * AI 运营
 *
 * ★★ 本页有一条**必须谨慎对待**的能力：**故障注入**（`fail-mode`）。
 *   打开之后，**所有用户的对话**都会走"检索失败 → 无依据回答"的降级分支。
 *
 *   为什么要做这个开关：降级路径不演练，第一次演练就是线上。
 *   为什么要点名：它能把演示环境变成故障环境，所以本页做了三层防护：
 *     ① 二次确认（且文案说明影响范围）
 *     ② 打开后**页面顶部常驻红色告警条**（不依赖"用户记得关"）
 *     ③ 独立的"恢复正常"按钮（不是同一个开关再点一次——误点概率更低）
 *
 * ★ 维度一致性检查（`mappedDimensions` vs `configuredDimensions`）是另一处要点：
 *   它俩不一致时**检索照样能跑**，只是召回质量会莫名变差（向量维度对不上时
 *   后端通常按前缀匹配或直接算错），这是最难查的一类问题。
 *   所以本页把它做成一个**醒目的状态徽标**而不是一行小字。
 */
const loading = ref(false)
const knowledge = ref<KnowledgeStatus | null>(null)
const rebuilding = ref(false)
const failModeSwitching = ref(false)
const provider = ref('')
const prompts = ref<Record<string, string>>({})

const limit = ref<LimitView | null>(null)
const limitLoading = ref(false)
const limitForm = reactive({ userId: '1', dailyChats: 50, dailyTokens: 200000, maxConcurrent: 2 })

/** 维度一致性 */
const dimsConsistent = computed(() => {
  const k = knowledge.value
  if (!k) return true
  return Number(k.mappedDimensions) === Number(k.configuredDimensions)
})

/**
 * 故障注入的**当前状态**
 *
 * ★★ 这里有一个真实的缺口（已登记为 `#A10`）：**后端没有"查询当前开关状态"的接口**。
 *   `GET /ais/admin/knowledge/status` 返回索引/向量数等信息，**不含 `simulateFailure`**。
 *   也就是说：页面刷新之后，前端**无从得知**检索此刻是不是被强制失败着。
 *
 *   所以这里用三态而不是布尔：
 *     `null`  = 未知（刚进页面，后端查不到）
 *     `true`  = 本次会话开启过
 *     `false` = 本次会话关闭过
 *   并且**"恢复正常"按钮在未知状态下也一直可点** ——
 *   对"关闭危险开关"这种操作，不该要求先查到状态才能关
 *   （否则一旦状态丢失，唯一能造成全局影响的能力反而关不掉）。
 */
const failModeLocal = ref<boolean | null>(null)

/**
 * 语料明细（后端 `corpusInfo()` 会把它们展开进 status 的顶层）
 *
 * ⚠️ 这份清单是**照实测返回抄的**，不是我猜的：
 *   第一版我按"想当然的名字"写了 docsDir / docCount / avgChunkChars / hybridEnabled，
 *   结果**一个都不存在** → 那一块永远渲染成空，而且不会报错。
 *   实测返回的键是下面这 8 个（`GET /ais/admin/knowledge/status`）。
 */
const corpusKeys = [
  'embeddingModel',
  'dimensions',
  'chunkSize',
  'chunkOverlap',
  'topK',
  'hybrid',
  'similarityThreshold',
  'corpusLocation',
]

function corpusEntries(): [string, unknown][] {
  const k = knowledge.value
  if (!k) return []
  return corpusKeys
    .filter((key) => k[key] !== undefined)
    .map((key) => [key, k[key]] as [string, unknown])
}

async function load() {
  loading.value = true
  try {
    const [k, p, pr] = await Promise.all([getKnowledgeStatus(), getProvider(), getPrompts()])
    knowledge.value = k
    provider.value = typeof p === 'string' ? p : String(p ?? '')
    prompts.value = pr ?? {}
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

async function handleRebuild() {
  rebuilding.value = true
  try {
    await ElMessageBox.confirm(
      '重建会：删除现有索引 → 重新分块语料 → 重新向量化写入。\n\n' +
        '· 期间检索不可用（对话会走降级分支）\n' +
        '· 接了真实 embedding 服务时会消耗额度\n' +
        '· 本地小语料通常几秒到几十秒',
      '重建知识库索引',
      { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' },
    )
    const r = await rebuildKnowledge()
    ElMessage.success(
      `重建完成：${r?.corpusDocs ?? '?'} 篇 / ${r?.chunkCount ?? '?'} 块 / ${r?.vectorCount ?? '?'} 向量，耗时 ${r?.costMs ?? '?'}ms`,
    )
    await load()
  } catch {
    /* 取消 / 已提示 */
  } finally {
    rebuilding.value = false
  }
}

/** 打开故障注入（高影响操作 → 二次确认） */
async function enableFailMode() {
  failModeSwitching.value = true
  try {
    await ElMessageBox.confirm(
      '⚠️ 这会让**所有用户**的 AI 对话都走"检索失败"的降级分支。\n\n' +
        '用途：验证降级路径（回答会明确说"暂时查不到依据"，但不会崩）。\n' +
        '影响范围：全部用户，直到你关掉它或重启 fm-aigc。',
      '开启故障注入',
      { type: 'error', confirmButtonText: '我确认，开启', cancelButtonText: '取消' },
    )
    const r = await setKnowledgeFailMode(true)
    ElMessage.warning('故障注入已开启 —— 检索已被强制失败')
    failModeLocal.value = r?.simulateFailure ?? true
  } catch {
    /* 取消 / 已提示 */
  } finally {
    failModeSwitching.value = false
  }
}

async function disableFailMode() {
  failModeSwitching.value = true
  try {
    const r = await setKnowledgeFailMode(false)
    ElMessage.success('已恢复正常检索')
    failModeLocal.value = r?.simulateFailure ?? false
  } catch {
    /* 已提示 */
  } finally {
    failModeSwitching.value = false
  }
}

async function loadLimit() {
  limitLoading.value = true
  try {
    limit.value = await getLimitView(limitForm.userId)
    if (limit.value?.enabled) {
      limitForm.dailyChats = limit.value.dailyChats
      limitForm.dailyTokens = limit.value.dailyTokens
      limitForm.maxConcurrent = limit.value.maxConcurrent
    }
  } catch {
    /* 已提示 */
  } finally {
    limitLoading.value = false
  }
}

async function applyOverride() {
  try {
    await overrideLimit(limitForm.dailyChats, limitForm.dailyTokens, limitForm.maxConcurrent)
    ElMessage.success('已临时调额（重启后恢复配置值）')
    await loadLimit()
  } catch {
    /* 已提示 */
  }
}

async function resetOverride() {
  try {
    await resetLimitOverride()
    ElMessage.success('已恢复为配置额度')
    await loadLimit()
  } catch {
    /* 已提示 */
  }
}

async function clearCounter() {
  try {
    await ElMessageBox.confirm(
      `清空用户 ${limitForm.userId} 的今日计数？\n（只影响配额统计，不影响已产生的对话记录）`,
      '清空今日计数',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' },
    )
    await resetLimitCounter(limitForm.userId)
    ElMessage.success('已清空')
    await loadLimit()
  } catch {
    /* 取消 / 已提示 */
  }
}

onMounted(async () => {
  await load()
  await loadLimit()
})
</script>

<template>
  <div v-loading="loading" class="fm-page ai-ops">
    <!-- ★★ 故障注入开启时的常驻告警（不依赖"用户记得关"） -->
    <el-alert
      v-if="failModeLocal === true"
      type="error"
      :closable="false"
      show-icon
      class="fail-alert"
      data-testid="aiops-failmode-alert"
    >
      <template #title>⚠️ 知识库故障注入已开启 —— 当前**所有用户**的检索都被强制失败</template>
      <div class="alert-body">
        对话不会崩（会走"无依据回答"的降级分支），但回答质量会明显下降。
        <el-button type="danger" size="small" :loading="failModeSwitching" data-testid="aiops-failmode-off" @click="disableFailMode">
          立即恢复正常
        </el-button>
      </div>
    </el-alert>

    <!-- 通道与知识库状态 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="fm-card-title">知识库状态</span>
          <div class="head-actions">
            <el-tag :type="provider === 'mock' ? 'info' : 'success'" size="small" effect="dark" data-testid="aiops-provider">
              模型通道：{{ provider === 'mock' ? 'Mock（确定性）' : provider || '未知' }}
            </el-tag>
            <el-button :icon="Refresh" size="small" @click="load">刷新</el-button>
            <el-button
              type="primary"
              size="small"
              :icon="RefreshRight"
              :loading="rebuilding"
              data-testid="aiops-rebuild"
              @click="handleRebuild"
            >
              重建索引
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="knowledge" class="status-grid">
        <div class="status-item">
          <span class="s-label">索引名</span>
          <span class="s-value mono" data-testid="aiops-index-name">{{ knowledge.indexName }}</span>
        </div>
        <div class="status-item">
          <span class="s-label">索引存在</span>
          <el-tag :type="knowledge.indexExists ? 'success' : 'danger'" size="small">
            {{ knowledge.indexExists ? '是' : '否' }}
          </el-tag>
        </div>
        <div class="status-item">
          <span class="s-label">健康</span>
          <el-tag :type="knowledge.healthy ? 'success' : 'danger'" size="small">
            {{ knowledge.healthy ? '正常' : '异常' }}
          </el-tag>
        </div>
        <div class="status-item">
          <span class="s-label">向量数</span>
          <span class="s-value" data-testid="aiops-vector-count">{{ formatThousands(knowledge.vectorCount) }}</span>
        </div>
        <div class="status-item">
          <span class="s-label">语料 / 分块</span>
          <span class="s-value">{{ knowledge.corpusDocs ?? '—' }} / {{ knowledge.chunkCount ?? '—' }}</span>
        </div>
        <!-- ★ 维度一致性：不一致时检索会"能跑但结果莫名变差"，必须醒目 -->
        <div class="status-item">
          <span class="s-label">向量维度</span>
          <span class="s-value" :class="{ 'dim-bad': !dimsConsistent }" data-testid="aiops-dims">
            mapping {{ knowledge.mappedDimensions }} / 配置 {{ knowledge.configuredDimensions }}
            <template v-if="!dimsConsistent"> ⚠️ 不一致</template>
          </span>
        </div>
      </div>

      <div v-if="knowledge?.message" class="status-msg">{{ knowledge.message }}</div>

      <el-descriptions v-if="corpusEntries().length" :column="3" border size="small" class="corpus-desc">
        <el-descriptions-item v-for="[k, v] in corpusEntries()" :key="k" :label="k">
          {{ v }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="hint-line">
        ★ 维度不一致时**检索照样能跑**，只是召回质量会莫名变差 ——
        这是最难查的一类问题，所以这里把它做成醒目标记而不是一行小字。
        「重建索引」会按当前配置重新向量化整份语料。
      </div>
    </el-card>

    <!-- 故障注入 -->
    <el-card shadow="never">
      <template #header>
        <div class="fm-card-title">
          <el-icon><Warning /></el-icon>
          故障注入（降级演练）
        </div>
      </template>

      <div class="fail-row">
        <div class="fail-desc">
          打开后，检索会被**强制失败**，对话走"无依据回答"的降级分支。
          <br />
          <span class="sub">
            存在的意义：**降级路径不演练，第一次演练就是线上**。
            但它是全局开关，所以打开后页面顶部会一直挂着红色告警。
            <br />
            ⚠️ 后端**没有查询该开关状态的接口**（`status` 不返回它），
            所以刷新页面后会显示"未知"——「恢复正常」按钮在这种情况下也保持可用。
          </span>
        </div>
        <div class="fail-actions">
          <el-tag v-if="failModeLocal === null" type="info" size="small" data-testid="aiops-failmode-unknown">
            当前状态：未知
          </el-tag>
          <el-tag v-else-if="failModeLocal" type="danger" size="small">当前状态：已开启</el-tag>
          <el-tag v-else type="success" size="small">当前状态：已关闭</el-tag>

          <el-button
            type="danger"
            :disabled="failModeLocal === true"
            :loading="failModeSwitching"
            data-testid="aiops-failmode-on"
            @click="enableFailMode"
          >
            开启故障注入
          </el-button>
          <!-- ★ 未知状态下也允许点"恢复正常"：关闭危险开关不该依赖"先查到状态" -->
          <el-button
            type="success"
            :disabled="failModeLocal === false"
            :loading="failModeSwitching"
            data-testid="aiops-failmode-off-2"
            @click="disableFailMode"
          >
            恢复正常
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 限流 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="fm-card-title">AI 配额与限流</span>
          <el-button :icon="Refresh" size="small" @click="loadLimit">刷新</el-button>
        </div>
      </template>

      <div class="limit-row">
        <el-input v-model="limitForm.userId" placeholder="用户 id" style="width: 130px" data-testid="aiops-limit-user" />
        <el-button type="primary" plain @click="loadLimit">查询</el-button>

        <template v-if="limit">
          <el-divider direction="vertical" />
          <span class="limit-stat">
            今日对话 <b data-testid="aiops-used-chats">{{ limit.usedChats }}</b> / {{ limit.dailyChats }}
          </span>
          <span class="limit-stat">
            token <b>{{ formatThousands(limit.usedTokens) }}</b> / {{ formatThousands(limit.dailyTokens) }}
          </span>
          <span class="limit-stat">
            并发 <b>{{ limit.running }}</b> / {{ limit.maxConcurrent }}
          </span>
          <el-tag :type="limit.enabled ? 'success' : 'info'" size="small">
            {{ limit.enabled ? '限流已启用' : '限流未启用' }}
          </el-tag>
        </template>
      </div>

      <div class="override-row">
        <span class="sub">临时调额（不持久化，重启恢复）：</span>
        <el-input-number v-model="limitForm.dailyChats" :min="1" :max="100000" :controls="false" style="width: 110px" data-testid="aiops-override-chats" />
        <span class="sub">对话 /</span>
        <el-input-number v-model="limitForm.dailyTokens" :min="1000" :max="100000000" :step="10000" :controls="false" style="width: 140px" />
        <span class="sub">token /</span>
        <el-input-number v-model="limitForm.maxConcurrent" :min="1" :max="100" :controls="false" style="width: 90px" />
        <span class="sub">并发</span>

        <el-button type="primary" size="small" :icon="Setting" data-testid="aiops-override-apply" @click="applyOverride">应用</el-button>
        <el-button size="small" @click="resetOverride">恢复默认</el-button>
        <el-button type="danger" size="small" plain :icon="Delete" data-testid="aiops-counter-clear" @click="clearCounter">
          清空今日计数
        </el-button>
      </div>
    </el-card>

    <!-- 提示词来源 -->
    <el-card shadow="never">
      <template #header>
        <div class="fm-card-title">提示词来源（{{ Object.keys(prompts).length }} 条）</div>
      </template>
      <el-table :data="Object.entries(prompts).map(([k, v]) => ({ key: k, len: v?.length ?? 0, text: v }))" size="small" data-testid="aiops-prompts">
        <el-table-column prop="key" label="key" width="140" />
        <el-table-column prop="len" label="字数" width="90" />
        <el-table-column label="内容（前 160 字）" min-width="320">
          <template #default="{ row }">
            <span class="prompt-preview">{{ (row.text ?? '').slice(0, 160) }}{{ (row.text ?? '').length > 160 ? '…' : '' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint-line">
        ★ 提示词是**三级来源**（Nacos 配置 → 本地文件 → 代码兜底常量）。
        这里显示的是"最终生效的那一份"，所以字数可以用来判断"到底读到了哪一级"。
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.ai-ops {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.fail-alert {
  align-items: flex-start;
}

.alert-body {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
  line-height: 1.7;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px 18px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.s-label {
  color: var(--el-text-color-secondary);
  flex: 0 0 76px;
}

.s-value {
  font-weight: 600;
}

.s-value.mono {
  font-family: ui-monospace, Consolas, monospace;
  font-weight: 500;
}

.dim-bad {
  color: var(--el-color-danger);
}

.status-msg {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.corpus-desc {
  margin-top: 12px;
}

.hint-line {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.8;
}

.fail-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.fail-desc {
  font-size: 13px;
  line-height: 1.8;
}

.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.fail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.limit-row,
.override-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.override-row {
  margin-top: 14px;
}

.limit-stat {
  font-size: 13px;
}

.limit-stat b {
  color: var(--el-color-primary);
}

.prompt-preview {
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.7;
  word-break: break-all;
}
</style>
