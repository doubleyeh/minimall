<template>
  <n-space vertical :size="16">
    <n-alert type="info">
      菜单是平台级数据,只有平台超管能维护。标为「平台专用」的菜单不会进入任何套餐,
      因此普通租户的授权候选集里永远看不到它。
    </n-alert>

    <n-card>
      <template #header>
        <n-space justify="space-between" align="center">
          <span>菜单树</span>
          <n-space>
            <n-select v-model:value="filterStatus" :options="statusOptions" clearable placeholder="状态" style="width: 120px" @update:value="load" />
            <n-button @click="load">刷新</n-button>
            <n-button v-perm="'system:menu:create'" type="primary" @click="openCreate(null)">新增菜单</n-button>
          </n-space>
        </n-space>
      </template>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: MenuTreeNode) => row.id"
        :default-expand-all="false"
      />
    </n-card>

    <n-modal v-model:show="formVisible" preset="card" :title="editing ? '编辑菜单' : '新增菜单'" style="width: 560px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="上级菜单" path="parentId">
          <n-tree-select v-model:value="form.parentId" :options="parentOptions" placeholder="顶级菜单请选择「根菜单」" />
        </n-form-item>
        <n-form-item label="菜单类型" path="menuType">
          <n-radio-group v-model:value="form.menuType">
            <n-radio :value="1">目录</n-radio>
            <n-radio :value="2">页面</n-radio>
            <n-radio :value="3">按钮</n-radio>
          </n-radio-group>
        </n-form-item>
        <n-form-item label="菜单名称" path="menuName"><n-input v-model:value="form.menuName" /></n-form-item>
        <n-form-item v-if="form.menuType !== 3" label="路由地址" path="routePath">
          <n-input v-model:value="form.routePath" placeholder="目录用 /system,页面用 user 这样的片段" />
        </n-form-item>
        <n-form-item v-if="form.menuType === 3" label="权限标识" path="permCode">
          <n-input v-model:value="form.permCode" placeholder="模块:资源:操作,如 system:user:list" />
        </n-form-item>
        <n-form-item label="排序" path="sortOrder"><n-input-number v-model:value="form.sortOrder" :min="0" /></n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="form.status">
            <n-radio :value="1">启用</n-radio>
            <n-radio :value="0">禁用</n-radio>
          </n-radio-group>
        </n-form-item>
        <n-form-item label="平台专用">
          <n-switch v-model:value="form.isPlatform" />
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

import { createMenu, deleteMenu, menuTree, updateMenu } from '@/api/menu'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { DataTableColumns, FormInst, FormRules, SelectOption, TreeSelectOption } from 'naive-ui'
import type { MenuSaveRequest, MenuTreeNode } from '@/types/system'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<MenuTreeNode[]>([])
const parentOptions = ref<TreeSelectOption[]>([])
const filterStatus = ref<number | null>(null)

const ROOT_KEY = '0'
const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]
const typeLabel: Record<number, string> = { 1: '目录', 2: '页面', 3: '按钮' }

/** 只有目录/页面能作为上级(按钮不能有子节点) */
function toParentOptions(nodes: MenuTreeNode[]): TreeSelectOption[] {
  return nodes
    .filter((node) => node.menuType !== 3)
    .map((node) => ({
      key: node.id,
      label: node.menuName,
      children: node.children?.length ? toParentOptions(node.children) : undefined,
    }))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await menuTree(filterStatus.value)
    parentOptions.value = [{ key: ROOT_KEY, label: '根菜单' }, ...toParentOptions(rows.value)]
  } finally {
    loading.value = false
  }
}

const columns: DataTableColumns<MenuTreeNode> = [
  { title: '菜单名称', key: 'menuName' },
  { title: '类型', key: 'menuType', width: 90, render: (row) => typeLabel[row.menuType] ?? '-' },
  { title: '路由地址', key: 'routePath', width: 180, render: (row) => row.routePath ?? '-' },
  { title: '权限标识', key: 'permCode', width: 200, render: (row) => row.permCode ?? '-' },
  { title: '排序', key: 'sortOrder', width: 80 },
  {
    title: '状态',
    key: 'status',
    width: 90,
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
        permission.hasPerm('system:menu:create') && row.menuType !== 3
          ? h(NButton, { size: 'tiny', onClick: () => openCreate(row.id) }, { default: () => '新增下级' })
          : null,
        permission.hasPerm('system:menu:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:menu:delete')
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
const form = reactive<{
  parentId: Id
  menuName: string
  menuType: number
  routePath: string
  permCode: string
  sortOrder: number
  status: number
  isPlatform: boolean
}>({
  parentId: ROOT_KEY,
  menuName: '',
  menuType: 2,
  routePath: '',
  permCode: '',
  sortOrder: 1,
  status: 1,
  isPlatform: false,
})

const formRules: FormRules = {
  parentId: [{ required: true, message: '请选择上级菜单', trigger: ['change'] }],
  menuName: [{ required: true, message: '请输入菜单名称', trigger: ['input', 'blur'] }],
  menuType: [{ required: true, message: '请选择菜单类型', trigger: ['change'] }],
}

function openCreate(parentId: Id | null): void {
  editing.value = false
  editingId.value = null
  Object.assign(form, {
    parentId: parentId ?? ROOT_KEY,
    menuName: '',
    menuType: 2,
    routePath: '',
    permCode: '',
    sortOrder: 1,
    status: 1,
    isPlatform: false,
  })
  formVisible.value = true
}

function openEdit(row: MenuTreeNode): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    parentId: row.parentId === '0' ? ROOT_KEY : row.parentId,
    menuName: row.menuName,
    menuType: row.menuType,
    routePath: row.routePath ?? '',
    permCode: row.permCode ?? '',
    sortOrder: row.sortOrder,
    status: row.status,
    isPlatform: false,
  })
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (form.menuType === 3 && !form.permCode) {
    message.warning('按钮类型必须填写权限标识')
    return
  }
  if (form.menuType !== 3 && !form.routePath) {
    message.warning('目录/页面必须填写路由地址')
    return
  }
  const payload: MenuSaveRequest = {
    parentId: form.parentId,
    menuName: form.menuName,
    menuType: form.menuType,
    routePath: form.menuType === 3 ? null : form.routePath,
    permCode: form.menuType === 3 ? form.permCode : null,
    sortOrder: form.sortOrder,
    status: form.status,
    isPlatform: form.isPlatform,
  }
  submitting.value = true
  try {
    if (editing.value && editingId.value) {
      await updateMenu(editingId.value, payload)
    } else {
      await createMenu(payload)
    }
    message.success('已保存')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function onDelete(row: MenuTreeNode): void {
  dialog.error({
    title: '删除菜单',
    content: `确认删除菜单「${row.menuName}」?它在各角色/套餐里的授权会被一并清理,并对全部租户失效权限缓存。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteMenu(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(load)
</script>
