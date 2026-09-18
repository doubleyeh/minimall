import type { Directive } from 'vue'

import { usePermissionStore } from '@/stores/permission'

/**
 * `v-perm` 按钮级权限(前端文档 5.3)。
 *
 * 用法:`<n-button v-perm="'system:user:create'">新增</n-button>` 或 `v-perm="['a','b']"`(任一命中即可)。
 *
 * 三条注意:
 * 1. `perm_code` 原样使用,禁止前端拼接或改造(格式是 `模块:资源:操作`)。
 * 2. 超管不需要特判:后端给超管返回的就是全量权限码(5.3 第 3 条)。
 * 3. 它不是安全边界,只是 UI 显隐 —— 真正的校验在后端(后端的 `@SaCheckPermission`)。
 *    所以这里"移除元素"就够,不需要额外的兜底逻辑。
 *
 * 实现细节:用 `mounted` 而不是 `updated`。权限快照在一次会话内不会变(要变得先重建路由,
 * 那时页面已经重新渲染),再挂 `updated` 反而会在某些列表重排场景下误删后来的元素。
 */
export const vPerm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    if (codes.length === 0 || !usePermissionStore().hasPerm(codes)) {
      el.parentNode?.removeChild(el)
    }
  },
}
