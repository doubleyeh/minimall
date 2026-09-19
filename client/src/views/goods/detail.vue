<template>
  <div class="page" v-if="goods">
    <t-navbar :title="goods.goodsName" left-arrow :fixed="false" @go-back="goBack" />

    <img class="main-image" :src="goods.mainImage" alt="" />

    <div class="card">
      <div class="price">
        ¥{{ currentSku ? currentSku.price : goods.salePriceMin }}
        <span class="unit">/ 件</span>
      </div>
      <div class="title">{{ goods.goodsName }}</div>
      <div class="subtitle ellipsis-2">{{ goods.goodsSubtitle || '' }}</div>
      <div class="meta">
        <span>已售 {{ goods.saleCount }}</span>
        <span>可售 {{ currentSku ? currentSku.availableStock : goods.totalStock }} 件</span>
      </div>
    </div>

    <div class="card" v-if="goods.skus.length > 0">
      <div class="section-title">选择规格</div>
      <div class="skus">
        <div
          v-for="sku in goods.skus"
          :key="sku.id"
          class="sku-item"
          :class="{ active: String(selectedSkuId) === String(sku.id), disabled: sku.availableStock <= 0 }"
          @click="selectSku(sku)"
        >
          {{ sku.skuName }}
          <span v-if="sku.availableStock <= 0" class="sold-out">(售罄)</span>
        </div>
      </div>
      <div class="quantity">
        <span>数量</span>
        <t-stepper v-model="quantity" :min="1" :max="maxQuantity" />
      </div>
    </div>

    <div class="card" v-else>
      <div class="empty-tip">该商品暂无可售规格</div>
    </div>

    <div class="card" v-if="goods.detailContent">
      <div class="section-title">商品详情</div>
      <!-- 详情是后端配置的 HTML 片段,这里按原样渲染(商品详情的富文本由商家自己维护) -->
      <div class="detail-content" v-html="goods.detailContent"></div>
    </div>

    <!--
      商品评价是公开接口,游客也要能看:一个"没有评价"的商品页会让人怀疑没人买过。
      没有评价时如实显示"暂无评价",不做假数据填充。
    -->
    <div class="card">
      <div class="section-title">
        商品评价<span v-if="reviewTotal > 0" class="review-count">({{ reviewTotal }})</span>
      </div>
      <div v-if="reviews.length === 0" class="empty-tip">暂无评价</div>
      <template v-else>
        <div v-for="review in reviews" :key="review.id" class="review">
          <div class="review-head">
            <span class="review-user">{{ review.customerNickname || '匿名用户' }}</span>
            <span class="review-stars">{{ stars(review.rating) }}</span>
          </div>
          <div class="review-content">{{ review.content || '该用户没有填写评价内容' }}</div>
          <div v-if="reviewImages(review).length > 0" class="review-images">
            <img v-for="(url, index) in reviewImages(review)" :key="index" :src="url" alt="" />
          </div>
          <div v-if="review.replyContent" class="review-reply">商家回复:{{ review.replyContent }}</div>
          <div class="review-time">{{ review.createTime }}</div>
        </div>
        <div v-if="reviewTotal > reviews.length" class="review-more" @click="loadMoreReviews">
          查看更多评价
        </div>
      </template>
    </div>

    <div class="page-gap"></div>

    <div class="footer">
      <div class="footer-action" @click="goCart">购物车</div>
      <t-button theme="light" :disabled="!canBuy" @click="onAddToCart">加入购物车</t-button>
      <t-button theme="primary" :disabled="!canBuy" @click="onBuyNow">立即购买</t-button>
    </div>
  </div>
  <div v-else class="empty-tip">加载中…</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { cartAdd, goodsDetail, goodsReviews } from '@/api/client'
import { isLoggedIn } from '@/utils/auth'
import type { ClientGoodsDetailView, ClientSkuView, Id, ReviewView } from '@/types/client'
import { ApiError } from '@/utils/request'

const route = useRoute()
const router = useRouter()

const goods = ref<ClientGoodsDetailView | null>(null)
const selectedSkuId = ref<Id | ''>('')
const quantity = ref(1)

const currentSku = computed<ClientSkuView | null>(
  () => goods.value?.skus.find((sku) => String(sku.id) === String(selectedSkuId.value)) ?? null,
)

/** 库存不足 1 件时不给下单:提前在端上挡住,避免提交后才被后端拒绝 */
const maxQuantity = computed(() => Math.max(1, currentSku.value?.availableStock ?? 1))
const canBuy = computed(() => currentSku.value != null && currentSku.value.availableStock > 0)

function selectSku(sku: ClientSkuView): void {
  if (sku.availableStock <= 0) {
    return
  }
  selectedSkuId.value = sku.id
  quantity.value = 1
}

function goBack(): void {
  void router.back()
}

function goCart(): void {
  void router.push('/cart')
}

