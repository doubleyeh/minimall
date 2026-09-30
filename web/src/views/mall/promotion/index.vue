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
        <n-form-item label="减免阶梯">
          <n-space vertical :size="8" style="width: 100%">
            <n-space v-for="(tier, index) in tiers" :key="index" align="center" :size="8">
              <n-text depth="3" style="width: 56px">第 {{ index + 1 }} 档</n-text>
              <n-text>满</n-text>
              <n-input-number
                v-model:value="tier.amount"
                :min="0.01"
                :precision="2"
                placeholder="门槛金额"
                style="width: 150px"
              />
              <n-text>减</n-text>
              <n-input-number
                v-model:value="tier.reduce"
                :min="0.01"
                :precision="2"
                placeholder="减免金额"
                style="width: 150px"
              />
              <n-button v-if="tiers.length > 1" size="small" type="error" ghost @click="removeTier(index)">
                删除
              </n-button>
            </n-space>
            <n-button size="small" dashed @click="addTier">添加阶梯</n-button>
          </n-space>
        </n-form-item>
        <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
          每一档的「减」必须小于「满」;下一档的「满」与「减」都要大于上一档;顺序按表格行序写进
          sort 字段。保存时前后端都会校验 —— 规则写坏的表现是活动看着启用中、用户却下不了单。
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
import { toLocalDateTime } from '@/utils/datetime'

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

/** 阶梯表格的一行。提交时序列化成后端要的 JSON(`PromotionSaveRequest.reductionRule`) */
interface Tier {
  amount: number | null
  reduce: number | null
}

const tiers = ref<Tier[]>([{ amount: 100, reduce: 10 }])

const form = reactive<PromotionSaveRequest>({
  activityName: '',
  reductionRule: '',
  scopeType: 1,
  scopeIds: [],
  validStartTime: '',
  validEndTime: '',
  status: 1,
})

const formRules: FormRules = {
  activityName: { required: true, message: '请输入活动名称', trigger: ['blur', 'input'] },
}

/** 新档预填成"比上一档大一点":最常见的填法,省掉两次输入 */
function addTier(): void {
  const last = tiers.value[tiers.value.length - 1]
  tiers.value.push({
    amount: last?.amount ? Number(last.amount) + 100 : 100,
    reduce: last?.reduce ? Number(last.reduce) + 10 : 10,
  })
}

function removeTier(index: number): void {
  tiers.value.splice(index, 1)
}

/**
 * 阶梯校验,返回第一条不满足的说明;{@code null} 表示合法。
 *
 * <p>与后端 {@code OrderAmountCalculator#validateReductionRule} 的约束一一对应。
 * 前端校验是为了让运营当场看到哪里不对,真正的门在服务端(端上可以改请求体)。
 */
function tierRuleError(): string | null {
  if (tiers.value.length === 0) {
    return '至少要有一个阶梯'
  }
  let prevAmount: number | null = null
  let prevReduce: number | null = null
  for (let i = 0; i < tiers.value.length; i++) {
    const no = i + 1
    const { amount, reduce } = tiers.value[i]
    if (amount == null || reduce == null || amount <= 0 || reduce <= 0) {
      return `第 ${no} 档:满和减都要填大于 0 的金额`
    }
    if (reduce >= amount) {
      return `第 ${no} 档:减的金额必须小于满的金额`
    }
    if (prevAmount != null && amount <= prevAmount) {
      return `第 ${no} 档:满的金额必须大于上一档`
    }
    if (prevReduce != null && reduce <= prevReduce) {
      return `第 ${no} 档:减的金额必须大于上一档`
    }
    prevAmount = amount
    prevReduce = reduce
  }
  return null
}

/** 把库里存的 JSON 还原成表格行;解析不出来就返回空数组,由调用方兜底成一行空档 */
function parseTiers(rule: string): Tier[] {
  try {
    const parsed = JSON.parse(rule) as { sort?: number; amount?: number; reduce?: number }[]
    if (!Array.isArray(parsed)) {
      return []
    }
    // 按 sort 回显:顺序存在数据里,不依赖数组位置。历史数据没有 sort 时按原顺序兜底
    return parsed
      .map((item, index) => ({
        sort: item?.sort == null ? index + 1 : Number(item.sort),
        amount: item?.amount == null ? null : Number(item.amount),
        reduce: item?.reduce == null ? null : Number(item.reduce),
      }))
      .sort((left, right) => left.sort - right.sort)
      .map((tier) => ({ amount: tier.amount, reduce: tier.reduce }))
  } catch {
    return []
  }
}

/** 顺序写进每一档的 sort(从 1 开始):档位顺序是这份配置的语义,不能只靠数组下标表达 */
function serializeTiers(): string {
  return JSON.stringify(
    tiers.value.map((tier, index) => ({ sort: index + 1, amount: tier.amount, reduce: tier.reduce })),
  )
}

function splitIds(value: string): Id[] {
  return value
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function openCreate(): void {
  editingId.value = null
  form.activityName = ''
  tiers.value = [{ amount: 100, reduce: 10 }]
  form.scopeType = 1
  form.scopeIds = []
  form.status = 1
  validRange.value = null
  formVisible.value = true
}

function openEdit(row: PromotionView): void {
  editingId.value = row.id
  form.activityName = row.activityName
  form.scopeType = row.scopeType
  form.scopeIds = row.scopeIds ?? []
  form.status = row.status
  validRange.value = [new Date(row.validStartTime).getTime(), new Date(row.validEndTime).getTime()]
  const restored = parseTiers(row.reductionRule)
  if (restored.length === 0) {
    // 库里这条规则解析不出来(多半是历史坏数据):给一行空档让运营重填,而不是显示一片空白让人猜
    message.warning('这条活动的减免规则无法解析,请重新填写阶梯')
    tiers.value = [{ amount: null, reduce: null }]
  } else {
    tiers.value = restored
  }
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  const ruleError = tierRuleError()
  if (ruleError) {
    message.warning(ruleError)
    return
  }
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
      reductionRule: serializeTiers(),
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
