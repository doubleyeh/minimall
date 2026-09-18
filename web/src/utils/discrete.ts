import { createDiscreteApi } from 'naive-ui'
import { dateZhCN, zhCN } from 'naive-ui'

import { themeOverrides } from '@/theme'

/**
 * 脱离组件上下文的 message/dialog/notification(前端文档 3.2,**强制**)。
 *
 * <p>为什么必须这样:`useMessage()` 依赖组件上下文,在 `request.ts` 这类模块里调用**编写期不报错**,
 * 首次触发时才抛 "must be called inside setup" 或拿到 undefined —— 也就是说,错误提示这件"兜底功能"
 * 会在真正需要它的那一刻(接口报错时)才失效,是最难被发现的一类问题。
 *
 * <p>这里传的 `configProviderProps` 与 `n-config-provider` 用的是**同一份**主题与语言配置,
 * 否则弹出来的提示会和页面样式/语言不一致。
 */
const { message, dialog, notification, loadingBar } = createDiscreteApi(
  ['message', 'dialog', 'notification', 'loadingBar'],
  {
    configProviderProps: {
      themeOverrides,
      locale: zhCN,
      dateLocale: dateZhCN,
    },
  },
)

export { dialog, loadingBar, message, notification }
