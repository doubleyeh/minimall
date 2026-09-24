<template>
  <n-space vertical :size="16">
    <n-alert type="info">
      微信支付配置按租户一份,普通商户与服务商两种模式。密钥/证书保存后不再回显,
      列表只显示"已配置";编辑时留空表示保持原值。
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
          <span>微信支付配置</span>
          <n-button v-perm="'system:wxpay:update'" type="primary" @click="openCreate">新建配置</n-button>
        </n-space>
      </template>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: WxPayConfigView) => row.tenantId"
        remote
        @update:page="load"
      />
    </n-card>

    <n-modal v-model:show="formVisible" preset="card" :title="editing ? '编辑支付配置' : '新建支付配置'" style="width: 720px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-grid :cols="2" :x-gap="16">
          <n-form-item-gi label="租户" path="tenantId">
            <n-select
              v-model:value="form.tenantId"
              :options="tenantOptions"
              :disabled="editing"
              placeholder="选择要配置的租户"
              filterable
            />
          </n-form-item-gi>
          <n-form-item-gi label="支付模式" path="payMode">
            <n-select v-model:value="form.payMode" :options="modeOptions" @update:value="onModeChange" />
          </n-form-item-gi>

          <n-form-item-gi :label="form.payMode === 'partner' ? '服务商商户号' : '商户号'" path="mchId">
            <n-input v-model:value="form.mchId" placeholder="微信支付商户号" />
          </n-form-item-gi>
          <n-form-item-gi v-if="form.payMode === 'partner'" label="特约商户号" path="subMchId">
            <n-input v-model:value="form.subMchId" placeholder="子商户号 sub_mchid" />
          </n-form-item-gi>

          <n-form-item-gi :label="form.payMode === 'partner' ? '服务商小程序 appId' : '小程序 appId'" path="appId">
            <n-input v-model:value="form.appId" placeholder="wx 开头" />
          </n-form-item-gi>
          <n-form-item-gi :label="secretLabel('appSecret', '小程序 appSecret')">
            <n-input v-model:value="form.appSecret" type="password" show-password-on="click" :placeholder="placeholder('appSecret')" />
          </n-form-item-gi>

          <template v-if="form.payMode === 'partner'">
            <n-form-item-gi label="特约商户小程序 appId">
              <n-input v-model:value="form.subAppId" placeholder="留空表示用服务商小程序收款" />
            </n-form-item-gi>
            <n-form-item-gi :label="secretLabel('subAppSecret', '特约商户 appSecret')">
              <n-input
                v-model:value="form.subAppSecret"
                type="password"
                show-password-on="click"
                :placeholder="placeholder('subAppSecret')"
              />
            </n-form-item-gi>
            <n-form-item-gi label="登录用哪套小程序">
              <n-select v-model:value="form.loginAppSource" :options="loginSourceOptions" />
            </n-form-item-gi>
          </template>

          <n-form-item-gi :label="secretLabel('apiV3Key', 'APIv3 密钥')">
            <n-input v-model:value="form.apiV3Key" type="password" show-password-on="click" :placeholder="placeholder('apiV3Key')" />
          </n-form-item-gi>
          <n-form-item-gi label="商户证书序列号" path="merchantSerialNo">
            <n-input v-model:value="form.merchantSerialNo" placeholder="请求签名用" />
          </n-form-item-gi>

          <n-form-item-gi :label="secretLabel('merchantPrivateKey', '商户私钥')">
            <n-input
              v-model:value="form.merchantPrivateKey"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 8 }"
              :placeholder="placeholder('merchantPrivateKey', 'PKCS#8 PEM,含头尾行')"
            />
          </n-form-item-gi>
          <n-form-item-gi label="平台证书序列号">
            <n-input v-model:value="form.platformSerialNo" placeholder="回调验签用" />
          </n-form-item-gi>
          <n-form-item-gi :label="secretLabel('platformPublicKey', '微信支付平台公钥')">
            <n-input
              v-model:value="form.platformPublicKey"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 8 }"
              :placeholder="placeholder('platformPublicKey', 'PEM 公钥,含头尾行')"
            />
          </n-form-item-gi>
          <n-form-item-gi label="状态">
            <n-select v-model:value="form.status" :options="statusOptions" />
          </n-form-item-gi>
          <n-form-item-gi label="备注">
            <n-input v-model:value="form.remark" />
          </n-form-item-gi>
        </n-grid>
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

import { pageTenants } from '@/api/tenant'
import {
  changeWxPayConfigStatus,
  createWxPayConfig,
  pageWxPayConfigs,
  updateWxPayConfig,
} from '@/api/wxPay'
import { usePermissionStore } from '@/stores/permission'
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui'
import type { Id } from '@/types/api'
import type { TenantView } from '@/types/system'
import type { WxPayConfigSaveRequest, WxPayConfigView, WxPayLoginAppSource, WxPayMode } from '@/types/wxPay'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<WxPayConfigView[]>([])
const tenantOptions = ref<SelectOption[]>([])

