/**
 * naive-ui 常用类型的统一出口(前端文档 1.1)。
 *
 * <p>为什么单独收口:`unplugin-auto-import` 只能注入运行时的值(`useMessage` 这类),
 * 类型没法被它注入;而 `unplugin-vue-components` 的 resolver 只处理组件。所以类型统一从这里 import,
 * 既满足"不手写 naive-ui 的深路径类型导入",也让"项目里用过哪些 naive 类型"一眼可见。
 *
 * 用法:`import type { DataTableColumns, FormInst } from '@/types/naive'`
 */
export type {
  DataTableColumns,
  FormInst,
  FormRules,
  GlobalThemeOverrides,
  MenuOption,
  SelectOption,
  TreeOption,
} from 'naive-ui'
