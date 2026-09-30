<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline label-placement="left">
        <n-form-item label="统计区间">
          <n-date-picker v-model:value="timeRange" type="datetimerange" clearable style="width: 360px" />
        </n-form-item>
        <n-form-item>
          <n-button type="primary" :loading="loading" @click="load">查询</n-button>
        </n-form-item>
      </n-form>

      <n-text depth="3" style="font-size: 13px">
        销售额按下单时间落在区间内、且已支付的订单实付额统计(后来退款过的也算,钱确实收过);
        退款额按退款成功时间统计。两者不是同一批订单,净额只用于看量级。
      </n-text>
    </n-card>

    <n-grid :cols="5" :x-gap="12">
      <n-gi>
        <n-card size="small">
          <n-statistic label="已支付订单" :value="summary.orderCount" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card size="small">
          <n-statistic label="实付总额" :value="money(summary.paidAmount)" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card size="small">
          <n-statistic label="退款额" :value="money(summary.refundAmount)" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card size="small">
          <n-statistic label="净额" :value="money(summary.netAmount)" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card size="small">
          <n-statistic label="客单价" :value="money(summary.avgOrderAmount)" />
        </n-card>
      </n-gi>
    </n-grid>

    <n-card title="商品排行(按下单金额前 10)">
      <n-data-table
        :columns="columns"
        :data="topGoods"
        :row-key="(row: TopGoodsView) => row.goodsId"
        :bordered="false"
      />
    </n-card>
  </n-space>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { salesStatReport } from '@/api/salesStat'
import { toLocalDateTime } from '@/utils/datetime'
import type { SalesSummaryView, TopGoodsView } from '@/types/mall'
import type { DataTableColumns } from 'naive-ui'

/** 默认看近 30 天:区间必填,不给默认值的话第一次进来是空页面 */
const DAY_MS = 24 * 60 * 60 * 1000
const timeRange = ref<[number, number] | null>([Date.now() - 30 * DAY_MS, Date.now()])
const loading = ref(false)
const topGoods = ref<TopGoodsView[]>([])
const summary = ref<SalesSummaryView>({
  orderCount: 0,
  paidAmount: 0,
  refundAmount: 0,
  netAmount: 0,
  avgOrderAmount: 0,
})

function money(value: number): string {
  return `¥${Number(value ?? 0).toFixed(2)}`
}

const columns: DataTableColumns<TopGoodsView> = [
  {
    title: '排名',
    key: 'rank',
    width: 80,
    render: (_row, index) => index + 1,
  },
  { title: '商品', key: 'goodsName', render: (row) => row.goodsName ?? '-' },
  { title: '销量', key: 'quantity', width: 120 },
  { title: '金额', key: 'amount', width: 140, render: (row) => money(row.amount) },
]

async function load(): Promise<void> {
  if (!timeRange.value) {
    return
  }
  loading.value = true
  try {
    const report = await salesStatReport(toLocalDateTime(timeRange.value[0]), toLocalDateTime(timeRange.value[1]))
    summary.value = report.summary
    topGoods.value = report.topGoods
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
