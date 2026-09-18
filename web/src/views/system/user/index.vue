<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="用户名">
          <n-input v-model:value="query.username" clearable placeholder="模糊匹配" style="width: 160px" />
        </n-form-item>
        <n-form-item label="部门">
          <n-tree-select
            v-model:value="query.deptId"
            :options="deptOptions"
            clearable
            placeholder="全部部门"
            style="width: 200px"
          />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 120px" />
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
      <template #header>
        <n-space justify="space-between" align="center">
          <span>用户列表</span>
          <n-button v-perm="'system:user:create'" type="primary" @click="openCreate">新增用户</n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: UserView) => row.id"
        remote
        @update:page="load"
      />
    </n-card>

    <!-- 新增 / 编辑 -->
    <n-modal v-model:show="formVisible" preset="card" :title="editing ? '编辑用户' : '新增用户'" style="width: 560px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="用户名" path="username">
          <n-input v-model:value="form.username" :disabled="editing" placeholder="3-64 位字母/数字/下划线,字母开头" />
        </n-form-item>
        <n-form-item v-if="!editing" label="初始密码" path="password">
          <n-input v-model:value="form.password" placeholder="留空则由系统随机生成并返回一次" />
        </n-form-item>
        <n-form-item label="昵称"><n-input v-model:value="form.nickname" /></n-form-item>
        <n-form-item label="手机号"><n-input v-model:value="form.phone" /></n-form-item>
        <n-form-item label="部门">
          <n-tree-select v-model:value="form.deptId" :options="deptOptions" clearable placeholder="可留空" />
        </n-form-item>
        <n-form-item label="角色">
          <n-select v-model:value="form.roleIds" :options="roleOptions" multiple clearable placeholder="可多选" />
        </n-form-item>
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

    <!-- 一次性明文密码:只在本次响应里出现,必须提示立即保存(前端文档 6.3) -->
    <n-modal v-model:show="secretVisible" preset="card" title="初始密码" style="width: 460px">
      <n-alert type="warning" :show-icon="true">
        这个密码只显示这一次,关闭后无法再查看。请立即复制并交给使用者,对方首次登录会被要求改密。
      </n-alert>
      <n-space align="center" :size="8" style="margin-top: 12px">
        <n-input :value="secret" readonly />
        <n-button @click="copySecret">复制</n-button>
      </n-space>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import { deptTree } from '@/api/dept'
import { pageRoles } from '@/api/role'
import {
  changeUserStatus,
  createUser,
  deleteUser,
  pageUsers,
  resetUserPassword,
  unlockUser,
  updateUser,
} from '@/api/user'
import type { Id } from '@/types/api'
import type { DataTableColumns, FormInst, FormRules, SelectOption, TreeSelectOption } from 'naive-ui'
import type { DeptTreeNode, RoleView, UserSaveRequest, UserView } from '@/types/system'
import { usePermissionStore } from '@/stores/permission'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<UserView[]>([])
const total = ref(0)
const deptOptions = ref<TreeSelectOption[]>([])
const roleOptions = ref<SelectOption[]>([])

const query = reactive<{ username: string; deptId: Id | null; status: number | null; pageNo: number; pageSize: number }>({
  username: '',
  deptId: null,
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]

const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: false,
  prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
})

function toDeptOptions(nodes: DeptTreeNode[]): TreeSelectOption[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.deptName,
    children: node.children?.length ? toDeptOptions(node.children) : undefined,
  }))
}

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageUsers({
      username: query.username || undefined,
      deptId: query.deptId,
      status: query.status,
      pageNo: query.pageNo,
      pageSize: query.pageSize,
    })
    rows.value = result.list
    total.value = result.total
    pagination.page = query.pageNo
    pagination.itemCount = result.total
  } finally {
    loading.value = false
  }
}

function onResetQuery(): void {
  query.username = ''
  query.deptId = null
  query.status = null
  void load(1)
}

// ——— 表格列 ———

