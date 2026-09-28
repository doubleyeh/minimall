<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="昵称">
          <n-input v-model:value="query.nickname" clearable placeholder="模糊匹配" style="width: 160px" />
        </n-form-item>
        <n-form-item label="手机号">
          <n-input v-model:value="query.phone" clearable placeholder="模糊匹配" style="width: 160px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="search">查询</n-button>
            <n-button @click="resetQuery">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: CustomerView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <!-- 客户详情:两种流水都给 —— 积分的消耗不改变成长值(两者分账),只看一种查不出问题 -->
    <n-drawer v-model:show="detailVisible" :width="720">
      <n-drawer-content title="客户详情" closable>
        <n-descriptions v-if="detail" :column="2" label-placement="left" bordered size="small">
          <n-descriptions-item label="昵称">{{ detail.customer.nickname || '—' }}</n-descriptions-item>
          <n-descriptions-item label="手机号">{{ detail.customer.phone || '—' }}</n-descriptions-item>
          <n-descriptions-item label="等级">{{ detail.customer.memberLevelName }}</n-descriptions-item>
          <n-descriptions-item label="注册时间">{{ detail.customer.registerTime }}</n-descriptions-item>
          <n-descriptions-item label="可用积分">{{ detail.customer.points }}</n-descriptions-item>
          <n-descriptions-item label="成长值">{{ detail.customer.growthValue }}</n-descriptions-item>
        </n-descriptions>

        <n-divider>积分流水</n-divider>
        <n-data-table
          :columns="pointsLogColumns"
          :data="detail?.pointsLogs ?? []"
          :row-key="(row: PointsLogView) => row.id"
          size="small"
          :bordered="false"
        />

        <n-divider>成长值流水</n-divider>
        <n-data-table
          :columns="growthLogColumns"
          :data="detail?.growthLogs ?? []"
          :row-key="(row: GrowthLogView) => row.id"
          size="small"
          :bordered="false"
        />
      </n-drawer-content>
    </n-drawer>

    <n-modal v-model:show="adjustVisible" preset="card" title="调整积分与成长值" style="width: 460px">
      <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
        填正数增加、负数扣减。扣减会扣到 0 为止(不允许负积分),所以实际扣减量可能小于填写值。
        原因必填 —— 手工改动资产要留下"为什么",否则事后对账无从解释。
      </n-alert>
      <n-form ref="adjustFormRef" :model="adjustForm" :rules="adjustRules" label-placement="top">
        <n-form-item label="积分调整量">
          <n-input-number v-model:value="adjustForm.pointsDelta" placeholder="如 100 或 -50" style="width: 100%" />
        </n-form-item>
        <n-form-item label="成长值调整量">
          <n-input-number v-model:value="adjustForm.growthDelta" placeholder="如 100 或 -50" style="width: 100%" />
        </n-form-item>
        <n-form-item label="调整原因" path="remark">
          <n-input
            v-model:value="adjustForm.remark"
            type="textarea"
            :rows="2"
            maxlength="255"
            placeholder="如：客诉补偿 / 活动补发"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="adjustVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onAdjustSubmit">确认调整</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import { adjustCustomer, getCustomer, pageCustomers } from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type {
  CustomerDetailView,
  CustomerView,
  GrowthLogView,
  MemberValueAdjustRequest,
  PointsLogView,
} from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'

const message = useMessage()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<CustomerView[]>([])
const total = ref(0)

const query = reactive<{ nickname?: string; phone?: string; pageNo: number; pageSize: number }>({
  nickname: '',
  phone: '',
  pageNo: 1,
  pageSize: 10,
})

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

/** 积分与成长值都带符号展示:只看数字很容易把扣减看成增加。 */
function signed(value: number): string {
  return value > 0 ? `+${value}` : String(value)
}

