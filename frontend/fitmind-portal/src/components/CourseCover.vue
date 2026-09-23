<script setup lang="ts">
import { computed, ref } from 'vue'

/**
 * 课程封面（带降级）
 *
 * ============================================================
 * ★★ 为什么必须抽成组件，而不是在两个地方各写一遍
 * ============================================================
 * 封面兜底的核心是"**按课程名算出一个固定色相**" —— 同一门课每次都是同一个颜色。
 * 如果 AI 教练的卡片和课程列表各写一份、算法稍有出入（比如一个用 charCodeAt、
 * 一个用 codePointAt，或者一个 +50 一个 +40），
 * **同一门课在聊天里和列表里会显示成两种颜色** —— 用户会以为是两门不同的课。
 * 这种"看起来像数据错误、其实是两份实现不一致"的问题最难解释。
 *
 * ============================================================
 * ★ 为什么要降级：MinIO 桶默认不开匿名读
 * ============================================================
 * `coverUrl` 存的是 MinIO 直链。桶没开匿名只读时浏览器加载会 403，
 * 而 `<img>` 的加载失败**不抛异常、不影响 JS** —— 不处理就只是一个破图。
 * 所以这里把"加载失败"当**常态**处理：失败即换成"课程名首字 + 按名字生成的渐变"。
 * （演示环境已给桶开了匿名只读，但代码不能依赖环境的这一步，否则换个环境就破图。）
 */
const props = defineProps<{
  coverUrl: string | null | undefined
  /** 课程名（既用于 alt，也用于算兜底色 + 首字） */
  name: string
  /** 宽高比，默认 16:10（列表用）；聊天气泡里用小一点的图可传 '1 / 1' */
  aspect?: string
}>()

const coverFailed = ref(false)

const showFallback = computed(() => !props.coverUrl || coverFailed.value)

const coverStyle = computed(() => ({
  aspectRatio: props.aspect ?? '16 / 10',
}))

/**
 * 兜底渐变：按课程名算色相，保证"同一门课每次都是同一个颜色"。
 * ★ 这个算法是**唯一**的（列表卡片与聊天气泡共用本组件），不要在各处再实现一遍。
 */
const fallbackStyle = computed(() => {
  let h = 0
  for (const ch of props.name) h = (h * 31 + ch.charCodeAt(0)) % 360
  return {
    background: `linear-gradient(135deg, hsl(${h},72%,60%), hsl(${(h + 50) % 360},74%,40%))`,
  }
})
</script>

<template>
  <div class="fm-cover" :style="[coverStyle, showFallback ? fallbackStyle : undefined]">
    <img
      v-if="!showFallback"
      :src="coverUrl ?? ''"
      :alt="name"
      loading="lazy"
      @error="coverFailed = true"
    />
    <span v-else class="fm-cover-initial">{{ name.slice(0, 1) }}</span>
    <!-- 角标等叠在封面上层的内容由使用方决定 -->
    <slot />
  </div>
</template>

<style scoped>
.fm-cover {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--el-fill-color-light);
}

.fm-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.fm-cover-initial {
  font-size: 40px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.92);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
</style>
