<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage } from 'element-plus'

import { markAllMessagesRead, markMessageRead, pageMessages } from '@/api/message'
import { useMessageStore } from '@/stores/message'
import type { InboxMessage } from '@/types/social'
import { NoticeType } from '@/types/social'
import { formatDateTime, pageTotal } from '@/utils/format'

/**
 * 站内信（M7-5）
 *
 * ★ 消息的来源是**业务链路自动触发的**（M4-4 起接通的 MQ）：
 *   购课成功、退款到账、笔记被采集、问答被回复……都各自在业务侧发消息，
 *   前端只负责"看"和"标记已读"。所以这个页面**只有读和改状态，没有写**。
 *
 * ★ 标记已读后**重新拉未读数**（不本地减 1）：
 *   一条消息可能同时被"标记单条"和"点开自动标记"改到，本地减出来的数会飘。
 */
const messageStore = useMessageStore()

const TAB_ALL = -1
const tabs = [
  { label: '全部', value: TAB_ALL },
  { label: '未读', value: 0 },
  { label: '已读', value: 1 },
]

const activeTab = ref<number>(TAB_ALL)
const list = ref<InboxMessage[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const loading = ref(false)
/** 展开的消息 id（点标题展开内容） */
const expanded = ref<Set<string>>(new Set())

const isEmpty = computed(() => !loading.value && list.value.length === 0)

async function load() {
  loading.value = true
  try {
    const page = await pageMessages({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      isRead: activeTab.value === TAB_ALL ? undefined : activeTab.value,
    })
    list.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), messageStore.load()])
})

function handleTabChange() {
  pageNo.value = 1
  load()
}

function toggleExpand(msg: InboxMessage) {
  const next = new Set(expanded.value)
  if (next.has(msg.id)) {
    next.delete(msg.id)
  } else {
    next.add(msg.id)
  }
  expanded.value = next
  // 点开就算读了 —— 用户已经看到内容，还挂着"未读"会让人以为漏看了什么
  if (msg.isRead === 0) {
    void handleRead(msg)
  }
}

async function handleRead(msg: InboxMessage) {
  try {
    await markMessageRead(msg.id)
    await Promise.all([load(), messageStore.load()])
  } catch {
    /* 已提示 */
  }
}

async function handleReadAll() {
  try {
    await markAllMessagesRead()
    ElMessage.success('已全部标记为已读')
    await Promise.all([load(), messageStore.load()])
  } catch {
    /* 已提示 */
  }
}

function typeLabel(type: number): string {
  switch (type) {
    case NoticeType.SYSTEM:
      return '系统'
    case NoticeType.NOTE:
      return '笔记'
    case NoticeType.QA:
      return '问答'
    case NoticeType.PRIVATE:
      return '私信'
    default:
      return '其它'
  }
}

function typeTag(type: number): 'info' | 'success' | 'warning' | 'primary' {
  switch (type) {
    case NoticeType.NOTE:
      return 'warning'
    case NoticeType.QA:
      return 'primary'
    case NoticeType.PRIVATE:
      return 'success'
    default:
      return 'info'
  }
}
</script>

<template>
  <div class="messages-page">
    <div class="page-head">
      <h2>消息中心</h2>
      <el-tag v-if="messageStore.unread > 0" type="danger" size="small" data-testid="message-unread-tag">
        {{ messageStore.unread }} 条未读
      </el-tag>
      <span class="head-count" data-testid="message-total">共 {{ total }} 条</span>
      <div class="head-actions">
        <el-button size="small" :disabled="messageStore.unread === 0" data-testid="message-read-all" @click="handleReadAll">
          全部标为已读
        </el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="status-tabs" @tab-change="handleTabChange">
      <el-tab-pane v-for="tab in tabs" :key="tab.label" :label="tab.label" :name="tab.value" />
    </el-tabs>

    <el-empty v-if="isEmpty" description="这里空空如也" data-testid="message-empty" />

    <div v-loading="loading" class="msg-list">
      <el-card
        v-for="msg in list"
        :key="msg.id"
        shadow="never"
        class="msg-card"
        :class="{ 'is-unread': msg.isRead === 0 }"
        data-testid="message-item"
      >
        <div class="msg-head" @click="toggleExpand(msg)">
          <el-tag :type="typeTag(msg.type)" size="small" effect="plain">
            {{ typeLabel(msg.type) }}
          </el-tag>
          <span class="msg-title">{{ msg.title }}</span>
          <span v-if="msg.isRead === 0" class="unread-dot" data-testid="message-unread-dot" />
          <span class="msg-time">{{ formatDateTime(msg.pushTime) }}</span>
        </div>

        <div v-if="expanded.has(msg.id)" class="msg-body" data-testid="message-body">
          {{ msg.content || '（无内容）' }}
        </div>

        <div class="msg-foot">
          <el-button link size="small" @click="toggleExpand(msg)">
            {{ expanded.has(msg.id) ? '收起' : '展开' }}
          </el-button>
          <el-button
            v-if="msg.isRead === 0"
            link
            type="primary"
            size="small"
            data-testid="message-mark-read"
            @click="handleRead(msg)"
          >
            标为已读
          </el-button>
          <span v-else class="read-tag">已读</span>
        </div>
      </el-card>
    </div>

    <el-pagination
      v-if="total > pageSize"
      v-model:current-page="pageNo"
      class="pager"
      layout="prev, pager, next"
      :page-size="pageSize"
      :total="total"
      @current-change="load"
    />
  </div>
</template>

<style scoped>
.messages-page {
  max-width: 860px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.head-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.head-actions {
  margin-left: auto;
}

.status-tabs {
  margin-bottom: 8px;
}

.msg-list {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.msg-card.is-unread {
  border-left: 3px solid var(--el-color-primary);
}

.msg-head {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.msg-title {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.unread-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-danger);
  flex-shrink: 0;
}

.msg-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.msg-body {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-foot {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.read-tag {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
