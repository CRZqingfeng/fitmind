<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { Close, Cpu, DataAnalysis, Histogram, Refresh } from '@element-plus/icons-vue'

import { getPrompts } from '@/api/ai'
import RagSources from '@/components/chat/RagSources.vue'
import type { MemoryView, QuotaView, RagInfo, StopPayload } from '@/types/ai'
import { chatTypeLabel, ragModeLabel } from '@/types/ai'

/**
 * AI 教练调试面板
 *
 * ============================================================
 * ★★ 这个面板才是本项目的差异化所在
 * ============================================================
 * 别人的 AI Demo：一个输入框 + 一个回答框，回答对不对全靠感觉。
 * 这个面板把**整条链路摊开**给看的人：
 *
 *   这轮由谁回答（Agent）→ 花了多少钱（token / 耗时 / 首字延迟）
 *   → 依据是什么（知识库引用：哪一篇、哪一节、什么分数）
 *   → 做了什么（工具链：调了什么、参数是什么、多久、有没有被拦）
 *   → 记不记得上文（上下文窗口：几条历史、有没有压缩、摘要多少字）
 *   → 有没有被限流 / 降级
 *
 * 这些东西**后端本来就在算**，只是多数前端不展示 ——
 * 不展示，用户就只能"看着回答猜它为什么这么答"。
 *
 * ============================================================
 * ★ 两处刻意的"如实呈现"
 * ============================================================
 * ① 引用分数不叫"相似度" —— 混合检索下有两套口径（余弦 / BM25），
 *    详见 `RagSources.vue` 的说明。
 * ② 上下文条数、向量条数这类字段后端是**字符串**（Long→String 全局规则），
 *    这里直接展示不参与算术，所以不转数字；一旦要比较大小就必须先 Number()。
 */
const props = defineProps<{
  metrics: StopPayload | null
  memory: MemoryView | null
  quota: QuotaView | null
  provider: string
  /**
   * 本轮的引用明细。
   *
   * ★ 它**不在 `StopPayload` 里** —— 引用与卡片走的是另一条路（SSE 的 PARAM 事件），
   *   所以必须由页面单独传进来。如果以为"指标里有"就不用传，这块会永远显示不出来。
   */
  rag: RagInfo | null
}>()

const emit = defineEmits<{ close: [] }>()

const prompts = ref<Record<string, string>>({})

onMounted(async () => {
  try {
    prompts.value = (await getPrompts()) ?? {}
  } catch {
    prompts.value = {}
  }
})

const toolInvocations = computed(() => props.metrics?.toolCalls?.invocations ?? [])

const hasMetrics = computed(() => !!props.metrics)

/** 首字延迟占“总耗时”的比例（可视化"思考了很久才开始说"） */
const firstTokenRatio = computed(() => {
  const first = props.metrics?.firstTokenMs
  const total = props.metrics?.totalMs
  if (!first || !total) return 0
  return Math.min(100, Math.round((first / total) * 100))
})

const totalTokens = computed(() => {
  const p = props.metrics?.promptTokens ?? 0
  const c = props.metrics?.completionTokens ?? 0
  return p + c
})

const promptKeys = computed(() => Object.keys(prompts.value))

/** 上下文占用率（条数 / 上限）—— 接近满时会触发压缩，值得预警 */
const memoryUsage = computed(() => {
  const used = Number(props.memory?.messageCount ?? 0)
  const max = props.memory?.maxMessages ?? 0
  if (!max) return 0
  return Math.min(100, Math.round((used / max) * 100))
})

function refreshPrompts() {
  void getPrompts()
    .then((p) => {
      prompts.value = p ?? {}
    })
    .catch(() => undefined)
}
</script>

