import { createDiscreteApi } from 'naive-ui'

import { configProviderProps } from '@/theme'

/** 脱离组件上下文的 message/dialog/notification(前端文档 3.2)。主题与页面共用一份配置。 */
const { message, dialog, notification, loadingBar } = createDiscreteApi(
  ['message', 'dialog', 'notification', 'loadingBar'],
  { configProviderProps },
)

export { dialog, loadingBar, message, notification }
