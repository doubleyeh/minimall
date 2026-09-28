<template>
  <n-space vertical :size="16">
    <n-alert type="info">
      定时任务没有用户点按钮:任务挂了、或者某个任务从某个时刻起不再执行,<b>没有任何人会发现</b>。
      上面那张总览表就是为这件事准备的 —— 看每个任务的"最后执行",时间明显偏旧的就是停了。
      只记成功与失败,不记"未抢到锁"(多实例下它每个周期都会产生,记了就是纯噪声);
      失败同时会打一条 ERROR 日志,日志侧可以直接按它告警。
    </n-alert>

    <n-card title="任务总览">
      <n-data-table
        size="small"
        :columns="summaryColumns"
        :data="summaries"
        :loading="summaryLoading"
        :row-key="(row: TaskSummaryView) => row.taskName"
        :pagination="false"
      />
    </n-card>

    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="任务">
          <n-select
            v-model:value="query.taskName"
            :options="taskOptions"
            clearable
            placeholder="全部"
            style="width: 220px"
          />
        </n-form-item>
        <n-form-item label="结果">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 110px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="load(1)">查询</n-button>
            <n-button @click="onResetQuery">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: TaskRunLogView) => row.id"
        remote
        @update:page="load"
      />
    </n-card>
  </n-space>
</template>

<script setup lang="ts">
import { NTag } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import { pageTaskRunLogs, taskRunSummaries } from '@/api/taskLog'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import type { TaskRunLogView, TaskSummaryView } from '@/types/system'

const loading = ref(false)
const summaryLoading = ref(false)
const rows = ref<TaskRunLogView[]>([])
const summaries = ref<TaskSummaryView[]>([])

const query = reactive<{ taskName: string | null; status: number | null; pageNo: number; pageSize: number }>({
  taskName: null,
  status: null,
  pageNo: 1,
  pageSize: 20,
})

const statusOptions: SelectOption[] = [
  { label: '成功', value: 1 },
  { label: '失败', value: 2 },
]

/** 任务名下拉来自总览 —— 这样"有哪些任务"和"每个任务跑得怎么样"永远是同一份数据 */
const taskOptions = computed<SelectOption[]>(() =>
  summaries.value.map((item) => ({ label: item.taskName, value: item.taskName })),
)

const pagination = reactive({ page: 1, pageSize: 20, itemCount: 0, showSizePicker: false })

function statusTag(status: number, text: string) {
  return h(
    NTag,
    { size: 'small', type: status === 2 ? 'error' : 'success', bordered: false },
    { default: () => text },
  )
}

const summaryColumns: DataTableColumns<TaskSummaryView> = [
  { title: '任务', key: 'taskName', minWidth: 200 },
  { title: '历史记录', key: 'totalRuns', width: 100 },
  {
    title: '最后执行',
    key: 'lastStartTime',
    width: 180,
    render: (row) => row.lastStartTime || '从未执行',
  },
  {
    title: '最后结果',
    key: 'lastStatusText',
    width: 100,
    render: (row) =>
      row.lastStatus == null ? '—' : statusTag(row.lastStatus, row.lastStatusText || '未知'),
  },
  {
    title: '耗时',
    key: 'lastDurationMs',
    width: 110,
    render: (row) => (row.lastDurationMs == null ? '—' : `${row.lastDurationMs} ms`),
  },
]

const columns: DataTableColumns<TaskRunLogView> = [
  { title: '任务', key: 'taskName', width: 200 },
  {
    title: '结果',
    key: 'statusText',
    width: 90,
    render: (row) => statusTag(row.status, row.statusText),
  },
  { title: '开始时间', key: 'startTime', width: 180 },
  { title: '耗时', key: 'durationMs', width: 100, render: (row) => `${row.durationMs} ms` },
  {
    title: '错误摘要',
    key: 'errorMsg',
    // 单独给一列而不是塞进详情:失败的记录一眼要能看到"错在哪",
    // 点开才看得到的话,总览就失去意义了
    render: (row) => row.errorMsg || '—',
  },
]

onMounted(() => {
  void loadSummaries()
  void load()
})

async function loadSummaries(): Promise<void> {
  summaryLoading.value = true
  try {
    summaries.value = await taskRunSummaries()
  } finally {
    summaryLoading.value = false
  }
}

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageTaskRunLogs({
      taskName: query.taskName || undefined,
      status: query.status,
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
  query.taskName = null
  query.status = null
  void load(1)
}
</script>
