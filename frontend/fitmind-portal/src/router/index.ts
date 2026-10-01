import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { getToken } from '@/utils/token'

declare module 'vue-router' {
  interface RouteMeta {
    /** 浏览器标签标题 */
    title?: string
    /** 免登录页面（默认全部需要登录） */
    public?: boolean
    /** 侧边菜单里是否显示 */
    hideInMenu?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true, hideInMenu: true },
  },
  // M7-12：注册页（★ 免登录；M7 方案 §7.1 第 1 页承诺过但此前未交付）
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册', public: true, hideInMenu: true },
  },
  {
    path: '/',
    component: DefaultLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页' },
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/views/CourseListView.vue'),
        meta: { title: '课程' },
      },
      {
        path: 'course/:id',
        name: 'course-detail',
        component: () => import('@/views/CourseDetailView.vue'),
        // ★ 详情页不在导航里高亮（hideInMenu 只影响菜单，这里靠 activeMenu 的匹配规则）
        meta: { title: '课程详情', hideInMenu: true },
      },
      // ==================== M7-3 交易链路 ====================
      {
        path: 'cart',
        name: 'cart',
        component: () => import('@/views/CartView.vue'),
        meta: { title: '购物车' },
      },
      {
        path: 'checkout',
        name: 'checkout',
        component: () => import('@/views/CheckoutView.vue'),
        // ★ 不在菜单里：结算页必须带着课程参数进入，从菜单直接点是打不开的
        meta: { title: '确认订单', hideInMenu: true },
      },
      {
        path: 'pay/:orderId',
        name: 'pay',
        component: () => import('@/views/PayView.vue'),
        meta: { title: '订单支付', hideInMenu: true },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrdersView.vue'),
        meta: { title: '我的订单' },
      },
      {
        path: 'orders/:id',
        name: 'order-detail',
        component: () => import('@/views/OrderDetailView.vue'),
        meta: { title: '订单详情', hideInMenu: true },
      },
      {
        path: 'refunds',
        name: 'refunds',
        component: () => import('@/views/RefundsView.vue'),
        meta: { title: '我的退款' },
      },
      // M7-12：我的优惠券 + 兑换码（★ M7 方案 §7.1 第 10 页承诺过，此前只有接口没有页面）
      {
        path: 'my-coupons',
        name: 'my-coupons',
        component: () => import('@/views/MyCouponsView.vue'),
        meta: { title: '我的优惠券', hideInMenu: true },
      },
      // ==================== M7-4 学习链路 ====================
      {
        path: 'my-courses',
        name: 'my-courses',
        component: () => import('@/views/MyCoursesView.vue'),
        meta: { title: '我的课程' },
      },
      {
        path: 'learn/:courseId',
        name: 'learn',
        component: () => import('@/views/LearnView.vue'),
        meta: { title: '训练', hideInMenu: true },
      },
      {
        path: 'plans',
        name: 'plans',
        component: () => import('@/views/PlansView.vue'),
        meta: { title: '训练计划', hideInMenu: true },
      },
      {
        path: 'growth',
        name: 'growth',
        component: () => import('@/views/GrowthView.vue'),
        meta: { title: '成长' },
      },
      // ==================== M7-6 体测评估 ====================
      {
        path: 'assessment',
        name: 'assessment',
        component: () => import('@/views/AssessmentView.vue'),
        meta: { title: '体测' },
      },
      // ==================== M7-10 动作考核 ====================
      {
        path: 'action-assess',
        name: 'action-assess',
        component: () => import('@/views/ActionAssessView.vue'),
        meta: { title: '动作考核' },
      },
      // ==================== M7-5 社区与消息 ====================
      {
        path: 'community',
        name: 'community',
        component: () => import('@/views/CommunityView.vue'),
        meta: { title: '社区' },
      },
      {
        path: 'community/question/:id',
        name: 'question-detail',
        component: () => import('@/views/QuestionDetailView.vue'),
        meta: { title: '问题详情', hideInMenu: true },
      },
      {
        path: 'messages',
        name: 'messages',
        component: () => import('@/views/MessagesView.vue'),
        // 入口在顶栏的消息图标（带未读角标），不在横向菜单里
        meta: { title: '消息中心', hideInMenu: true },
      },
      {
        path: 'ai',
        name: 'ai-coach',
        component: () => import('@/views/AiCoachView.vue'),
        meta: { title: 'AI 教练' },
      },
      {
        path: 'me',
        name: 'me',
        component: () => import('@/views/MeView.vue'),
        meta: { title: '我的' },
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

/**
 * 全局前置守卫
 *
 * 后端概念映射：路由守卫 ≈ 网关的 AccountAuthFilter —— 进页面前判断能不能进。
 * 区别：网关是"没 token 就 401"，守卫是"没 token 就送去登录页"（更友好）。
 *
 * ★ 这里只判断"有没有 token"，不校验 token 是否有效：
 *   有效性由后端说了算，前端校验等于把鉴权逻辑复制一份（两边不一致时更麻烦）。
 *   token 真的失效了，第一个请求会 401，由 request.ts 的续期逻辑兜底。
 */
router.beforeEach((to) => {
  const appName = import.meta.env.VITE_APP_NAME
  document.title = to.meta.title ? `${to.meta.title} · ${appName}` : appName

  if (to.meta.public) {
    // 已登录还去登录页 → 直接回首页（顺手挡掉手动敲 URL 的情况）
    if (to.name === 'login' && getToken()) {
      return { name: 'home' }
    }
    return true
  }

  if (!getToken()) {
    // 记住原本要去哪，登录后跳回去
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
