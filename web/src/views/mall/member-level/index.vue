<template>
  <n-space vertical :size="16">
    <n-card>
      <template #header>
        <n-space justify="space-between" align="center">
          <span>会员等级</span>
          <n-space>
            <n-button @click="load">刷新</n-button>
            <n-button v-perm="'mall:member-level:create'" type="primary" @click="openCreate">新增等级</n-button>
          </n-space>
        </n-space>
      </template>

      <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
        本期只维护等级定义:成长值晋升与等级折扣的计算规则尚未确定,因此这里的折扣率只保存、不参与结算。
      </n-alert>

      <n-data-table
        max-height="var(--mm-table-max-h)" :columns="columns" :data="rows" :loading="loading" :row-key="(row: MemberLevelView) => row.id" />
    </n-card>

    <n-modal v-model:show="formVisible" preset="card" :title="editingId ? '编辑等级' : '新增等级'" style="width: 480px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="等级名称" path="levelName"><n-input v-model:value="form.levelName" /></n-form-item>
        <n-form-item label="等级顺序" path="levelSort">
          <n-input-number v-model:value="form.levelSort" :min="0" />
        </n-form-item>
        <n-form-item label="成长值门槛" path="growthThreshold">
          <n-input-number v-model:value="form.growthThreshold" :min="0" />
        </n-form-item>
        <n-form-item label="折扣率">
          <n-input-number v-model:value="form.discountRate" :min="0.01" :max="1" :step="0.01" placeholder="如 0.95" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="form.status">
            <n-radio :value="1">启用</n-radio>
            <n-radio :value="0">停用</n-radio>
          </n-radio-group>
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
import { h, onMounted, reactive, ref } from 'vue'

import { createMemberLevel, listMemberLevels, updateMemberLevel } from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { MemberLevelSaveRequest, MemberLevelView } from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'

const message = useMessage()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<MemberLevelView[]>([])

const formVisible = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const form = reactive<MemberLevelSaveRequest>({
  levelName: '',
  levelSort: 0,
  growthThreshold: 0,
  discountRate: null,
  status: 1,
})

const formRules: FormRules = {
  levelName: { required: true, message: '请输入等级名称', trigger: ['blur', 'input'] },
}

const columns: DataTableColumns<MemberLevelView> = [
  { title: '等级名称', key: 'levelName' },
  { title: '顺序', key: 'levelSort', width: 90 },
  { title: '成长值门槛', key: 'growthThreshold', width: 130 },
  {
    title: '折扣率',
    key: 'discountRate',
    width: 110,
    render: (row) => (row.discountRate == null ? '-' : `${Number(row.discountRate) * 10} 折`),
  },
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
    width: 100,
    render: (row) =>
      permission.hasPerm('mall:member-level:update')
        ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
        : null,
  },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listMemberLevels()
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  form.levelName = ''
  form.levelSort = 0
  form.growthThreshold = 0
  form.discountRate = null
  form.status = 1
  formVisible.value = true
}

function openEdit(row: MemberLevelView): void {
  editingId.value = row.id
  form.levelName = row.levelName
  form.levelSort = row.levelSort
  form.growthThreshold = row.growthThreshold
  form.discountRate = row.discountRate == null ? null : Number(row.discountRate)
  form.status = row.status
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  submitting.value = true
  try {
    const payload: MemberLevelSaveRequest = { ...form }
    if (editingId.value == null) {
      await createMemberLevel(payload)
    } else {
      await updateMemberLevel(editingId.value, payload)
    }
    message.success('保存成功')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>
