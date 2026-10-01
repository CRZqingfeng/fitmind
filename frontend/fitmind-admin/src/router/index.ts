import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

import AdminLayout from '@/layouts/AdminLayout.vue'
import { getToken } from '@/utils/token'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    public?: boolean
    /** 侧边菜单里是否隐藏（个人中心这类不占菜单位） */
    hideInMenu?: boolean
  }
}

/**
 * 管理端路由
 *
 * ★ 侧边菜单**直接从这个表推导**（见 `AdminLayout.vue` —— 它遍历
 *   `router.options.routes` 里 `/` 的子路由，过滤掉 `hideInMenu`）：
 *   所以"加页面"只需要在这里加一条，不会出现"加了路由忘了加菜单"。
 *   代价是：**菜单顺序 = 这里的顺序**，图标要去 AdminLayout 的 `menuIcons` 补
 *   （忘了补不会报错，只会静默退化成默认图标 —— 所以加完路由顺手看一眼菜单）。
 *
 * ★ 页面顺序按"运营的日常动线"排，不按接口分类：
 *   先看数据（看板）→ 发现异常去处理（退款）→ 日常维护（课程 / 媒资）→
 *   拉新促活（优惠券）→ AI 侧的运维（AI 运营）。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true, hideInMenu: true },
  },
  {
    path: '/',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '数据看板' },
      },
      {
        path: 'refunds',
        name: 'refunds',
        component: () => import('@/views/RefundAdminView.vue'),
        meta: { title: '退款审批' },
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/views/CourseAdminView.vue'),
        meta: { title: '课程管理' },
      },
      // ==================== M7-12 第 2 期：建课主线 ====================
      /**
       * ★ 建课向导用**两条路由指向同一个组件**：
       *   `courses/edit`（新建）与 `courses/edit/:id`（继续填已有草稿）。
       *   组件内部靠"有没有 :id"区分模式（`isEdit`），而不是拆成两个文件 ——
       *   5 步表单的字段与校验完全一样，拆开只会变成两份要同步维护的代码。
       *   ★ 两条都 `hideInMenu`：它们是从「课程管理」点进去的，不该占菜单位。
       */
      {
        path: 'courses/edit',
        name: 'course-edit',
        component: () => import('@/views/CourseEditView.vue'),
        meta: { title: '新建课程', hideInMenu: true },
      },
      {
        path: 'courses/edit/:id',
        name: 'course-edit-id',
        component: () => import('@/views/CourseEditView.vue'),
        meta: { title: '编辑课程', hideInMenu: true },
      },
      {
        path: 'categorys',
        name: 'categorys',
        component: () => import('@/views/CategoryAdminView.vue'),
        meta: { title: '分类管理' },
      },
      {
        path: 'subjects',
        name: 'subjects',
        component: () => import('@/views/SubjectAdminView.vue'),
        meta: { title: '题库管理' },
      },
      // ==================== M7-12 第 3 期：内容运营 ====================
      /**
       * ★ 这两页补上的是 admin 最后缺失的"内容运营"能力 ——
       *   在此之前管理端只能管"课"，管不了"用户产生的内容"（问答/笔记）和"对外的通知"。
       */
      {
        path: 'community-audit',
        name: 'community-audit',
        component: () => import('@/views/CommunityAuditView.vue'),
        meta: { title: '社区审核' },
      },
      {
        path: 'notices',
        name: 'notices',
        component: () => import('@/views/NoticeAdminView.vue'),
        meta: { title: '公告与模板' },
      },
      // ==================== M7-10 动作考核 ====================
      {
        path: 'action-assess',
        name: 'action-assess',
        component: () => import('@/views/ActionAdminView.vue'),
        meta: { title: '动作考核' },
      },
      {
        path: 'media',
        name: 'media',
        component: () => import('@/views/MediaAdminView.vue'),
        meta: { title: '媒资管理' },
      },
      {
        path: 'coupons',
        name: 'coupons',
        component: () => import('@/views/CouponAdminView.vue'),
        meta: { title: '优惠券' },
      },
      {
        path: 'ai-ops',
        name: 'ai-ops',
        component: () => import('@/views/AiOpsView.vue'),
        meta: { title: 'AI 运营' },
      },
      {
        path: 'me',
        name: 'me',
        component: () => import('@/views/MeView.vue'),
        meta: { title: '账号信息', hideInMenu: true },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true, hideInMenu: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/** 全局前置守卫：与用户端同构（没 token 就送去登录页，并记住原目标） */
router.beforeEach((to) => {
  const appName = import.meta.env.VITE_APP_NAME
  document.title = to.meta.title ? `${to.meta.title} · ${appName}` : appName

  if (to.meta.public) {
    if (to.name === 'login' && getToken()) {
      return { name: 'dashboard' }
    }
    return true
  }

  if (!getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
