import TDesign from 'tdesign-mobile-vue'
import 'tdesign-mobile-vue/es/style/index.css'
import { createApp } from 'vue'

import App from '@/App.vue'
import router from '@/router'

import './styles/global.css'

createApp(App).use(router).use(TDesign).mount('#app')