const query = reactive<{ tenantCode: string; status: number | null; pageNo: number; pageSize: number }>({
  tenantCode: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

const modeOptions: SelectOption[] = [
  { label: '普通商户', value: 'direct' },
  { label: '服务商', value: 'partner' },
]

const loginSourceOptions: SelectOption[] = [
  { label: '服务商自己的小程序', value: 'app' },
  { label: '特约商户的小程序', value: 'sub' },
]

const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0, showSizePicker: false })

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageWxPayConfigs({
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

/** 商户号脱敏:后台页面不需要展示完整商户号。 */
function maskMchId(value: string | null): string {
  if (!value) {
    return '-'
  }
  return value.length <= 4 ? value : `${value.slice(0, 4)}****`
}

const columns: DataTableColumns<WxPayConfigView> = [
  { title: '租户编码', key: 'tenantCode', width: 130 },
  { title: '租户名称', key: 'tenantName', width: 150 },
  {
    title: '是否已配置',
    key: 'configured',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.configured ? 'success' : 'warning', bordered: false },
        { default: () => (row.configured ? '已配置' : '未配置') },
      ),
  },
  {
    title: '模式',
    key: 'payMode',
    width: 100,
    render: (row) => (row.payMode === 'partner' ? '服务商' : row.payMode === 'direct' ? '普通商户' : '-'),
  },
  { title: '商户号', key: 'mchId', width: 140, render: (row) => maskMchId(row.mchId) },
  {
    title: '密钥/证书',
    key: 'secrets',
    width: 200,
    render: (row) => {
      if (!row.configured) {
        return '-'
      }
      const parts = [
        row.apiV3KeyConfigured ? 'v3密钥' : null,
        row.merchantPrivateKeyConfigured ? '商户私钥' : null,
        row.platformPublicKeyConfigured ? '平台公钥' : null,
      ].filter(Boolean)
      return parts.length ? parts.join(' / ') : '未填密钥'
    },
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '启用' : '停用') },
      ),
  },
  { title: '更新时间', key: 'updateTime', width: 180, render: (row) => row.updateTime ?? '-' },
  {
    title: '操作',
    key: 'actions',
    width: 180,
    render: (row) => {
      if (!permission.hasPerm('system:wxpay:update')) {
        return null
      }
      return h('div', { style: 'display:flex;gap:8px' }, [
        h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => (row.configured ? '编辑' : '去配置') }),
        row.configured
          ? h(
              NButton,
              { size: 'tiny', onClick: () => onToggleStatus(row) },
              { default: () => (row.status === 1 ? '停用' : '启用') },
            )
          : null,
      ])
    },
  },
]

// ——— 新建 / 编辑 ———

const formVisible = ref(false)
const editing = ref(false)
const formRef = ref<FormInst | null>(null)

/** 已配置的密钥字段:编辑时留空表示不改。 */
const configuredFlags = ref<Record<string, boolean>>({})

const form = reactive<{
  tenantId: Id | null
  payMode: WxPayMode
  mchId: string
  subMchId: string
  appId: string
  appSecret: string
  subAppId: string
  subAppSecret: string
  loginAppSource: WxPayLoginAppSource
  apiV3Key: string
  merchantSerialNo: string
  merchantPrivateKey: string
  platformSerialNo: string
  platformPublicKey: string
  status: number
  remark: string
}>({
  tenantId: null,
  payMode: 'direct',
  mchId: '',
  subMchId: '',
  appId: '',
  appSecret: '',
  subAppId: '',
  subAppSecret: '',
  loginAppSource: 'app',
  apiV3Key: '',
  merchantSerialNo: '',
  merchantPrivateKey: '',
  platformSerialNo: '',
  platformPublicKey: '',
  status: 1,
  remark: '',
})

const formRules: FormRules = {
  tenantId: [{ required: true, message: '请选择租户', trigger: ['change'], type: 'number' }],
  payMode: [{ required: true, message: '请选择支付模式', trigger: ['change'] }],
  mchId: [{ required: true, message: '请输入商户号', trigger: ['input', 'blur'] }],
  subMchId: [
    {
      validator: (_rule, value: string) =>
        form.payMode === 'partner' && !value ? new Error('服务商模式必须填特约商户号') : true,
      trigger: ['input', 'blur'],
    },
  ],
  appId: [{ required: true, message: '请输入小程序 appId', trigger: ['input', 'blur'] }],
  merchantSerialNo: [{ required: true, message: '请输入商户证书序列号', trigger: ['input', 'blur'] }],
  appSecret: [{ validator: requiredOnCreate(), trigger: ['input', 'blur'] }],
  apiV3Key: [{ validator: requiredOnCreate(), trigger: ['input', 'blur'] }],
  merchantPrivateKey: [{ validator: requiredOnCreate(), trigger: ['input', 'blur'] }],
  platformPublicKey: [{ validator: requiredOnCreate(), trigger: ['input', 'blur'] }],
}