const pointsLogColumns: DataTableColumns<PointsLogView> = [
  { title: '变动', key: 'changePoints', width: 90, render: (row) => signed(row.changePoints) },
  { title: '余额', key: 'balancePoints', width: 90 },
  { title: '原因', key: 'bizTypeText', width: 120 },
  { title: '备注', key: 'remark' },
  { title: '时间', key: 'createTime', width: 160 },
]

const growthLogColumns: DataTableColumns<GrowthLogView> = [
  { title: '变动', key: 'changeGrowth', width: 90, render: (row) => signed(row.changeGrowth) },
  { title: '原因', key: 'bizTypeText', width: 120 },
  { title: '备注', key: 'remark' },
  { title: '时间', key: 'createTime', width: 160 },
]

const columns: DataTableColumns<CustomerView> = [
  { title: '昵称', key: 'nickname', render: (row) => row.nickname || '—' },
  { title: '手机号', key: 'phone', render: (row) => row.phone || '—' },
  { title: '等级', key: 'memberLevelName', width: 120 },
  { title: '可用积分', key: 'points', width: 100 },
  { title: '成长值', key: 'growthValue', width: 100 },
  { title: '注册时间', key: 'registerTime', width: 170 },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    render: (row) =>
      h('div', { style: 'display: flex; gap: 8px' }, [
        permission.hasPerm('mall:customer:detail')
          ? h(NButton, { size: 'tiny', onClick: () => openDetail(row) }, { default: () => '详情' })
          : null,
        permission.hasPerm('mall:customer:adjust')
          ? h(NButton, { size: 'tiny', type: 'primary', onClick: () => openAdjust(row) }, { default: () => '调整' })
          : null,
      ]),
  },
]

const detailVisible = ref(false)
const detail = ref<CustomerDetailView | null>(null)

const adjustVisible = ref(false)
const adjustFormRef = ref<FormInst | null>(null)
const adjustingId = ref<Id>('')
const adjustForm = reactive<MemberValueAdjustRequest>({
  pointsDelta: null,
  growthDelta: null,
  remark: '',
})

const adjustRules: FormRules = {
  remark: [{ required: true, message: '请填写调整原因', trigger: ['input', 'blur'] }],
}

onMounted(() => {
  void load()
})

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageCustomers({
      nickname: query.nickname || undefined,
      phone: query.phone || undefined,
      pageNo: page,
      pageSize: query.pageSize,
    })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  void load(1)
}

function resetQuery(): void {
  query.nickname = ''
  query.phone = ''
  void load(1)
}

function onPageChange(page: number): void {
  void load(page)
}

async function openDetail(row: CustomerView): Promise<void> {
  try {
    detail.value = await getCustomer(row.id)
    detailVisible.value = true
  } catch {
    // 具体原因由 request 层统一提示
  }
}

function openAdjust(row: CustomerView): void {
  adjustingId.value = row.id
  adjustForm.pointsDelta = null
  adjustForm.growthDelta = null
  adjustForm.remark = ''
  adjustVisible.value = true
}

/**
 * 提交调整。
 *
 * "两个都不填"在端上先挡一次:后端也会报错,但那是一条 40003 的业务错误,
 * 不如在这里直接说清"至少要调整一项"。
 */
async function onAdjustSubmit(): Promise<void> {
  const points = adjustForm.pointsDelta ?? 0
  const growth = adjustForm.growthDelta ?? 0
  if (points === 0 && growth === 0) {
    message.warning('积分与成长值至少要调整一项')
    return
  }
  try {
    await adjustFormRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    await adjustCustomer(adjustingId.value, {
      pointsDelta: points,
      growthDelta: growth,
      remark: adjustForm.remark,
    })
    message.success('已调整')
    adjustVisible.value = false
    await load()
    // 详情可能开着:跟着刷新,否则抽屉里还是调整前的数字
    if (detailVisible.value) {
      detail.value = await getCustomer(adjustingId.value)
    }
  } catch {
    // 具体原因由 request 层统一提示
  } finally {
    submitting.value = false
  }
}
</script>