<template>
  <aside class="coach-panel" data-testid="ai-panel">
    <header class="panel-head">
      <el-icon><DataAnalysis /></el-icon>
      <span class="panel-title">调试面板</span>
      <el-button link :icon="Close" data-testid="ai-panel-close" @click="emit('close')" />
    </header>

    <div class="panel-body">
      <!-- ==================== 1. 本轮指标 ==================== -->
      <section class="block">
        <div class="block-title">本轮指标</div>

        <div v-if="!hasMetrics" class="empty">还没有发过言。发一条消息，这里会显示这一轮的完整指标。</div>

        <template v-else>
          <div class="kv">
            <span>模型通道</span>
            <b data-testid="ai-metrics-provider">
              {{ provider === 'mock' ? 'mock（确定性）' : provider || '—' }}
            </b>
          </div>
          <div class="kv">
            <span>编排策略</span>
            <b data-testid="ai-metrics-chattype">{{ chatTypeLabel(metrics?.chatType) }}</b>
          </div>
          <div class="kv">
            <span>执行 Agent</span>
            <b data-testid="ai-metrics-agent">{{ metrics?.agentType || '—' }}</b>
          </div>
          <div class="kv">
            <span>首字延迟</span>
            <b data-testid="ai-metrics-firsttoken">
              {{ metrics?.firstTokenMs != null ? `${metrics.firstTokenMs} ms` : '—' }}
            </b>
          </div>
          <div class="kv">
            <span>总耗时</span>
            <b data-testid="ai-metrics-total">
              {{ metrics?.totalMs != null ? `${metrics.totalMs} ms` : '—' }}
            </b>
          </div>
          <div class="kv">
            <span>输入 token</span>
            <b data-testid="ai-metrics-prompt-tokens">{{ metrics?.promptTokens ?? '—' }}</b>
          </div>
          <div class="kv">
            <span>输出 token</span>
            <b data-testid="ai-metrics-completion-tokens">{{ metrics?.completionTokens ?? '—' }}</b>
          </div>
          <div class="kv">
            <span>合计 token</span>
            <b data-testid="ai-metrics-total-tokens">{{ totalTokens || '—' }}</b>
          </div>
          <div v-if="metrics?.routePromptTokens" class="kv-wrap">
            <div class="kv">
              <span>其中路由消耗</span>
              <b>{{ metrics.routePromptTokens }} token</b>
            </div>
            <!-- ★ 解释文案单独占一行（不用 flex 里的 `flex-basis:100%`）：
                 后者会让「值」和「说明」在同一行里折行，挤成一团看不出是什么。
                 这一项只在 ROUTE 策略下非 0 —— 它正是"路由更贵"的量化证据。 -->
            <div class="kv-hint">ROUTE 策略为了"先判断该谁回答"额外花的一次调用</div>
          </div>

          <!-- 首字延迟占比：能直观看出"思考慢"还是"输出慢" -->
          <div v-if="metrics?.firstTokenMs && metrics?.totalMs" class="ratio">
            <div class="ratio-bar">
              <div class="ratio-fill" :style="{ width: `${firstTokenRatio}%` }" />
            </div>
            <div class="ratio-label">
              前 {{ firstTokenRatio }}% 的时间在"想"，之后才开始"说"
            </div>
          </div>

          <div class="flags">
            <el-tag v-if="metrics?.stopped" size="small" type="warning" effect="plain">被停止</el-tag>
            <el-tag v-if="metrics?.degraded" size="small" type="danger" effect="plain">
              降级回答（模型不可用时的兜底）
            </el-tag>
            <el-tag v-if="metrics?.compressed" size="small" type="info" effect="plain">
              触发了上下文压缩（{{ metrics.compressedCount }} 条 → 摘要，耗时 {{ metrics.compressMs }}ms）
            </el-tag>
            <el-tag v-if="metrics?.limited" size="small" type="danger" effect="plain">
              被限流：{{ metrics.limitReason || '超出配额' }}
            </el-tag>
          </div>
        </template>
      </section>

      <!-- ==================== 2. 工具调用链 ==================== -->
      <section class="block">
        <div class="block-title">
          <el-icon><Cpu /></el-icon>
          工具调用
          <span class="count">{{ metrics?.toolCallCount ?? 0 }} 次</span>
        </div>

        <div v-if="!toolInvocations.length" class="empty">
          本轮没有调用工具（工具只在需要真实数据时才调 —— 比如查档案、搜课、下单）
        </div>

        <ul v-else class="tool-list">
          <li
            v-for="(inv, i) in toolInvocations"
            :key="i"
            class="tool-item"
            data-testid="ai-tool-invocation"
          >
            <div class="tool-line">
              <span class="tool-index">{{ i + 1 }}</span>
              <b class="tool-name" data-testid="ai-tool-name">{{ inv.name }}</b>
              <span class="tool-cost">{{ inv.costMs }} ms</span>
            </div>
            <div class="tool-args">
              <span class="label">参数</span>
              <code>{{ inv.args }}</code>
            </div>
            <div class="tool-result">
              <span class="label">结果</span>
              <span>{{ inv.result }}</span>
            </div>
            <el-tag v-if="inv.blocked" size="small" type="warning" effect="plain">
              超出调用次数上限被拦下
            </el-tag>
            <el-tag v-if="inv.error" size="small" type="danger" effect="plain">{{ inv.error }}</el-tag>
          </li>
        </ul>
      </section>

      <!-- ==================== 3. 检索引用 ==================== -->
      <section class="block">
        <div class="block-title">
          <el-icon><Histogram /></el-icon>
          知识库依据
          <span class="count">
            {{ metrics?.ragHits ?? 0 }} 条
            <template v-if="metrics?.ragMode">· {{ ragModeLabel(metrics.ragMode) }}</template>
          </span>
        </div>

        <div v-if="!metrics?.ragHits" class="empty">
          本轮没有使用知识库
          <template v-if="metrics?.ragMode === 'degraded'">（检索服务不可用，已降级为无依据回答）</template>
          <template v-else-if="metrics?.ragMode === 'skipped'">（这轮编排策略不需要检索）</template>
        </div>

        <template v-else-if="metrics">
          <!-- ★ 完整视图由 RagSources 提供（含分数口径说明） -->
          <RagSources v-if="rag" :rag="rag" mode="panel" />
          <div v-else class="empty">
            本轮注入了 {{ metrics.ragHits }} 条依据，但明细不在这次响应里<br />
            <span class="hint">
              检索统计：候选 {{ metrics.ragCandidates ?? '—' }} 条 · 耗时
              {{ metrics.ragSearchMs ?? '—' }} ms
            </span>
          </div>
        </template>
      </section>

      <!-- ==================== 4. 上下文窗口 ==================== -->
      <section class="block">
        <div class="block-title">上下文窗口</div>

        <div v-if="!memory" class="empty">还没有会话，或体检报告不可用</div>

        <template v-else>
          <div class="kv">
            <span>本轮带上历史</span>
            <b data-testid="ai-memory-history">{{ metrics?.memoryHistory ?? 0 }} 条</b>
          </div>
          <div class="kv">
            <span>窗口内消息数</span>
            <b data-testid="ai-memory-count">{{ memory.messageCount ?? '—' }} / {{ memory.maxMessages }}</b>
          </div>
          <div v-if="memoryUsage" class="ratio">
            <div class="ratio-bar">
              <div class="ratio-fill" :style="{ width: `${memoryUsage}%` }" />
            </div>
            <div class="ratio-label">
              占用 {{ memoryUsage }}%（接近上限时会把旧消息压成摘要）
            </div>
          </div>
          <div class="kv">
            <span>摘要字数</span>
            <b data-testid="ai-memory-summary">{{ memory.summaryChars ?? 0 }} 字</b>
          </div>
          <div class="kv">
            <span>保留时效</span>
            <b>{{ memory.ttlDays }} 天</b>
          </div>

          <div v-if="memory.summaryPreview" class="preview-block">
            <div class="preview-title">摘要</div>
            <div class="preview-text">{{ memory.summaryPreview }}</div>
          </div>

          <div v-if="memory.recentPreview?.length" class="preview-block">
            <div class="preview-title">窗口内最近几条</div>
            <div
              v-for="(p, i) in memory.recentPreview"
              :key="i"
              class="preview-row"
            >
              <el-tag size="small" :type="p.role === 'user' ? 'info' : 'success'" effect="plain">
                {{ p.role === 'user' ? '我' : 'AI' }}
              </el-tag>
              <span class="preview-text">{{ p.preview }}</span>
            </div>
          </div>

          <p class="block-note">
            这组数据用来回答"它怎么突然不记得刚才说的了"：条数到上限会触发压缩，
            摘要会替代原始消息进入 prompt —— 细节因此丢失，但上下文不爆。
          </p>
        </template>
      </section>

      <!-- ==================== 5. 配额与并发 ==================== -->
      <section class="block">
        <div class="block-title">配额与并发</div>

        <div v-if="!quota" class="empty">配额信息不可用</div>

        <template v-else>
          <div class="kv">
            <span>今日对话</span>
            <b data-testid="ai-quota-used">{{ quota.usedChats }} / {{ quota.dailyChats }}</b>
          </div>
          <div class="kv">
            <span>剩余对话</span>
            <b data-testid="ai-quota-remaining">{{ quota.remainingChats }}</b>
          </div>
          <div class="kv">
            <span>今日 token</span>
            <b>{{ quota.usedTokens }} / {{ quota.dailyTokens }}</b>
          </div>
          <div class="kv">
            <span>当前并发</span>
            <b data-testid="ai-quota-concurrent">{{ quota.running }} / {{ quota.maxConcurrent }}</b>
          </div>
          <p class="block-note">
            两个维度分开管：**配额**管"一天总共能用多少"，**并发**管"同一时刻能跑几轮"。
            前者防刷，后者防把模型通道打满。
          </p>
        </template>
      </section>

      <!-- ==================== 6. 提示词来源 ==================== -->
      <section class="block">
        <div class="block-title">
          提示词来源
          <el-button link size="small" :icon="Refresh" @click="refreshPrompts" />
        </div>

        <div v-if="!promptKeys.length" class="empty">提示词清单不可用</div>

        <template v-else>
          <div v-for="key in promptKeys" :key="key" class="kv prompt-row">
            <span class="prompt-key">{{ key }}</span>
            <b class="prompt-src" data-testid="ai-prompt-source">{{ prompts[key] }}</b>
          </div>
          <p class="block-note">
            每一条都标了来源与字数。三级来源是「Nacos（可热更新）→ 本地文件 → 代码兜底」——
            ★ 关键点是**读不到不等于出错**：不会出现"提示词为空导致 AI 不受控"。
          </p>
        </template>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.coach-panel {
  width: 340px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--fm-radius);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-color-primary);
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.panel-head .el-button {
  margin-left: auto;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0 16px;
}

