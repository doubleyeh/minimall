<template>
  <n-space vertical :size="16">
    <n-card title="当前身份">
      <n-descriptions :column="4" label-placement="left">
        <n-descriptions-item label="昵称">{{ user.profile?.nickname || '-' }}</n-descriptions-item>
        <n-descriptions-item label="用户 ID">{{ user.profile?.userId || '-' }}</n-descriptions-item>
        <n-descriptions-item label="租户 ID">{{ user.profile?.tenantId || '-' }}</n-descriptions-item>
        <n-descriptions-item label="账号类型">
          <n-tag :type="user.isSuperUser ? 'warning' : 'default'" size="small" :bordered="false">
            {{ user.isSuperUser ? '平台超管' : '租户用户' }}
          </n-tag>
        </n-descriptions-item>
      </n-descriptions>
    </n-card>

    <n-card title="权限概览">
      <n-space vertical :size="16">
        <div class="dash__stats">
          <div class="dash__stat">
            <span class="dash__stat-value">{{ permission.menus.length }}</span>
            <span class="dash__stat-label">可见菜单</span>
          </div>
          <div class="dash__stat">
            <span class="dash__stat-value">{{ permission.permCodes.length }}</span>
            <span class="dash__stat-label">权限码</span>
          </div>
        </div>

        <n-text depth="3">
          权限是登录那一刻的快照。后台改了角色授权后,前端要重新登录或刷新页面才会更新;
          收到 403 时会自动重建一次(限频 10 秒)。
        </n-text>

        <n-space :size="8" style="flex-wrap: wrap">
          <n-tag
            v-for="code in permission.permCodes"
            :key="code"
            size="small"
            type="info"
            :bordered="false"
            class="mm-code"
          >
            {{ code }}
          </n-tag>
        </n-space>
      </n-space>
    </n-card>
  </n-space>
</template>

<script setup lang="ts">
import { usePermissionStore } from '@/stores/permission'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const permission = usePermissionStore()
</script>

<style scoped>
/* 权限数量用等宽数字 + 大字号 */
.dash__stats {
  display: flex;
  gap: 36px;
}

.dash__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.dash__stat-value {
  font-size: 30px;
  font-weight: 650;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.dash__stat-label {
  font-size: 12px;
  color: var(--mm-text-3);
  letter-spacing: 0.02em;
}
</style>