/** 创建时必须填;编辑时留空表示保持原值。 */
function requiredOnCreate() {
  return (_rule: unknown, value: string) => {
    if (editing.value || value) {
      return true
    }
    return new Error('首次配置必须填写')
  }
}

function secretLabel(field: string, label: string): string {
  return configuredFlags.value[field] ? `${label}(已配置)` : label
}

function placeholder(field: string, hint = ''): string {
  if (editing.value && configuredFlags.value[field]) {
    return '已配置,留空保持不变'
  }
  return hint || '必填'
}

function onModeChange(): void {
  if (form.payMode === 'direct') {
    form.subMchId = ''
    form.subAppId = ''
    form.subAppSecret = ''
    form.loginAppSource = 'app'
  }
}

function resetForm(): void {
  Object.assign(form, {
    tenantId: null,
    payMode: 'direct' as WxPayMode,
    mchId: '',
    subMchId: '',
    appId: '',
    appSecret: '',
    subAppId: '',
    subAppSecret: '',
    loginAppSource: 'app' as WxPayLoginAppSource,
    apiV3Key: '',
    merchantSerialNo: '',
    merchantPrivateKey: '',
    platformSerialNo: '',
    platformPublicKey: '',
    status: 1,
    remark: '',
  })
}

function openCreate(): void {
  resetForm()
  configuredFlags.value = {}
  editing.value = false
  formVisible.value = true
}

function openEdit(row: WxPayConfigView): void {
  resetForm()
  editing.value = true
  configuredFlags.value = {
    appSecret: row.appSecretConfigured,
    subAppSecret: row.subAppSecretConfigured,
    apiV3Key: row.apiV3KeyConfigured,
    merchantPrivateKey: row.merchantPrivateKeyConfigured,
    platformPublicKey: row.platformPublicKeyConfigured,
  }
  Object.assign(form, {
    tenantId: row.tenantId,
    payMode: (row.payMode ?? 'direct') as WxPayMode,
    mchId: row.mchId ?? '',
    subMchId: row.subMchId ?? '',
    appId: row.appId ?? '',
    subAppId: row.subAppId ?? '',
    loginAppSource: (row.loginAppSource ?? 'app') as WxPayLoginAppSource,
    merchantSerialNo: row.merchantSerialNo ?? '',
    platformSerialNo: row.platformSerialNo ?? '',
    status: row.status ?? 1,
    remark: row.remark ?? '',
  })
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (!form.tenantId) {
    return
  }
  const payload: WxPayConfigSaveRequest = {
    tenantId: form.tenantId,
    payMode: form.payMode,
    mchId: form.mchId,
    subMchId: form.payMode === 'partner' ? form.subMchId : null,
    appId: form.appId,
    appSecret: form.appSecret || undefined,
    subAppId: form.subAppId || null,
    subAppSecret: form.subAppSecret || undefined,
    loginAppSource: form.payMode === 'partner' ? form.loginAppSource : 'app',
    apiV3Key: form.apiV3Key || undefined,
    merchantSerialNo: form.merchantSerialNo,
    merchantPrivateKey: form.merchantPrivateKey || undefined,
    platformSerialNo: form.platformSerialNo || null,
    platformPublicKey: form.platformPublicKey || undefined,
    status: form.status,
    remark: form.remark || null,
  }
  submitting.value = true
  try {
    if (editing.value) {
      await updateWxPayConfig(form.tenantId, payload)
      message.success('已保存')
    } else {
      await createWxPayConfig(payload)
      message.success('已创建')
    }
    formVisible.value = false
    await load(editing.value ? query.pageNo : 1)
  } finally {
    submitting.value = false
  }
}

// ——— 启停 ———

function onToggleStatus(row: WxPayConfigView): void {
  const next = row.status === 1 ? 0 : 1
  const apply = async (): Promise<void> => {
    await changeWxPayConfigStatus(row.tenantId, next)
    message.success(next === 1 ? '已启用' : '已停用')
    await load()
  }
  if (next === 0) {
    dialog.warning({
      title: '停用支付配置',
      content: '停用后该租户无法再发起支付,已生成的预支付单不受影响。确认继续?',
      positiveText: '确认停用',
      negativeText: '取消',
      onPositiveClick: apply,
    })
    return
  }
  void apply()
}

onMounted(async () => {
  const tenants = await pageTenants({ pageNo: 1, pageSize: 200, status: 1 })
  tenantOptions.value = tenants.list.map((item: TenantView) => ({
    label: `${item.tenantName}(${item.tenantCode})`,
    value: item.id,
  }))
  await load(1)
})
</script>
