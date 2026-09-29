<template>
  <n-space vertical :size="16">
    <n-card>
      <n-space vertical :size="8">
        <n-text strong>可售库存 ≤ {{ threshold }} 的在售规格</n-text>
        <n-text depth="3" style="font-size: 13px">
          可售库存 = 实际库存 − 下单未支付锁定的库存。阈值在字典
          <n-text code>stock_warn_threshold</n-text> 里配置。
        </n-text>
      </n-space>
    </n-card>

    <n-card>
      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: StockWarnView) => row.skuId"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>
  </n-space>
</template>

<script setup lang="ts">
import { NTag, NText } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'

import { pageStockWarns } from '@/api/stockWarn'
import type { StockWarnView } from '@/types/mall'
import type { DataTableColumns } from 'naive-ui'

const loading = ref(false)
const rows = ref<StockWarnView[]>([])
const total = ref(0)
const threshold = ref(0)
const pageNo = ref(1)
const pageSize = 10

/** 可售为 0 是最严重的一档,单独标红;其余用警示色 */
function stockTag(row: StockWarnView): ReturnType<typeof h> {
  const soldOut = row.availableStock <= 0
  return h(
    NTag,
    { size: 'small', type: soldOut ? 'error' : 'warning', bordered: false },
    { default: () => (soldOut ? '已售罄' : `可售 ${row.availableStock}`) },
  )
}

const columns: DataTableColumns<StockWarnView> = [
  {
    title: '商品',
    key: 'goodsName',
    render: (row) =>
      h('div', { style: 'display:flex;align-items:center;gap:8px' }, [
        row.mainImage
          ? h('img', { src: row.mainImage, style: 'width:36px;height:36px;object-fit:cover;border-radius:4px' })
          : null,
        h('span', null, row.goodsName ?? '-'),
      ]),
  },
  { title: '规格', key: 'skuName', width: 160 },
  { title: 'SKU 编码', key: 'skuCode', width: 160 },
  { title: '实际库存', key: 'stock', width: 100 },
  { title: '锁定', key: 'lockedStock', width: 90 },
  { title: '预警', key: 'availableStock', width: 110, render: stockTag },
]

const pagination = computed(() => ({
  page: pageNo.value,
  pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

async function load(): Promise<void> {
  loading.value = true
  try {
    const report = await pageStockWarns(pageNo.value, pageSize)
    rows.value = report.page.list
    total.value = report.page.total
    threshold.value = report.threshold
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number): void {
  pageNo.value = page
  void load()
}

onMounted(load)
</script>
