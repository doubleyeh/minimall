<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="活动名称">
          <n-input v-model:value="query.activityName" clearable style="width: 180px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 130px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="search">查询</n-button>
            <n-button @click="resetQuery">重置</n-button>
            <n-button v-perm="'mall:promotion:create'" type="primary" ghost @click="openCreate">新增活动</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: PromotionView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <n-modal v-model:show="formVisible" preset="card" :title="editingId ? '编辑满减活动' : '新增满减活动'" style="width: 640px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="活动名称" path="activityName"><n-input v-model:value="form.activityName" /></n-form-item>
        <n-form-item label="减免规则(JSON)" path="reductionRule">
          <n-input
            :value="form.reductionRule"
            type="textarea"
            :rows="3"
            placeholder='[{"amount":100,"reduce":10},{"amount":200,"reduce":30}]'
            @update:value="(value: string) => (form.reductionRule = value)"
          />
        </n-form-item>
        <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
          匹配时按门槛取满足条件的最高档,所以书写顺序不影响结果。保存时后端会试算一次,
          规则写坏会直接拒绝保存(而不是等下单时才发现活动从未生效)。
        </n-alert>
        <n-grid :cols="2" :x-gap="16">
          <n-form-item-gi label="适用范围" path="scopeType">
            <n-select v-model:value="form.scopeType" :options="scopeOptions" />
          </n-form-item-gi>
          <n-form-item-gi label="状态">
            <n-radio-group v-model:value="form.status">
              <n-radio :value="1">启用</n-radio>
              <n-radio :value="0">停用</n-radio>
            </n-radio-group>
          </n-form-item-gi>
        </n-grid>
        <n-form-item v-if="form.scopeType !== 1" label="范围 ID(逗号分隔,分类 ID 或商品 ID)">
          <n-input
            :value="(form.scopeIds ?? []).join(',')"
            placeholder="如 1001,1002"
            @update:value="(value: string) => (form.scopeIds = splitIds(value))"
          />
        </n-form-item>
        <n-form-item label="活动时间" path="validStartTime">
          <n-date-picker v-model:value="validRange" type="datetimerange" clearable style="width: 100%" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onSubmit">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import { changePromotionStatus, createPromotion, pagePromotions, updatePromotion } from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type { PromotionSaveRequest, PromotionView } from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui'

const message = useMessage()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<PromotionView[]>([])
const total = ref(0)

const query = reactive<{ activityName?: string; status?: number | null; pageNo: number; pageSize: number }>({
  activityName: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

const scopeOptions: SelectOption[] = [
  { label: '全部商品', value: 1 },
  { label: '指定分类', value: 2 },
  { label: '指定商品', value: 3 },
]

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

const columns: DataTableColumns<PromotionView> = [
  { title: '活动名称', key: 'activityName', width: 180 },
  { title: '规则', key: 'reductionRule', render: (row) => row.reductionRule },
  {
    title: '范围',
    key: 'scopeType',
    width: 130,
    render: (row) =>
      row.scopeType === 1 ? '全部商品' : `${row.scopeType === 2 ? '分类' : '商品'} ${row.scopeIds?.length ?? 0} 个`,
  },
  { title: '开始', key: 'validStartTime', width: 170 },
  { title: '结束', key: 'validEndTime', width: 170 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '启用' : '停用') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('mall:promotion:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('mall:promotion:update')
          ? h(
              NButton,
              { size: 'tiny', type: row.status === 1 ? 'error' : 'primary', onClick: () => toggleStatus(row) },
              { default: () => (row.status === 1 ? '停用' : '启用') },
            )
          : null,
      ]),
  },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result: PageResult<PromotionView> = await pagePromotions({ ...query })
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
  query.activityName = ''
  query.status = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void load()
}

const formVisible = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const validRange = ref<[number, number] | null>(null)
const form = reactive<PromotionSaveRequest>({
  activityName: '',
  reductionRule: '[{"amount":100,"reduce":10}]',
  scopeType: 1,
  scopeIds: [],
  validStartTime: '',
  validEndTime: '',
  status: 1,
})

const formRules: FormRules = {
  activityName: { required: true, message: '请输入活动名称', trigger: ['blur', 'input'] },
  reductionRule: { required: true, message: '请填写减免规则', trigger: ['blur', 'input'] },
}

function splitIds(value: string): Id[] {
  return value
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function toLocalDateTime(timestamp: number): string {
  const date = new Date(timestamp)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function openCreate(): void {
  editingId.value = null
  form.activityName = ''
  form.reductionRule = '[{"amount":100,"reduce":10}]'
  form.scopeType = 1
  form.scopeIds = []
  form.status = 1
  validRange.value = null
  formVisible.value = true
}

function openEdit(row: PromotionView): void {
  editingId.value = row.id
  form.activityName = row.activityName
  form.reductionRule = row.reductionRule
  form.scopeType = row.scopeType
  form.scopeIds = row.scopeIds ?? []
  form.status = row.status
  validRange.value = [new Date(row.validStartTime).getTime(), new Date(row.validEndTime).getTime()]
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  if (!validRange.value) {
    message.warning('请选择活动时间')
    return
  }
  if (form.scopeType !== 1 && (form.scopeIds ?? []).length === 0) {
    message.warning('指定分类/商品时至少需要选择一个范围')
    return
  }
  submitting.value = true
  try {
    const payload: PromotionSaveRequest = {
      ...form,
      scopeIds: form.scopeType === 1 ? [] : (form.scopeIds ?? []),
      validStartTime: toLocalDateTime(validRange.value[0]),
      validEndTime: toLocalDateTime(validRange.value[1]),
    }
    if (editingId.value == null) {
      await createPromotion(payload)
    } else {
      await updatePromotion(editingId.value, payload)
    }
    message.success('保存成功')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: PromotionView): Promise<void> {
  await changePromotionStatus(row.id, row.status === 1 ? 0 : 1)
  message.success(row.status === 1 ? '已停用' : '已启用')
  await load()
}

onMounted(load)
</script>
