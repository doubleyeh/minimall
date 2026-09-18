<template>
  <n-space vertical :size="16">
    <n-alert type="info">
      租户是平台级数据,只有平台超管能操作。禁用/改有效期是惰性生效的:
      该租户用户会在下一次请求时被退出登录,不是立刻断开。
    </n-alert>

    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="租户编码">
          <n-input v-model:value="query.tenantCode" clearable placeholder="模糊匹配" style="width: 180px" />
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
          <span>租户列表</span>
          <n-button v-perm="'system:tenant:create'" type="primary" @click="openCreate">新建租户</n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: TenantView) => row.id"
        remote
        @update:page="load"
      />
    </n-card>

    <!-- 新建租户 -->
    <n-modal v-model:show="formVisible" preset="card" title="新建租户" style="width: 600px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="租户编码" path="tenantCode">
          <n-input v-model:value="form.tenantCode" placeholder="小写字母/数字/短横线,登录时要用" />
        </n-form-item>
        <n-form-item label="租户名称" path="tenantName"><n-input v-model:value="form.tenantName" /></n-form-item>
        <n-form-item label="套餐" path="packageId">
          <n-select v-model:value="form.packageId" :options="packageOptions" placeholder="必选:套餐决定该租户的初始权限" />
        </n-form-item>
        <n-form-item label="有效期">
          <n-date-picker v-model:value="form.expireTime" type="datetime" clearable placeholder="留空表示不过期" />
        </n-form-item>
        <n-divider>初始管理员</n-divider>
        <n-form-item label="管理员用户名" path="adminUsername">
          <n-input v-model:value="form.adminUsername" placeholder="登录该租户时使用" />
        </n-form-item>
        <n-form-item label="管理员昵称"><n-input v-model:value="form.adminNickname" /></n-form-item>
        <n-form-item label="初始密码">
          <n-input v-model:value="form.adminPassword" placeholder="留空由系统随机生成(只返回一次)" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onSubmit">创建</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 换套餐 -->
    <n-modal v-model:show="packageVisible" preset="card" title="变更套餐" style="width: 480px">
      <n-alert type="warning" class="mb-12">
        升级只给默认管理员角色补菜单;降级会立即收回该租户全部角色里套餐外的菜单。
      </n-alert>
      <n-select v-model:value="targetPackageId" :options="packageOptions" placeholder="选择目标套餐" />
      <template #footer>
        <n-space justify="end">
          <n-button @click="packageVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onChangePackage">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 一次性初始密码 -->
    <n-modal v-model:show="secretVisible" preset="card" title="初始管理员密码" style="width: 480px">
      <n-alert type="warning" :show-icon="true">
        这个密码只显示这一次,请立即复制并交给该租户的管理员;对方首次登录会被要求改密。
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

import { pagePackages } from '@/api/package'
import { changeTenantPackage, changeTenantStatus, createTenant, pageTenants } from '@/api/tenant'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui'
import type { PackageView, TenantCreateRequest, TenantView } from '@/types/system'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

/** 平台租户不允许禁用 —— 它是所有平台级能力的载体,禁掉会导致谁都进不了后台。 */
const PLATFORM_TENANT_CODE = 'platform'

const loading = ref(false)
const submitting = ref(false)
const rows = ref<TenantView[]>([])
const packageOptions = ref<SelectOption[]>([])

const query = reactive<{ tenantCode: string; status: number | null; pageNo: number; pageSize: number }>({
  tenantCode: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]

const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0, showSizePicker: false })

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageTenants({
      tenantCode: query.tenantCode || undefined,
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
  query.tenantCode = ''
  query.status = null
  void load(1)
}

const columns: DataTableColumns<TenantView> = [
  { title: '租户编码', key: 'tenantCode', width: 160 },
  { title: '租户名称', key: 'tenantName', width: 180 },
  { title: '套餐', key: 'packageName', width: 160, render: (row) => row.packageName ?? '-' },
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
  { title: '有效期', key: 'expireTime', width: 180, render: (row) => row.expireTime ?? '不过期' },
  {
    title: '操作',
    key: 'actions',
    width: 240,
    render: (row) => {
      const isPlatformTenant = row.tenantCode === PLATFORM_TENANT_CODE
      return h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('system:tenant:package')
          ? h(NButton, { size: 'tiny', onClick: () => openChangePackage(row) }, { default: () => '换套餐' })
          : null,
        permission.hasPerm('system:tenant:status')
          ? h(
              NButton,
              {
                size: 'tiny',
                disabled: isPlatformTenant,
                title: isPlatformTenant ? '平台租户不允许禁用' : undefined,
                onClick: () => onToggleStatus(row),
              },
              { default: () => (row.status === 1 ? '禁用' : '启用') },
            )
          : null,
      ])
    },
  },
]

