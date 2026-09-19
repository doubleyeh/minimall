<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="商品 ID">
          <n-input v-model:value="query.goodsId" clearable placeholder="按商品筛选" style="width: 200px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 140px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="search">查询</n-button>
            <n-button @click="resetQuery">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: ReviewView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <n-modal v-model:show="replyVisible" preset="card" title="回复评价" style="width: 520px">
      <n-input
        :value="replyContent"
        type="textarea"
        :rows="4"
        placeholder="回复会展示在评价下方,买家可见"
        @update:value="(value: string) => (replyContent = value)"
      />
      <template #footer>
        <n-space justify="end">
          <n-button @click="replyVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onReply">回复</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NRate, NTag, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import { changeReviewStatus, pageReviews, replyReview } from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type { ReviewView } from '@/types/mall'
import type { DataTableColumns, SelectOption } from 'naive-ui'

const message = useMessage()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<ReviewView[]>([])
const total = ref(0)

const query = reactive<{ goodsId?: string; status?: number | null; pageNo: number; pageSize: number }>({
  goodsId: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '展示中', value: 1 },
  { label: '已隐藏', value: 0 },
]

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

const columns: DataTableColumns<ReviewView> = [
  { title: '商品', key: 'goodsName', width: 160, render: (row) => row.goodsName ?? '-' },
  { title: '评价人', key: 'customerNickname', width: 120, render: (row) => row.customerNickname ?? '-' },
  {
    title: '评分',
    key: 'rating',
    width: 140,
    render: (row) => h(NRate, { readonly: true, size: 'small', value: row.rating }),
  },
  { title: '评价内容', key: 'content', render: (row) => row.content ?? '-' },
  { title: '商家回复', key: 'replyContent', width: 180, render: (row) => row.replyContent ?? '-' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '展示中' : '已隐藏') },
      ),
  },
  { title: '时间', key: 'createTime', width: 170 },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('mall:review:reply')
          ? h(NButton, { size: 'tiny', onClick: () => openReply(row) }, { default: () => '回复' })
          : null,
        permission.hasPerm('mall:review:status')
          ? h(
              NButton,
              { size: 'tiny', type: row.status === 1 ? 'error' : 'primary', onClick: () => toggleStatus(row) },
              { default: () => (row.status === 1 ? '隐藏' : '恢复') },
            )
          : null,
      ]),
  },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result: PageResult<ReviewView> = await pageReviews({
      goodsId: query.goodsId ? query.goodsId : null,
      status: query.status,
      pageNo: query.pageNo,
      pageSize: query.pageSize,
    })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.pageNo = 1
  void load()
}

function resetQuery(): void {
  query.goodsId = ''
  query.status = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void load()
}

const replyVisible = ref(false)
const replyContent = ref('')
const replyingId = ref<Id | null>(null)

function openReply(row: ReviewView): void {
  replyingId.value = row.id
  replyContent.value = row.replyContent ?? ''
  replyVisible.value = true
}

async function onReply(): Promise<void> {
  if (!replyingId.value) return
  if (!replyContent.value.trim()) {
    message.warning('回复内容不能为空')
    return
  }
  submitting.value = true
  try {
    await replyReview(replyingId.value, replyContent.value.trim())
    message.success('已回复')
    replyVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: ReviewView): Promise<void> {
  // 隐藏而不是删除:违规内容下架后仍要能追溯
  await changeReviewStatus(row.id, row.status === 1 ? 0 : 1)
  message.success(row.status === 1 ? '已隐藏' : '已恢复展示')
  await load()
}

onMounted(load)
</script>
