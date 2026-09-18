import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { router } from './router'

/**
 * 应用入口。
 *
 * <p>顺序有讲究:`pinia` 必须在 `router` 之前装 —— 路由守卫里要用 store(见前端文档 5.2),
 * 反过来的话守卫首次执行时 pinia 还没注册,`useUserStore()` 会抛 "no active Pinia"。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
