<template>
  <n-space vertical :size="16">
    <n-alert type="info">
      操作日志是审计凭据,只读:不提供修改与删除。超管在这里看到的是全部租户的日志,
      普通账号只会看到自己租户的。
    </n-alert>

    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="模块">
          <n-input v-model:value="query.module" clearable placeholder="模糊匹配,如租户管理" style="width: 170px" />
        </n-form-item>
        <n-form-item label="用户ID">
          <n-input v-model:value="query.userId" clearable placeholder="精确匹配" style="width: 140px" />
        </n-form-item>
        <n-form-item label="结果">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 110px" />
        </n-form-item>
        <n-form-item label="时间">
          <n-date-picker v-model:value="timeRange" type="datetimerange" clearable style="width: 340px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="load(1)">查询</n-button>
            <n-button @click="onResetQuery">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>
    </n-card>

    <n-card>
      <template #header>操作日志</template>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: OperLogView) => row.id"
        remote
        @update:page="load"
      />
    </n-card>

    <n-modal v-model:show="detailVisible" preset="card" title="日志详情" style="width: 780px">
      <n-descriptions v-if="detail" :column="2" label-placement="top" bordered>
        <n-descriptions-item label="时间">{{ detail.createTime }}</n-descriptions-item>
        <n-descriptions-item label="结果">
          <n-tag size="small" :type="detail.status === 1 ? 'success' : 'error'" :bordered="false">
            {{ detail.status === 1 ? '成功' : '失败' }}
          </n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="租户ID">{{ detail.tenantId ?? '-' }}</n-descriptions-item>
        <n-descriptions-item label="用户ID">{{ detail.userId ?? '-' }}</n-descriptions-item>
        <n-descriptions-item label="模块">{{ detail.module }}</n-descriptions-item>
        <n-descriptions-item label="权限码">{{ detail.permCode ?? '-' }}</n-descriptions-item>
        <n-descriptions-item label="IP">{{ detail.ip ?? '-' }}</n-descriptions-item>
        <n-descriptions-item label="traceId">{{ detail.traceId ?? '-' }}</n-descriptions-item>
        <n-descriptions-item label="方法" :span="2">{{ detail.method }}</n-descriptions-item>
        <n-descriptions-item label="请求参数" :span="2">
          <pre class="oper-log__pre">{{ detail.requestParams || '(无)' }}</pre>
        </n-descriptions-item>
        <n-descriptions-item v-if="detail.errorMsg" label="错误信息" :span="2">
          <pre class="oper-log__pre">{{ detail.errorMsg }}</pre>
        </n-descriptions-item>
      </n-descriptions>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import { pageOperLogs } from '@/api/operLog'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import type { OperLogView } from '@/types/system'
import { toLocalDateTime } from '@/utils/datetime'

const loading = ref(false)
const rows = ref<OperLogView[]>([])
const timeRange = ref<[number, number] | null>(null)

const query = reactive<{ module: string; userId: string; status: number | null; pageNo: number; pageSize: number }>({
  module: '',
  userId: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '成功', value: 1 },
  { label: '失败', value: 0 },
]

const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0, showSizePicker: false })

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageOperLogs({
      module: query.module || undefined,
      userId: query.userId || undefined,
      status: query.status,
      startTime: timeRange.value ? toLocalDateTime(timeRange.value[0]) : undefined,
      endTime: timeRange.value ? toLocalDateTime(timeRange.value[1]) : undefined,
      pageNo: page,
      pageSize: query.pageSize,
    })
    rows.value = result.list
    pagination.page = page
    pagination.itemCount = result.total
  } finally {
    loading.value = false
  }
}

function onResetQuery(): void {
  query.module = ''
  query.userId = ''
  query.status = null
  timeRange.value = null
  void load(1)
}

const detailVisible = ref(false)
const detail = ref<OperLogView | null>(null)

function openDetail(row: OperLogView): void {
  detail.value = row
  detailVisible.value = true
}

const columns: DataTableColumns<OperLogView> = [
  { title: '时间', key: 'createTime', width: 170 },
  { title: '租户ID', key: 'tenantId', width: 130, render: (row) => row.tenantId ?? '-' },
  { title: '用户ID', key: 'userId', width: 130, render: (row) => row.userId ?? '-' },
  { title: '模块', key: 'module', width: 140 },
  { title: '权限码', key: 'permCode', width: 180, render: (row) => row.permCode ?? '-' },
  {
    title: '结果',
    key: 'status',
    width: 90,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'error', bordered: false },
        { default: () => (row.status === 1 ? '成功' : '失败') },
      ),
  },
  { title: 'IP', key: 'ip', width: 140, render: (row) => row.ip ?? '-' },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: (row) => h(NButton, { size: 'tiny', onClick: () => openDetail(row) }, { default: () => '详情' }),
  },
]

onMounted(() => {
  void load(1)
})
</script>

<style scoped>
.oper-log__pre {
  margin: 0;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
}
</style>
