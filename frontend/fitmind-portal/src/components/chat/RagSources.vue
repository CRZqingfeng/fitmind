<script setup lang="ts">
import { computed, ref } from 'vue'

import type { RagInfo } from '@/types/ai'
import { ragModeLabel } from '@/types/ai'

/**
 * 检索引用来源
 *
 * 两种用法：
 *  · `inline` —— 挂在回答气泡下面，默认收起，只露"引用 N 篇 + 文档 id"；
 *  · `panel`  —— 调试面板里的完整视图（含检索统计与命中片段）。
 *
 * ============================================================
 * ★★ 关于"相似度"这个数字：它**语义不统一**，展示时必须说明
 * ============================================================
 * 混合检索是"向量 + 关键词"两路召回后用 RRF 按**排名**融合的，两路分数**不可比**：
 *   · 向量那一路给的是**余弦相似度**，范围 [0,1]
 *   · 关键词那一路给的是 **BM25 原始分**，**没有上界**（实测出现过 6.96）
 * 后端在融合时把 RRF 分单独放在 `metadata.rrf`，`score` 保留的是原始分 ——
 * 于是列表里会同时出现 0.68 和 6.96 这种量级不同的数字。
 *
 * 前端能做的只有一件事：**别把它统一叫"相似度"**。所以这里的列名是"分数"，
 * 并在提示里写清楚两种口径；超过 1 的值额外标一下（它必然来自关键词那一路）。
 * ★ 把它叫"相似度 6.96"才是真的错 —— 那会让看的人以为是排序出了问题。
 */
const props = defineProps<{
  rag: RagInfo
  mode?: 'inline' | 'panel'
}>()

const expanded = ref(false)

const isPanel = computed(() => props.mode === 'panel')
const sources = computed(() => props.rag.sources || [])
const open = computed(() => isPanel.value || expanded.value)

const modeText = computed(() => ragModeLabel(props.rag.mode))

/** 有没有"不可能是余弦相似度"的值（>1 必然是 BM25） */
const hasUnboundedScore = computed(() => sources.value.some((s) => (s.score ?? 0) > 1))

function scoreText(score: number | null | undefined): string {
  if (score === null || score === undefined) return '—'
  // 余弦相似度只需要 3 位小数；BM25 可能是个位数以上，保留 2 位即可
  return score > 1 ? score.toFixed(2) : score.toFixed(3)
}
</script>

<template>
  <div class="rag-box" :class="{ 'is-panel': isPanel }" data-testid="rag-sources">
    <div class="rag-head">
      <span class="rag-title">
        引用来源
        <span class="rag-count" data-testid="rag-count">{{ sources.length }}</span>
        篇
      </span>

      <el-tag size="small" :type="rag.mode === 'rag' ? 'success' : 'warning'" effect="plain">
        {{ modeText }}
      </el-tag>
      <el-tag v-if="rag.hybrid" size="small" type="info" effect="plain">混合检索</el-tag>

      <!-- inline 模式默认收起：引用是"需要时可查"的信息，不该占满回答区 -->
      <el-button v-if="!isPanel && sources.length" link size="small" @click="expanded = !expanded">
        {{ expanded ? '收起' : '展开' }}
      </el-button>
    </div>

    <!-- 检索统计（面板里才展示完整口径） -->
    <div v-if="isPanel" class="rag-stats">
      <span>候选 {{ rag.candidateCount }} 条</span>
      <span>注入 {{ rag.injectedCount }} 条</span>
      <span>依据 {{ rag.contextChars }} 字</span>
      <span>检索耗时 {{ rag.searchMs }} ms</span>
    </div>

    <div v-if="rag.note" class="rag-note">{{ rag.note }}</div>

    <ul v-if="open && sources.length" class="source-list">
      <li
        v-for="(src, i) in sources"
        :key="`${src.docId}-${i}`"
        class="source-item"
        data-testid="rag-source-item"
      >
        <div class="source-line">
          <code class="doc-id" data-testid="rag-doc-id">{{ src.docId }}</code>
          <span class="source-title">{{ src.title }}</span>
          <span class="source-score" :title="hasUnboundedScore ? '混合检索下两类分数不可比：向量=余弦相似度[0,1]，关键词=BM25（无上界）' : ''">
            <span v-if="(src.score ?? 0) > 1" class="score-warn" title="这个值大于 1，必然来自关键词（BM25）那一路">BM25 </span>
            {{ scoreText(src.score) }}
          </span>
        </div>
        <div v-if="src.section" class="source-section">节：{{ src.section }}</div>
        <div v-if="src.preview" class="source-preview">{{ src.preview }}</div>
      </li>
    </ul>

    <div v-else-if="!sources.length" class="rag-empty">
      本轮没有使用知识库依据（{{ modeText }}）
    </div>

    <!--
      ★ 这条说明在**真的出现 >1 的分数**时才显示：
      平时不打扰，一旦这份数据里混了两种口径，就明确告诉看的人"这不是排序错了"。
    -->
    <div v-if="open && hasUnboundedScore" class="rag-footnote">
      注：混合检索融合了"向量"与"关键词"两路，两者的分数口径不同（余弦相似度 vs BM25），
      因此列表里的数字**只能在各自那一路内部比较**。标了 <b>BM25</b> 的来自关键词召回。
    </div>
  </div>
</template>

<style scoped>
.rag-box {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
}

.rag-box.is-panel {
  background: transparent;
  border: none;
  padding: 0;
  margin-top: 0;
}

.rag-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rag-title {
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.rag-count {
  color: var(--el-color-primary);
}

.rag-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rag-note {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-color-warning);
}

.source-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}

.source-item {
  padding: 8px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.source-item:first-child {
  border-top: none;
}

.source-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}

.doc-id {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  color: var(--el-color-primary);
}

.source-title {
  font-weight: 600;
}

.source-score {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: help;
}

.score-warn {
  color: var(--el-color-warning);
}

.source-section {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.source-preview {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
}

.rag-empty {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rag-footnote {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 11px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
</style>