/** 需要登录的操作统一走这里:未登录先跳登录,登录后回到本页 */
async function requireLogin(): Promise<boolean> {
  if (isLoggedIn()) {
    return true
  }
  await router.push({ name: 'Login', query: { redirect: route.fullPath } })
  return false
}

async function onAddToCart(): Promise<void> {
  if (!currentSku.value) return
  if (!(await requireLogin())) return
  try {
    await cartAdd(currentSku.value.id, quantity.value)
    window.alert('已加入购物车')
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '加入购物车失败')
  }
}

async function onBuyNow(): Promise<void> {
  if (!currentSku.value) return
  if (!(await requireLogin())) return
  // 立即购买不经过购物车:把要买的东西放进 query,结算页直接用它下单
  await router.push({
    name: 'Checkout',
    query: { skuId: currentSku.value.id, quantity: String(quantity.value) },
  })
}

// ——— 商品评价 ———

const REVIEW_PAGE_SIZE = 5
const reviews = ref<ReviewView[]>([])
const reviewTotal = ref(0)
const reviewPage = ref(0)

async function loadReviews(page = 1): Promise<void> {
  const result = await goodsReviews(String(route.params.goodsId), page, REVIEW_PAGE_SIZE)
  // 第一页覆盖、后续追加:分页接口返回的是整页,直接赋值会把已展示的评价替换掉
  reviews.value = page === 1 ? result.list : [...reviews.value, ...result.list]
  reviewTotal.value = result.total
  reviewPage.value = page
}

function loadMoreReviews(): void {
  void loadReviews(reviewPage.value + 1)
}

function stars(rating: number): string {
  const filled = Math.max(0, Math.min(5, rating))
  return '★'.repeat(filled) + '☆'.repeat(5 - filled)
}

/**
 * 解析评价图片。
 *
 * 后端存的是 **JSON 数组字符串**(形如 ["https://a.png","https://b.png"]) ——
 * 不是逗号分隔的列表。按逗号切分会把方括号和引号一起渲染出来,所以这里先按 JSON 解析;
 * 解析失败时退化为剥掉括号引号再切分,兼容历史数据。
 */
function reviewImages(review: ReviewView): string[] {
  const raw = review.images
  if (!raw) {
    return []
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      return parsed.filter((url): url is string => typeof url === 'string' && url.trim().length > 0)
    }
  } catch {
    // 不是严格 JSON:走下面的兜底
  }
  return raw
    .replace(/[[\]"]/g, '')
    .split(',')
    .map((url) => url.trim())
    .filter(Boolean)
}

onMounted(async () => {
  goods.value = await goodsDetail(String(route.params.goodsId))
  // 默认选中第一个有货的 SKU:让用户少点一次(没有可售 SKU 时保持未选中)
  const firstAvailable = goods.value.skus.find((sku) => sku.availableStock > 0)
  if (firstAvailable) {
    selectedSkuId.value = firstAvailable.id
  }
  await loadReviews(1)
})
</script>

<style scoped>
.main-image {
  width: 100%;
  height: 300px;
  object-fit: cover;
  background: #f5f5f5;
  display: block;
}

.unit {
  font-size: 12px;
  font-weight: 400;
}

.title {
  margin-top: 6px;
  font-size: 15px;
  font-weight: 600;
}

.subtitle {
  margin-top: 4px;
  color: #999;
  font-size: 12px;
}

.meta {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  color: #999;
  font-size: 12px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

.skus {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.sku-item {
  padding: 6px 12px;
  border: 1px solid #e0e0e0;
  border-radius: 16px;
  font-size: 13px;
  color: #333;
}

.sku-item.active {
  border-color: var(--mall-price-color);
  color: var(--mall-price-color);
  background: #fff5f5;
}

.sku-item.disabled {
  color: #ccc;
  border-color: #f0f0f0;
}

.sold-out {
  font-size: 11px;
}

.quantity {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  font-size: 14px;
}

.detail-content {
  font-size: 13px;
  line-height: 1.7;
  color: #333;
  word-break: break-all;
}

.page-gap {
  height: 72px;
}

.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #f0f0f0;
}

.footer-action {
  flex: 0 0 auto;
  font-size: 12px;
  color: #666;
  text-align: center;
  padding-right: 4px;
}

.review-count {
  color: #999;
  font-weight: 400;
}

.review {
  padding: 10px 0;
  border-bottom: 1px solid #f5f5f5;
}

.review:last-child {
  border-bottom: none;
}

.review-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.review-user {
  font-size: 13px;
  color: #333;
}

.review-stars {
  font-size: 13px;
  color: #ffa500;
  letter-spacing: 1px;
}

.review-content {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: #333;
  word-break: break-all;
}

.review-images {
  display: flex;
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.review-images img {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 4px;
  background: #f5f5f5;
}

.review-reply {
  margin-top: 6px;
  padding: 6px 8px;
  background: #f7f7f7;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
}

.review-time {
  margin-top: 6px;
  font-size: 11px;
  color: #aaa;
}

.review-more {
  padding: 10px 0;
  text-align: center;
  font-size: 13px;
  color: #666;
}
</style>
