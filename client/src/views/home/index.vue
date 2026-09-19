<template>
  <div class="page">
    <t-navbar title="商城" :fixed="false" />

    <div class="search">
      <t-input v-model="keyword" placeholder="搜索商品" @confirm="onSearch" />
    </div>

    <!-- 分类:一级分类横滑,选中后带出它下面的二级分类商品(后端已处理) -->
    <div class="categories">
      <div
        v-for="item in categoryTabs"
        :key="String(item.id)"
        class="category-item"
        :class="{ active: String(activeCategoryId) === String(item.id) }"
        @click="onCategoryChange(item.id)"
      >
        {{ item.categoryName }}
      </div>
    </div>

    <div v-if="loading" class="empty-tip">加载中…</div>
    <div v-else-if="goodsList.length === 0" class="empty-tip">暂无商品</div>
    <div v-else class="goods-list">
      <div v-for="goods in goodsList" :key="goods.id" class="goods-card" @click="openGoods(goods.id)">
        <img class="goods-image" :src="goods.mainImage" alt="" />
        <div class="goods-info">
          <div class="goods-name ellipsis-2">{{ goods.goodsName }}</div>
          <div class="goods-subtitle ellipsis">{{ goods.goodsSubtitle || '' }}</div>
          <div class="goods-bottom">
            <span class="price">
              ¥{{ goods.salePriceMin }}
              <span v-if="goods.salePriceMax > goods.salePriceMin"> 起</span>
            </span>
            <span class="sales">已售 {{ goods.saleCount }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="hasMore && !loading" class="load-more" @click="loadMore">加载更多</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { categories, goodsPage } from '@/api/client'
import type { CategoryTreeNode, ClientGoodsView, Id } from '@/types/client'

/**
 * 首页:分类 + 商品列表。
 *
 * 游客可访问(路由 meta.public)—— 小程序里"先逛后登录"是常态,
 * 加购或下单时才要求登录。
 */
const router = useRouter()

const keyword = ref('')
const categoryTabs = ref<Array<{ id: Id | ''; categoryName: string }>>([{ id: '', categoryName: '全部' }])
const activeCategoryId = ref<Id | ''>('')
const goodsList = ref<ClientGoodsView[]>([])
const loading = ref(false)
const pageNo = ref(1)
const hasMore = ref(false)

function flattenCategories(nodes: CategoryTreeNode[]): Array<{ id: Id; categoryName: string }> {
  const result: Array<{ id: Id; categoryName: string }> = []
  const walk = (list: CategoryTreeNode[]): void => {
    for (const node of list) {
      // 只把一级分类作为入口:二级分类的商品由后端按父分类一并返回,端上不必再点一层
      if (String(node.parentId) === '0') {
        result.push({ id: node.id, categoryName: node.categoryName })
      }
      if (node.children?.length) {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return result
}

async function load(reset = true): Promise<void> {
  loading.value = true
  try {
    const result = await goodsPage({
      categoryId: activeCategoryId.value || null,
      keyword: keyword.value.trim() || undefined,
      pageNo: pageNo.value,
      pageSize: 10,
    })
    goodsList.value = reset ? result.list : [...goodsList.value, ...result.list]
    hasMore.value = goodsList.value.length < result.total
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  pageNo.value = 1
  void load()
}

function onCategoryChange(categoryId: Id | ''): void {
  activeCategoryId.value = categoryId
  pageNo.value = 1
  void load()
}

function loadMore(): void {
  pageNo.value += 1
  void load(false)
}

function openGoods(goodsId: Id): void {
  void router.push(`/goods/${goodsId}`)
}

onMounted(async () => {
  const tree = await categories()
  categoryTabs.value = [{ id: '', categoryName: '全部' }, ...flattenCategories(tree)]
  await load()
})
</script>

<style scoped>
.search {
  padding: 8px 12px;
  background: #fff;
}

.categories {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  overflow-x: auto;
  background: #fff;
  white-space: nowrap;
}

.category-item {
  flex: 0 0 auto;
  padding: 6px 14px;
  border-radius: 16px;
  background: #f5f5f5;
  color: #666;
  font-size: 13px;
}

.category-item.active {
  background: var(--mall-price-color);
  color: #fff;
}

.goods-list {
  padding: 8px;
}

.goods-card {
  display: flex;
  gap: 12px;
  padding: 10px;
  margin-bottom: 8px;
  background: #fff;
  border-radius: 8px;
}

.goods-image {
  width: 96px;
  height: 96px;
  flex: 0 0 96px;
  object-fit: cover;
  border-radius: 6px;
  background: #f5f5f5;
}

.goods-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.goods-name {
  font-size: 14px;
  line-height: 1.4;
}

.goods-subtitle {
  color: #999;
  font-size: 12px;
  margin-top: 4px;
}

.goods-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.price {
  font-size: 16px;
}

.sales {
  color: #999;
  font-size: 12px;
}

.load-more {
  padding: 12px;
  text-align: center;
  color: #666;
  font-size: 13px;
}
</style>