.block {
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.block:last-child {
  border-bottom: none;
}

.block-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 10px;
}

.block-title .count {
  margin-left: auto;
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.empty {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.kv {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kv b {
  color: var(--el-text-color-primary);
  font-weight: 600;
  text-align: right;
  word-break: break-all;
}

.kv-hint {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--el-text-color-placeholder);
  text-align: right;
}

/* 首字延迟 / 上下文占用率 */
.ratio {
  margin: 8px 0 4px;
}

.ratio-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--el-fill-color);
  overflow: hidden;
}

.ratio-fill {
  height: 100%;
  background: var(--el-color-primary);
  transition: width 0.3s ease;
}

.ratio-label {
  margin-top: 4px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.flags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

/* 工具链 */
.tool-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.tool-item {
  padding: 8px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.tool-item:first-child {
  border-top: none;
  padding-top: 0;
}

.tool-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
}

.tool-index {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  text-align: center;
  line-height: 16px;
  font-size: 10px;
}

.tool-name {
  font-family: ui-monospace, Consolas, monospace;
  color: var(--el-text-color-primary);
}

.tool-cost {
  margin-left: auto;
  color: var(--el-text-color-secondary);
}

.tool-args,
.tool-result {
  display: flex;
  gap: 6px;
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.tool-args .label,
.tool-result .label {
  flex-shrink: 0;
  color: var(--el-text-color-placeholder);
}

.tool-args code {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
  word-break: break-all;
}

/* 上下文预览 */
.preview-block {
  margin-top: 8px;
}

.preview-title {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-bottom: 4px;
}

.preview-row {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  margin-bottom: 4px;
}

.preview-text {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  word-break: break-word;
}

/* 提示词 */
.prompt-row {
  align-items: center;
}

.prompt-key {
  font-family: ui-monospace, Consolas, monospace;
  color: var(--el-text-color-primary);
}

.prompt-src {
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.block-note {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--el-text-color-placeholder);
}
</style>
