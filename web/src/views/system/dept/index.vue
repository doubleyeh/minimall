<template>
  <n-space vertical :size="16">
    <n-card>
      <template #header>
        <n-space justify="space-between" align="center">
          <span>部门树</span>
          <n-space>
            <n-button @click="load">刷新</n-button>
            <n-button v-perm="'system:dept:create'" type="primary" @click="openCreate(null)">新增部门</n-button>
          </n-space>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: DeptTreeNode) => row.id"
        :default-expand-all="true"
      />
    </n-card>

    <n-modal v-model:show="formVisible" preset="card" :title="editing ? '编辑部门' : '新增部门'" style="width: 520px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="上级部门" path="parentId">
          <n-tree-select
            v-model:value="form.parentId"
            :options="parentOptions"
            placeholder="顶级部门请选择「根部门」"
          />
        </n-form-item>
        <n-form-item label="部门名称" path="deptName"><n-input v-model:value="form.deptName" /></n-form-item>
        <n-form-item label="排序" path="sortOrder"><n-input-number v-model:value="form.sortOrder" :min="0" /></n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="form.status">
            <n-radio :value="1">启用</n-radio>
            <n-radio :value="0">禁用</n-radio>
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
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import { createDept, deleteDept, deptTree, updateDept } from '@/api/dept'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { DataTableColumns, FormInst, FormRules, TreeSelectOption } from 'naive-ui'
import type { DeptSaveRequest, DeptTreeNode } from '@/types/system'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<DeptTreeNode[]>([])
const parentOptions = ref<TreeSelectOption[]>([])

/** 「根部门」用 0 表示(后端约定 parentId = 0) */
const ROOT_KEY = '0'

function toParentOptions(nodes: DeptTreeNode[]): TreeSelectOption[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.deptName,
    children: node.children?.length ? toParentOptions(node.children) : undefined,
  }))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await deptTree()
    parentOptions.value = [{ key: ROOT_KEY, label: '根部门' }, ...toParentOptions(rows.value)]
  } finally {
    loading.value = false
  }
}

const columns: DataTableColumns<DeptTreeNode> = [
  { title: '部门名称', key: 'deptName' },
  { title: '祖级链', key: 'ancestors', width: 180, render: (row) => row.ancestors || '-' },
  { title: '排序', key: 'sortOrder', width: 90 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '启用' : '禁用') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 240,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('system:dept:create')
          ? h(NButton, { size: 'tiny', onClick: () => openCreate(row.id) }, { default: () => '新增下级' })
          : null,
        permission.hasPerm('system:dept:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:dept:delete')
          ? h(
              NButton,
              { size: 'tiny', type: 'error', ghost: true, onClick: () => onDelete(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

const formVisible = ref(false)
const editing = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const form = reactive<{ parentId: Id; deptName: string; sortOrder: number; status: number }>({
  parentId: ROOT_KEY,
  deptName: '',
  sortOrder: 1,
  status: 1,
})

const formRules: FormRules = {
  parentId: [{ required: true, message: '请选择上级部门', trigger: ['change'] }],
  deptName: [{ required: true, message: '请输入部门名称', trigger: ['input', 'blur'] }],
}

function openCreate(parentId: Id | null): void {
  editing.value = false
  editingId.value = null
  Object.assign(form, { parentId: parentId ?? ROOT_KEY, deptName: '', sortOrder: 1, status: 1 })
  formVisible.value = true
}

function openEdit(row: DeptTreeNode): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    parentId: row.parentId === '0' ? ROOT_KEY : row.parentId,
    deptName: row.deptName,
    sortOrder: row.sortOrder,
    status: row.status,
  })
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const payload: DeptSaveRequest = {
    parentId: form.parentId,
    deptName: form.deptName,
    sortOrder: form.sortOrder,
    status: form.status,
  }
  submitting.value = true
  try {
    if (editing.value && editingId.value) {
      await updateDept(editingId.value, payload)
    } else {
      await createDept(payload)
    }
    message.success('已保存')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function onDelete(row: DeptTreeNode): void {
  dialog.error({
    title: '删除部门',
    content: `确认删除部门「${row.deptName}」?存在子部门或关联用户时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteDept(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(load)
</script>
