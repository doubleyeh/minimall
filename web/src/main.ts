import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { vPerm } from './directives/perm'
import { router } from './router'
import './styles/global.css'

/**
 * 应用入口。
 *
 * <p>顺序有讲究:`pinia` 必须在 `router` 之前装 —— 路由守卫里要用 store(见前端文档 5.2),
 * 反过来的话守卫首次执行时 pinia 还没注册,`useUserStore()` 会抛 "no active Pinia"。
 * `request.ts` 里注册的 403 / 登录失效回调也在 `router` 模块加载时一并生效。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.directive('perm', vPerm)
app.mount('#app')
