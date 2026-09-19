import { createRouter, createWebHistory } from 'vue-router'

import { isLoggedIn } from '@/utils/auth'

/**
 * 路由表。
 *
 * 两类页面:
 * 1. 免登录:首页(商品列表)、商品详情、领券列表 —— 小程序里"先逛后登录"是常态,
 *    强制登录会明显增加流失;
 * 2. 需要登录:购物车、结算、订单、售后、我的 —— 它们都是"这个客户自己的数据"。
 *
 * 守卫只做"没令牌就跳登录",不做权限码判断:商城端没有 RBAC 权限体系,
 * 能改什么由服务端按客户身份判断(见后端 ClientAuthFilter)。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'Login', component: () => import('@/views/login/index.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/views/layout/TabbarLayout.vue'),
      children: [
        { path: '', name: 'Home', component: () => import('@/views/home/index.vue'), meta: { public: true } },
        { path: 'cart', name: 'Cart', component: () => import('@/views/cart/index.vue') },
        { path: 'orders', name: 'Orders', component: () => import('@/views/order/list.vue') },
        { path: 'profile', name: 'Profile', component: () => import('@/views/profile/index.vue') },
      ],
    },
    { path: '/goods/:goodsId', name: 'GoodsDetail', component: () => import('@/views/goods/detail.vue'), meta: { public: true } },
    { path: '/coupons', name: 'Coupons', component: () => import('@/views/coupon/index.vue'), meta: { public: true } },
    { path: '/checkout', name: 'Checkout', component: () => import('@/views/order/checkout.vue') },
    { path: '/orders/:orderId', name: 'OrderDetail', component: () => import('@/views/order/detail.vue') },
    { path: '/after-sales', name: 'AfterSales', component: () => import('@/views/after-sale/index.vue') },
    { path: '/after-sales/apply/:orderItemId', name: 'AfterSaleApply', component: () => import('@/views/after-sale/apply.vue') },
  ],
})

router.beforeEach((to) => {
  if (to.meta.public) {
    return true
  }
  if (isLoggedIn()) {
    return true
  }
  // 带上目标地址,登录后回到用户原本想去的页面
  return { name: 'Login', query: { redirect: to.fullPath } }
})

export default router