const columns: DataTableColumns<UserView> = [
  { title: '用户名', key: 'username', width: 160 },
  { title: '昵称', key: 'nickname', width: 140 },
  { title: '部门', key: 'deptName', width: 140, render: (row) => row.deptName ?? '-' },
  { title: '手机号', key: 'phone', width: 140, render: (row) => row.phone ?? '-' },
  {
    title: '状态',
    key: 'status',
    width: 120,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.lockTime ? 'error' : row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.lockTime ? '已锁定' : row.status === 1 ? '启用' : '禁用') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 320,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        permission.hasPerm('system:user:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:user:status')
          ? h(
              NButton,
              { size: 'tiny', onClick: () => onToggleStatus(row) },
              { default: () => (row.status === 1 ? '禁用' : '启用') },
            )
          : null,
        row.lockTime && permission.hasPerm('system:user:unlock')
          ? h(NButton, { size: 'tiny', onClick: () => onUnlock(row) }, { default: () => '解锁' })
          : null,
        permission.hasPerm('system:user:reset-password')
          ? h(NButton, { size: 'tiny', onClick: () => onResetPassword(row) }, { default: () => '重置密码' })
          : null,
        permission.hasPerm('system:user:delete')
          ? h(
              NButton,
              { size: 'tiny', type: 'error', ghost: true, onClick: () => onDelete(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

// ——— 新增 / 编辑 ———

const formVisible = ref(false)
const editing = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const form = reactive<UserSaveRequest & { roleIds: Id[]; status: number }>({
  username: '',
  password: '',
  nickname: '',
  phone: '',
  deptId: null,
  roleIds: [],
  status: 1,
})

const formRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: ['input', 'blur'] },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_]{2,63}$/,
      message: '3-64 位字母/数字/下划线,且以字母开头',
      trigger: ['input', 'blur'],
    },
  ],
}

function openCreate(): void {
  editing.value = false
  editingId.value = null
  Object.assign(form, { username: '', password: '', nickname: '', phone: '', deptId: null, roleIds: [], status: 1 })
  formVisible.value = true
}

function openEdit(row: UserView): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    password: '',
    nickname: row.nickname,
    phone: row.phone,
    deptId: row.deptId,
    roleIds: [],
    status: row.status,
  })
  formVisible.value = true
  // 列表不返回 roleIds(后端不暴露),编辑时角色以"不改动"为准:留空即提交空数组会让后端清空角色,
  // 所以这里显式提示使用者重新选择,避免静默清空权限
  message.info('如需调整角色,请重新选择;不改动角色时请保持为空并保存其它字段')
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload: UserSaveRequest = {
      username: form.username,
      nickname: form.nickname,
      phone: form.phone,
      deptId: form.deptId,
      status: form.status,
      roleIds: form.roleIds.length ? form.roleIds : undefined,
    }
    if (editing.value && editingId.value) {
      await updateUser(editingId.value, payload)
      message.success('已保存')
    } else {
      const created = await createUser({ ...payload, password: form.password || undefined })
      if (created.initialPassword) {
        showSecret(created.initialPassword)
      } else {
        message.success('已创建')
      }
    }
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// ——— 一次性密码 ———

const secretVisible = ref(false)
const secret = ref('')

function showSecret(value: string): void {
  secret.value = value
  secretVisible.value = true
}

async function copySecret(): Promise<void> {
  await navigator.clipboard.writeText(secret.value)
  message.success('已复制')
}

// ——— 行内操作 ———

async function onToggleStatus(row: UserView): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    dialog.warning({
      title: '禁用用户',
      content: '禁用后该用户的在线会话会在下一次请求失效,确认继续?',
      positiveText: '确认禁用',
      negativeText: '取消',
      onPositiveClick: async () => {
        await changeUserStatus(row.id, 0)
        message.success('已禁用')
        await load()
      },
    })
    return
  }
  await changeUserStatus(row.id, 1)
  message.success('已启用')
  await load()
}

async function onUnlock(row: UserView): Promise<void> {
  await unlockUser(row.id)
  message.success('已解除锁定')
  await load()
}

async function onResetPassword(row: UserView): Promise<void> {
  const result = await resetUserPassword(row.id)
  if (result.initialPassword) {
    showSecret(result.initialPassword)
  }
}

function onDelete(row: UserView): void {
  dialog.error({
    title: '删除用户',
    content: `确认删除用户「${row.username}」?该操作不可撤销。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteUser(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(async () => {
  const [depts, roles] = await Promise.all([deptTree(), pageRoles({ pageNo: 1, pageSize: 100 })])
  deptOptions.value = toDeptOptions(depts)
  roleOptions.value = roles.list.map((role: RoleView) => ({ label: role.roleName, value: role.id }))
  await load(1)
})
</script>