// ——— 新建租户 ———

const formVisible = ref(false)
const formRef = ref<FormInst | null>(null)
const form = reactive<{
  tenantCode: string
  tenantName: string
  packageId: Id | null
  expireTime: number | null
  adminUsername: string
  adminNickname: string
  adminPassword: string
}>({
  tenantCode: '',
  tenantName: '',
  packageId: null,
  expireTime: null,
  adminUsername: '',
  adminNickname: '',
  adminPassword: '',
})

const formRules: FormRules = {
  tenantCode: [{ required: true, message: '请输入租户编码', trigger: ['input', 'blur'] }],
  tenantName: [{ required: true, message: '请输入租户名称', trigger: ['input', 'blur'] }],
  packageId: [{ required: true, message: '请选择套餐', trigger: ['change'] }],
  adminUsername: [{ required: true, message: '请输入管理员用户名', trigger: ['input', 'blur'] }],
}

function openCreate(): void {
  Object.assign(form, {
    tenantCode: '',
    tenantName: '',
    packageId: null,
    expireTime: null,
    adminUsername: '',
    adminNickname: '',
    adminPassword: '',
  })
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (!form.packageId) {
    return
  }
  const payload: TenantCreateRequest = {
    tenantCode: form.tenantCode,
    tenantName: form.tenantName,
    packageId: form.packageId,
    // 后端是 LocalDateTime:这里传本地时间字符串,不带时区后缀
    expireTime: form.expireTime ? new Date(form.expireTime).toISOString().slice(0, 19) : null,
    adminUsername: form.adminUsername,
    adminNickname: form.adminNickname,
    adminPassword: form.adminPassword || null,
  }
  submitting.value = true
  try {
    const created = await createTenant(payload)
    formVisible.value = false
    if (created.initialPassword) {
      secret.value = created.initialPassword
      secretVisible.value = true
    } else {
      message.success('租户已创建')
    }
    await load(1)
  } finally {
    submitting.value = false
  }
}

// ——— 换套餐 ———

const packageVisible = ref(false)
const targetPackageId = ref<Id | null>(null)
const targetTenantId = ref<Id | null>(null)

function openChangePackage(row: TenantView): void {
  targetTenantId.value = row.id
  targetPackageId.value = row.packageId
  packageVisible.value = true
}

async function onChangePackage(): Promise<void> {
  if (!targetTenantId.value || !targetPackageId.value) {
    message.warning('请选择目标套餐')
    return
  }
  submitting.value = true
  try {
    await changeTenantPackage(targetTenantId.value, targetPackageId.value)
    message.success('已变更,权限变更在用户下一次请求生效')
    packageVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// ——— 启停 ———

function onToggleStatus(row: TenantView): void {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    dialog.warning({
      title: '禁用租户',
      content: '禁用后该租户用户将在下一次请求时退出登录,并非立刻断开。确认继续?',
      positiveText: '确认禁用',
      negativeText: '取消',
      onPositiveClick: async () => {
        await changeTenantStatus(row.id, 0)
        message.success('已禁用,该租户用户将在下一次请求时退出')
        await load()
      },
    })
    return
  }
  void changeTenantStatus(row.id, 1).then(() => {
    message.success('已启用')
    return load()
  })
}

// ——— 一次性密码 ———

const secretVisible = ref(false)
const secret = ref('')

async function copySecret(): Promise<void> {
  await navigator.clipboard.writeText(secret.value)
  message.success('已复制')
}

onMounted(async () => {
  const packages = await pagePackages({ pageNo: 1, pageSize: 100, status: 1 })
  packageOptions.value = packages.list.map((item: PackageView) => ({
    label: `${item.packageName}(菜单 ${item.menuCount} 项)`,
    value: item.id,
  }))
  await load(1)
})
</script>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}
</style>
