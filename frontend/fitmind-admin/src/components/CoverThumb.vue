<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 封面缩略图（管理端通用）
 *
 * 两个必须处理的失败路径（都在用户端撞过，管理端同样会遇到）：
 *  ① **对象不存在 / 桶策略变了** → 直链 403/404 → `<img>` onerror
 *  ② ★★ **对象存成了 `binary/octet-stream`** → 直链**返回 200**，
 *     但浏览器**不把非 `image/*` 的响应当图片渲染** → 还是 onerror
 *     （起因是上传时没带 Content-Type，见 M7-7 的记录与 `scripts/fix-minio-content-type.py`）
 *
 * 所以"图裂了"在这里是**常态**而不是异常：失败就降级成
 * "按名字生成的渐变色 + 首字"，至少能一眼区分是哪张图。
 * ★ 渐变色的色相由 `seed` 决定 —— 同一个对象每次刷新必须是同一个颜色，
 *   否则同一个列表两次打开颜色不同，看着像数据乱了。
 */
const props = withDefaults(
  defineProps<{
    src?: string | null
    /** 降级时显示的兜底字符（一般是名字首字） */
    name?: string | null
    /** 生成颜色的种子（同一对象必须稳定） */
    seed?: string | number | null
    width?: number
    height?: number
  }>(),
  { src: null, name: null, seed: null, width: 72, height: 45 },
)

const failed = ref(false)

// 换了一张图（比如翻页、换了行）要重置失败标记，否则后续图片永远走降级
watch(
  () => props.src,
  () => {
    failed.value = false
  },
)

const showFallback = computed(() => !props.src || failed.value)

const initial = computed(() => (props.name ?? '?').trim().slice(0, 1) || '?')

const style = computed(() => {
  const key = String(props.seed ?? props.name ?? '')
  let h = 0
  for (const ch of key) h = (h * 31 + ch.charCodeAt(0)) % 360
  return {
    width: `${props.width}px`,
    height: `${props.height}px`,
    background: `linear-gradient(135deg, hsl(${h},68%,62%), hsl(${(h + 45) % 360},70%,42%))`,
  }
})
</script>

<template>
  <div class="cover-thumb" :style="style">
    <img v-if="!showFallback" :src="src ?? ''" :alt="name ?? ''" @error="failed = true" />
    <span v-else class="cover-initial">{{ initial }}</span>
  </div>
</template>

<style scoped>
.cover-thumb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  overflow: hidden;
  background: var(--el-fill-color-light);
  flex: 0 0 auto;
}

.cover-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-initial {
  color: rgba(255, 255, 255, 0.94);
  font-size: 15px;
  font-weight: 700;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.18);
}
</style>
