<script setup lang="ts">
import { computed, ref } from 'vue'

import { Clock, EditPen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { saveNote } from '@/api/social'
import { formatClock } from '@/utils/format'

/**
 * 快捷笔记条（M10-1 第 1 期）
 *
 * ============================================================
 * ★★ 它解决的唯一问题：**让"记笔记"不用离开播放器**
 * ============================================================
 * 以前要记笔记得去社区页、还得先选课程再选动作 —— 等选完，想记的那句话已经忘了。
 * 这里就一行：**左边是当前时间点，右边是输入框**，回车/点「记下」就落库。
 *
 * ============================================================
 * ★ `moment` 由**父页**维护，本组件只负责提交
 * ============================================================
 * 理由：时间点来自 `<video>` 的 `timeupdate`，而 `<video>` 只有播放页有。
 * 子组件自己去 `document.querySelector('video')` 看着省事，但**一旦播放器换成
 * 别的实现（或页面上出现第二个 video）就会静默取错对象** —— 取错对象不会报错，
 * 只会把错误的时间点写进笔记，事后完全看不出来。
 *
 * ============================================================
 * ★ 默认 `isPrivate: true`
 * ============================================================
 * `note` 表有 `is_private`，而"公开"在本项目里的语义是**被别人采集**（gather），
 * 不是"人人可见"。所以笔记默认私有 —— 想分享是主动动作，不是默认状态。
 */
const props = defineProps<{
  courseId: string
  /** 当前动作所属训练日（`NoteFormDTO.chapterId` 必填，父页反查后传入） */
  chapterId: string
  /** 当前动作（`NoteFormDTO.sectionId` 必填） */
  sectionId: string
  /** 当前播放秒数 */
  moment: number
  /** 没选中动作 / 视频没加载好时禁用 */
  disabled?: boolean
}>()

const emit = defineEmits<{ (e: 'saved'): void }>()

const content = ref('')
const submitting = ref(false)

const clock = computed(() => formatClock(props.moment))

async function submit() {
  if (props.disabled || submitting.value) return
  const text = content.value.trim()
  if (!text) {
    ElMessage.warning('先写点什么吧')
    return
  }

  submitting.value = true
  try {
    await saveNote({
      courseId: props.courseId,
      chapterId: props.chapterId,
      sectionId: props.sectionId,
      // ★ 取的是"此刻"的播放位置 —— 提交有网络延迟，但这个值是**点击时**读的，不是回调里读的
      noteMoment: props.moment,
      content: text,
      isPrivate: true,
    })
    content.value = ''
    // ★ 提示里带上时间点：让用户确认"记到了我刚才看的那一秒"，而不是只有一个模糊的"成功"
    ElMessage.success(`已记下（${clock.value}）`)
    emit('saved')
  } catch {
    /* request.ts 已统一提示，这里不重复弹 */
  } finally {
    submitting.value = false
  }
}

/**
 * `Ctrl/Cmd + Enter` 提交 —— 一行输入框不值得让手离开键盘。
 *
 * ★ 参数类型必须是 `Event | KeyboardEvent`：Element Plus 的 `el-input`
 *   把 `@keydown` 声明成了这个联合类型（它内部转发的是原生事件），
 *   只写 `KeyboardEvent` 会被 `vue-tsc` 判成不兼容。
 */
function onKeydown(e: Event | KeyboardEvent) {
  if (!(e instanceof KeyboardEvent)) return
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    void submit()
  }
}
</script>

<template>
  <div class="note-composer" :class="{ 'is-disabled': disabled }" data-testid="note-composer">
    <span class="stamp" :title="`这条笔记会挂到 ${clock}，之后点它就能跳回来`">
      <el-icon><Clock /></el-icon>
      <span class="stamp-text" data-testid="note-composer-clock">{{ clock }}</span>
    </span>

    <el-input
      v-model="content"
      class="composer-input"
      :disabled="disabled"
      :placeholder="
        disabled ? '先选一个训练动作' : '记下这一句（自动带上当前时间点，之后点它就能跳回来）'
      "
      maxlength="2000"
      data-testid="note-composer-input"
      @keydown="onKeydown"
    />

    <el-button
      type="primary"
      :icon="EditPen"
      :loading="submitting"
      :disabled="disabled"
      data-testid="note-composer-submit"
      @click="submit"
    >
      记下
    </el-button>
  </div>
</template>

<style scoped>
.note-composer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}

.note-composer.is-disabled {
  opacity: 0.6;
}

/* 时间点：等宽字体 + 定宽 —— 列表里要能竖向对齐扫读 */
.stamp {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 13px;
  line-height: 20px;
}

.stamp-text {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-variant-numeric: tabular-nums;
}

.composer-input {
  flex: 1 1 auto;
  min-width: 0;
}
</style>
