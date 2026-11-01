<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { Clock, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { deleteNote, pageNotes } from '@/api/social'
import type { Note } from '@/types/social'
import { formatClock, formatDateTime, pageTotal } from '@/utils/format'

/**
 * 笔记面板（M10-1 第 1 期）
 *
 * ============================================================
 * ★★ 这个面板的灵魂是"点时间戳跳回视频"
 * ============================================================
 * 笔记存了 `note_moment`（记下时的播放秒数）。列表里把它渲染成一个**可点的按钮**，
 * 点了就让播放器跳到那一秒 —— 这样"第 3 分 42 秒要这样发力"才真的有意义，
 * 否则时间点只是一串没用的数字。
 *
 * ★ 跳转动作**不由本组件执行**：本组件 `emit('seek', moment)`，
 *   由播放页去动 `<video>`。理由见 `NoteComposer.vue` 顶部注释
 *   （子组件去 querySelector('video') 会静默取错对象）。
 *
 * ============================================================
 * ★ 只看"我的笔记"（onlyMine: true）
 * ============================================================
 * 两个理由：
 *   1. 语义 —— 笔记是个人的训练记录，默认就该只看自己的；
 *   2. ★ 安全 —— 后端 `NotePageQuery` 只有 `onlyMine`，**没有"是否只返回公开笔记"的开关**。
 *      一旦传 `onlyMine: false`，别人的**私有笔记**也会被列出来。
 *      这不是本组件要顺手修的东西（属于后端接口设计），所以这里**不碰那个口子**。
 *      （"看别人的笔记"应该是"采集"路径，M10-1 §十 已登记为后续期次）
 */
const props = defineProps<{
  courseId: string
  /** 当前动作 id；切动作时本面板要跟着刷新 */
  sectionId?: string
  /** 父页每次成功记笔记后 +1 —— 用它触发刷新，比 ref 调子组件方法更松耦合 */
  refreshKey?: number
}>()

const emit = defineEmits<{ (e: 'seek', moment: number): void }>()

/** 范围：只看当前动作 / 看整门课 */
const scope = ref<'section' | 'course'>('section')
const loading = ref(false)
const notes = ref<Note[]>([])
const total = ref(0)

/**
 * 列表按时间点升序。
 *
 * ★ 为什么在前端排而不让后端排：接口没有排序参数，而**单动作的笔记量很小**
 *   （几十条以内）。为它加一个后端排序参数，收益不抵一次发版。
 *   ⚠️ 若将来笔记量上来了（比如整门课范围），要改成后端排序 + 分页 ——
 *      前端排序会把"当前页内有序"误当成"全局有序"。
 */
const sortedNotes = computed(() =>
  [...notes.value].sort((a, b) => (a.noteMoment ?? 0) - (b.noteMoment ?? 0)),
)

async function load() {
  if (!props.courseId) return
  loading.value = true
  try {
    const res = await pageNotes({
      pageNo: 1,
      pageSize: 50,
      courseId: props.courseId,
      onlyMine: true,
      // ★ "整门课"范围下**不能传 sectionId** —— 传了就等于筛成了单个动作
      ...(scope.value === 'section' && props.sectionId ? { sectionId: props.sectionId } : {}),
    })
    notes.value = res?.list ?? []
    total.value = pageTotal(res)
  } catch {
    notes.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 切动作 → 刷新（"整门课"范围下内容不变，但重拉一次代价很小，省掉一个分支判断）
watch(() => props.sectionId, load)
// 父页记完笔记 → 刷新
watch(() => props.refreshKey, load)
// 切换范围 → 刷新
watch(scope, load)

/** 点时间戳 → 交给父页跳转 */
function seek(note: Note) {
  if (note.noteMoment === null || note.noteMoment === undefined) return
  emit('seek', note.noteMoment)
}

async function remove(note: Note) {
  try {
    await ElMessageBox.confirm('删除这条笔记？删了就找不回来了。', '删除笔记', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '再想想',
    })
  } catch {
    return // 用户取消
  }
  try {
    await deleteNote(note.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* request.ts 已提示 */
  }
}
</script>

<template>
  <div v-loading="loading" class="note-panel">
    <div class="panel-toolbar">
      <el-radio-group v-model="scope" size="small" data-testid="note-scope">
        <el-radio-button value="section">本动作</el-radio-button>
        <el-radio-button value="course">整门课</el-radio-button>
      </el-radio-group>
      <span class="panel-count" data-testid="note-total">共 {{ total }} 条</span>
    </div>

    <el-empty
      v-if="!loading && !sortedNotes.length"
      :image-size="60"
      :description="
        scope === 'section' ? '这个动作还没记过笔记，上面写一句试试' : '这门课还没记过笔记'
      "
      data-testid="note-empty"
    />

    <ul v-else class="note-list">
      <li v-for="n in sortedNotes" :key="n.id" class="note-item" data-testid="note-item">
        <button
          class="note-stamp"
          type="button"
          :title="`跳到 ${formatClock(n.noteMoment)}`"
          data-testid="note-stamp"
          @click="seek(n)"
        >
          <el-icon><Clock /></el-icon>
          <span class="stamp-text">{{ formatClock(n.noteMoment) }}</span>
        </button>

        <div class="note-main">
          <p class="note-content" data-testid="note-content">{{ n.content }}</p>
          <div class="note-foot">
            <span class="note-time">{{ formatDateTime(n.createTime) }}</span>
            <span v-if="n.isPrivate" class="note-flag">私有</span>
            <el-button
              class="note-del"
              link
              type="danger"
              size="small"
              :icon="Delete"
              data-testid="note-delete"
              @click="remove(n)"
            >
              删除
            </el-button>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.note-panel {
  min-height: 120px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.panel-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.note-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.note-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

/* ★ 时间戳是"按钮"不是"文字" —— 它是这个面板唯一的可点目标，得看起来像能点 */
.note-stamp {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
  padding: 2px 8px;
  border: none;
  border-radius: 6px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
}

.note-stamp:hover {
  background: var(--el-color-primary-light-8);
}

.stamp-text {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-variant-numeric: tabular-nums;
}

.note-main {
  flex: 1 1 auto;
  min-width: 0;
}

.note-content {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.note-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.note-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.note-flag {
  padding: 0 6px;
  border-radius: 4px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 18px;
}

.note-del {
  margin-left: auto;
}
</style>
